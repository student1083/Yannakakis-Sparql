package at.ac.tuwien.thesis.yannakakis;

import at.ac.tuwien.thesis.yannakakis.QueryClassifier.Classification;
import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fusion of dimension relations, Wang et al. §5.1: "When a query involves joins between
 * a large relation and multiple small relations, the optimizer can enhance efficiency by
 * first joining the small relations, or even using Cartesian products if they lack common
 * attributes. This is because a join or semi-join with the large relation can be more
 * costly than performing a Cartesian product of the small relations." Their example is
 * R1(a) ⋈ R2(a, b) ⋈ R3(b) with |R1|, |R3| ≪ |R2|: compute R1 × R3 first, saving one join
 * or semijoin with R2.
 *
 * <p>The relational version relies on nothing but sizes, so it transfers to RDF (unlike
 * the key-based rules of the same section, see CLAUDE.md). On a join tree it reads: for a
 * node R with leaf children S_1..S_k, replace a set of those leaves by one fused node
 * F = S_1 ⋈ ... ⋈ S_k (a Cartesian product where they share no variable), a leaf under R.
 * Every first-round step the leaves would have caused against R — one absorption join or
 * semijoin each — collapses into the single step F causes. The result is again a join
 * tree: a variable shared by two fused leaves lies in R by running intersection, so F's
 * variables meet the rest of the tree only through R, exactly as each leaf's did.
 *
 * <p><b>When.</b> A set of leaves is fused when est(R) ≥ θ · ∏ est(S_i), θ being
 * {@link YannakakisSymbols#FUSION_RATIO}. Leaves are tried smallest first and added
 * greedily while the inequality holds (empty relations count as size 1 — the product is
 * a bound on the work of building F); at least two leaves must qualify. Estimates saturate
 * at the counting bound, so a saturated est(R) reads "at least the bound" and lets every
 * leaf set whose product stays under it fuse.
 *
 * <p><b>Class preservation.</b> Fusion must not push a query out of the guarantees of its
 * class, so leaves are grouped and only fused within a group:
 * <ul>
 *   <li><em>absorbable</em> leaves, A_i ∩ O ⊆ A_R: the first round absorbs each of them
 *       into R, and A_F ∩ O = ⋃(A_i ∩ O) ⊆ A_R, so F is absorbed too. Every leaf of a
 *       relation-dominated tree and every leaf outside the connex subtree Tn of a
 *       free-connex tree is absorbable, so Theorem 3.11 and the "no general merge" claim
 *       survive.</li>
 *   <li><em>connex-compatible</em> leaves, A_i ∩ A_R ⊆ O, that are not absorbable: these
 *       are leaves of Tn carrying output variables R lacks. A_F ∩ A_R = ⋃(A_i ∩ A_R) ⊆ O,
 *       so F takes their place in Tn and round two still merges it without dropping
 *       anything a neighbour needs.</li>
 * </ul>
 * A leaf that is neither (only in a general acyclic tree) is never fused. Fusing across
 * the two groups could produce an F that is neither absorbed nor mergeable, hence the
 * separation.
 *
 * <p>Not covered: a large relation whose small neighbours are its parent and siblings.
 * The §5.2 heuristics in {@link QueryClassifier} make large relations parents where the
 * structure allows, which is what brings this rule its opportunities.
 */
public final class DimensionFusion {

    private DimensionFusion() {}

    /** One fusion: the leaves {@code leafIds} under {@code parentId} replaced by the fused edge {@code fusedId}. */
    public record Fusion(int parentId, List<Integer> leafIds, int fusedId) {
        public Fusion {
            leafIds = List.copyOf(leafIds);
        }
    }

    /** A classification after fusion (identical to the input when nothing was fused) and the fusions made. */
    public record Fused(Classification classification, List<Fusion> fusions) {
        public Fused {
            fusions = List.copyOf(fusions);
        }

        public boolean isEmpty() { return fusions.isEmpty(); }

        /**
         * The relations for the fused tree: {@code relations} (keyed by triple/edge id)
         * minus the fused leaves, plus for each fusion the join of its leaves' relations,
         * smallest first, under the fused id. The join is a Cartesian product when the
         * leaves share no variable ({@link Relation#join}).
         */
        public Map<Integer, Relation> fuseRelations(Map<Integer, Relation> relations) {
            Map<Integer, Relation> out = new LinkedHashMap<>(relations);
            for (Fusion f : fusions) {
                Relation fused = null;
                for (Integer id : f.leafIds()) {
                    Relation part = out.remove(id);
                    if (part == null) throw new IllegalArgumentException("missing relation for fused leaf " + id);
                    fused = fused == null ? part : fused.join(part);
                }
                out.put(f.fusedId(), fused);
            }
            return out;
        }
    }

    /**
     * Applies the rule to every node of {@code cl}'s tree.
     *
     * @param estimates edge id → estimated relation size (missing: 0)
     * @param ratio θ; fusion is off for NaN, negative or infinite values, and θ = 0 fuses every eligible group
     */
    public static Fused apply(Classification cl, Map<Integer, Long> estimates, double ratio) {
        JoinTree tree = cl.joinTree();
        if (tree.root() == null || Double.isNaN(ratio) || Double.isInfinite(ratio) || ratio < 0) {
            return new Fused(cl, List.of());
        }
        Set<Var> o = cl.outputVars();
        int nextId = 0;
        for (JoinTree.Node n : tree.nodes()) nextId = Math.max(nextId, n.edge().id() + 1);

        List<Fusion> fusions = new ArrayList<>();
        for (JoinTree.Node r : tree.nodes()) {
            List<JoinTree.Node> absorbable = new ArrayList<>();
            List<JoinTree.Node> connex = new ArrayList<>();
            for (JoinTree.Node c : r.children()) {
                if (!c.children().isEmpty()) continue;                  // not a leaf
                if (r.vars().containsAll(intersect(c.vars(), o))) absorbable.add(c);
                else if (o.containsAll(intersect(c.vars(), r.vars()))) connex.add(c);
            }
            for (List<JoinTree.Node> group : List.of(absorbable, connex)) {
                List<Integer> chosen = choose(r, group, estimates, ratio);
                if (chosen.size() >= 2) fusions.add(new Fusion(r.edge().id(), chosen, nextId++));
            }
        }
        if (fusions.isEmpty()) return new Fused(cl, List.of());
        return new Fused(rebuild(cl, fusions), fusions);
    }

    /** The largest prefix (smallest first) of {@code group} whose size product stays within est(R) / θ. */
    private static List<Integer> choose(JoinTree.Node r, List<JoinTree.Node> group, Map<Integer, Long> estimates, double ratio) {
        if (group.size() < 2) return List.of();
        List<JoinTree.Node> sorted = new ArrayList<>(group);            // stable: ties keep child order
        sorted.sort(Comparator.comparingLong(n -> estimates.getOrDefault(n.edge().id(), 0L)));
        double large = estimates.getOrDefault(r.edge().id(), 0L);
        double product = 1;
        List<Integer> chosen = new ArrayList<>();
        for (JoinTree.Node s : sorted) {
            double next = product * Math.max(1, estimates.getOrDefault(s.edge().id(), 0L));
            if (large < ratio * next) break;                             // later leaves are no smaller
            product = next;
            chosen.add(s.edge().id());
        }
        return chosen;
    }

    /** The classification over the fused tree: same root and kind, fused leaves replaced, Tn adjusted. */
    private static Classification rebuild(Classification cl, List<Fusion> fusions) {
        JoinTree tree = cl.joinTree();
        Set<Integer> fusedAway = new LinkedHashSet<>();
        for (Fusion f : fusions) fusedAway.addAll(f.leafIds());

        List<QueryHypergraph.Hyperedge> edges = new ArrayList<>();
        Map<Integer, Integer> parentOf = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> pc : tree.parentMap().entrySet()) {
            if (!fusedAway.contains(pc.getKey())) parentOf.put(pc.getKey(), pc.getValue());
        }
        for (JoinTree.Node n : tree.nodes()) {
            if (!fusedAway.contains(n.edge().id())) edges.add(n.edge());
        }
        for (Fusion f : fusions) {
            Set<Var> vars = new LinkedHashSet<>();
            for (Integer id : f.leafIds()) vars.addAll(tree.node(id).vars());
            edges.add(QueryHypergraph.Hyperedge.fused(f.fusedId(), vars));
            parentOf.put(f.fusedId(), f.parentId());
        }
        JoinTree fusedTree = JoinTree.build(edges, tree.root().edge().id(), parentOf);

        Set<JoinTree.Node> tn = new LinkedHashSet<>();
        switch (cl.kind()) {
            case RELATION_DOMINATED -> tn.add(fusedTree.root());
            case ACYCLIC -> tn.addAll(fusedTree.nodes());
            case FREE_CONNEX -> {
                Set<Integer> oldTn = new LinkedHashSet<>();
                for (JoinTree.Node n : cl.connexSubtree()) oldTn.add(n.edge().id());
                for (JoinTree.Node n : fusedTree.nodes()) {
                    if (oldTn.contains(n.edge().id())) tn.add(n);
                }
                for (Fusion f : fusions) {
                    JoinTree.Node fused = fusedTree.node(f.fusedId());
                    // F joins Tn iff its parent is in Tn and it shares only output variables with it
                    if (oldTn.contains(f.parentId())
                            && cl.outputVars().containsAll(intersect(fused.vars(), fused.parent().vars()))) {
                        tn.add(fused);
                    }
                }
            }
        }
        return new Classification(cl.kind(), fusedTree, tn, cl.outputVars());
    }

    private static Set<Var> intersect(Set<Var> a, Set<Var> b) {
        Set<Var> out = new LinkedHashSet<>(a);
        out.retainAll(b);
        return out;
    }

}
