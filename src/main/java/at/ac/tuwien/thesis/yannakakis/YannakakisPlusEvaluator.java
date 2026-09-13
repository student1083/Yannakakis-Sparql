package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Output-sensitive Yannakakis (Wang et al., Algorithms 1 and 2) over a {@link JoinTree}
 * rooted as chosen by {@link QueryClassifier}. {@link YannakakisEvaluator} is left
 * untouched and serves as the differential oracle for this class.
 *
 * <p>Notation (thesis Chapter 3): O = output variables, A_i = variables of relation R_i,
 * A_i^- = A_i ∩ ⋃_{j≠i} A_j = the join variables of R_i (computed over the whole query,
 * not over the shrinking tree).
 *
 * <p>Relations carry multiplicities ({@link Relation}: the counting semiring), so the
 * early projections below are exact under SPARQL bag semantics; nothing here depends on
 * whether the query is DISTINCT — that is a final {@link Relation#distinct()} in the caller.
 *
 * <p><b>First round</b> ({@link #firstRound}):
 * <pre>
 *   arrange R_1..R_n in post-order (R_n = root)
 *   for i = 1 to n-1:
 *      Rp = parent of R_i
 *      if R_i is a leaf and A_i ∩ O ⊆ A_p:
 *          Rp := Rp ⋈ π_{A_p}(R_i)        # absorb, no semijoin
 *          remove R_i from the tree
 *      else:
 *          R_i := π_{O ∪ A_i^-}(R_i)      # early projection
 *          Rp := Rp ⋉ R_i
 *   R_n := π_{O ∪ A_n^-}(R_n)
 * </pre>
 *
 * "Leaf" is evaluated on the current tree: a node whose children were all absorbed is a
 * leaf when its own turn comes. Under set semantics the absorption join equals a semijoin
 * (π_{A_p}(R_i) has schema A_i ∩ A_p); it is nevertheless performed as a join and counted
 * separately, because the claim of Chapter 3 is about the number of semijoins.
 *
 * <p>Theorem 3.11: for a relation-dominated query rooted at the dominating relation the
 * loop absorbs every other node — by running intersection A_i ∩ O ⊆ A_i ∩ A_root ⊆ A_p
 * for every leaf — so exactly one node remains, no semijoin is performed, and π_O of that
 * node is the answer. {@link #evaluate(QueryClassifier.Classification, Map)} asserts this.
 *
 * <p><b>Second round</b> ({@link #secondRound}): merges the remaining nodes into the
 * dangling-free root, dropping the non-output join variables of each merged pair.
 */
public final class YannakakisPlusEvaluator {

    private YannakakisPlusEvaluator() {}

    /**
     * The reduced tree after the first round: the nodes still standing (same hyperedges,
     * original parent links, root unchanged), their relations keyed by edge id, O, and how
     * many semijoins and absorptions the round performed. {@code JoinTree.Node#vars()} still
     * reports the hyperedge's variables; the projected schema is on the relation.
     */
    public record Reduced(JoinTree tree, Map<Integer, Relation> relations, Set<Var> outputVars,
                          int semijoins, int absorptions) {
        public Reduced {
            relations = Collections.unmodifiableMap(new LinkedHashMap<>(relations));
            outputVars = Collections.unmodifiableSet(new LinkedHashSet<>(outputVars));
        }

        public boolean isSingleNode() { return tree.size() == 1; }

        /** The current relation of a remaining node. */
        public Relation relation(JoinTree.Node n) {
            Relation r = relations.get(n.edge().id());
            if (r == null) throw new IllegalArgumentException(n + " was absorbed in the first round");
            return r;
        }

        /**
         * Theorem 3.11 short-circuit: when a single node remains it carries the whole
         * answer, and π_O of it is the result. Throws if more than one node remains.
         */
        public Relation singleNodeAnswer() {
            if (!isSingleNode()) {
                throw new IllegalStateException("reduced tree has " + tree.size() + " nodes, not 1:\n" + tree.pretty());
            }
            return relation(tree.root()).project(outputVars);
        }

    }

    /** Result of the whole evaluation: π_O with multiplicities, plus what the two rounds did. */
    public record Answer(Relation relation, Reduced firstRound, int merges, int generalMerges) {}

    // ---- invariant checks ---------------------------------------------------

    private static volatile boolean invariantChecks = false;

    /**
     * When on, {@link #secondRound} throws as soon as the general-merge branch is
     * reached for a query that was declared free-connex. Off by default so
     * benchmarks do not pay for it; tests switch it on.
     */
    public static void setInvariantChecks(boolean on) { invariantChecks = on; }
    public static boolean invariantChecks() { return invariantChecks; }

    // ---- round 2 -------------------------------------------------------------

    /**
     * Wang et al., Algorithm 2 (second round) on the reduced tree of the first round.
     *
     * <pre>
     *   while the tree has more than one node:
     *      R_i := a dangling-free node          # the root after round one; a join of a
     *                                           # dangling-free relation with a neighbour
     *                                           # stays dangling-free, so R_i is always the
     *                                           # node the root has grown into
     *      if R_i has a reducible neighbour R_j:
     *          R_i := π_{O ∪ (A_i Δ A_j)}(R_i ⋈ R_j);  merge the two nodes
     *      else:
     *          general merge (see below)
     *   return π_O(surviving relation)
     * </pre>
     *
     * The merge drops D = (A_i ∩ A_j) \ O, the join variables of the pair that are not
     * output. R_j is <em>reducible</em> iff no other neighbour of the merged node still
     * needs a variable of D, i.e. A_k ∩ D = ∅ for every other neighbour R_k of R_i and
     * of R_j (with running intersection this is the same as A_k ∩ A_j ⊆ O for R_i's other
     * neighbours and A_k ∩ A_i ⊆ O for R_j's). For a free-connex query the remaining
     * tree is the connex subtree Tn, whose adjacent nodes share only output variables,
     * so D = ∅ and every neighbour is reducible: the else branch is never taken, and
     * {@link #setInvariantChecks} makes that an exception rather than a silent fallback.
     *
     * <p>The else branch is only reached for general acyclic queries (e.g. a star with a
     * hidden centre and all leaves output). There a semijoin alone cannot make any
     * merge reducible — reducibility is a property of the variable sets, not of the
     * data — so instead of the output-bounded merge we join R_i with a child and keep
     * the dropped variables that some remaining neighbour still needs. That is always
     * correct; the output-sensitivity guarantee of the paper simply does not apply.
     *
     * @param expectReducible whether the caller claims every step is a reducible merge
     *                        (true for free-connex and relation-dominated queries)
     */
    public static Answer secondRound(Reduced reduced, boolean expectReducible) {
        Set<Var> o = reduced.outputVars();
        JoinTree tree = reduced.tree();
        if (tree.root() == null) return new Answer(Relation.unit().project(o), reduced, 0, 0);

        Piece root = pieces(tree.root(), reduced);
        int remaining = tree.size();
        int merges = 0, generalMerges = 0;

        while (remaining > 1) {
            Piece i = root;                                          // dangling-free
            Piece j = null;
            Set<Var> dropped = null;
            for (Piece candidate : i.children) {
                Set<Var> d = dropped(i, candidate, o);
                if (!neededByOthers(i, candidate, d).isEmpty()) continue;
                j = candidate;
                dropped = d;
                break;
            }
            if (j != null) {
                merges++;
            } else {
                if (invariantChecks && expectReducible) {
                    throw new IllegalStateException("no reducible neighbour although the query was declared "
                            + "free-connex; A_i = " + i.rel.schema() + ", O = " + o + ", children = " + i.children);
                }
                j = i.children.get(0);
                dropped = dropped(i, j, o);
                dropped.removeAll(neededByOthers(i, j, dropped));   // keep what a neighbour still needs
                generalMerges++;
            }
            Set<Var> keep = new LinkedHashSet<>(i.rel.schema());   // O ∪ (A_i Δ A_j) = (A_i ∪ A_j) \ D
            keep.addAll(j.rel.schema());
            keep.removeAll(dropped);
            i.rel = i.rel.join(j.rel).project(keep);
            i.children.remove(j);
            i.children.addAll(j.children);
            remaining--;
        }
        return new Answer(root.rel.project(o), reduced, merges, generalMerges);
    }

    /** A node of the shrinking tree of round two: its current relation and its children. */
    private static final class Piece {
        Relation rel;
        final List<Piece> children = new ArrayList<>();
        Piece(Relation rel) { this.rel = rel; }
        @Override public String toString() { return rel.schema().toString(); }
    }

    private static Piece pieces(JoinTree.Node n, Reduced reduced) {
        Piece p = new Piece(reduced.relation(n));
        for (JoinTree.Node c : n.children()) p.children.add(pieces(c, reduced));
        return p;
    }

    /** D = (A_i ∩ A_j) \ O: the variables the merge of i and j projects away. */
    private static Set<Var> dropped(Piece i, Piece j, Set<Var> o) {
        Set<Var> d = new LinkedHashSet<>(i.rel.schema());
        d.retainAll(j.rel.schema());
        d.removeAll(o);
        return d;
    }

    /** The variables of {@code dropped} that some neighbour of i or j other than the pair still uses. */
    private static Set<Var> neededByOthers(Piece i, Piece j, Set<Var> dropped) {
        Set<Var> needed = new LinkedHashSet<>();
        for (Piece k : i.children) {
            if (k == j) continue;
            for (Var v : k.rel.schema()) if (dropped.contains(v)) needed.add(v);
        }
        for (Piece l : j.children) {
            for (Var v : l.rel.schema()) if (dropped.contains(v)) needed.add(v);
        }
        return needed;
    }

    /** Algorithm 1, first round, on {@code tree} as rooted by the caller. */
    public static Reduced firstRound(JoinTree tree, Set<Var> outputVars, Map<Integer, Relation> relations) {
        if (tree.root() == null) {                                  // empty BGP
            return new Reduced(JoinTree.empty(), Map.of(), outputVars, 0, 0);
        }
        Map<Integer, Relation> rel = new LinkedHashMap<>();
        Map<Var, Integer> occurrences = new HashMap<>();
        Map<Integer, Integer> liveChildren = new HashMap<>();
        for (JoinTree.Node n : tree.nodes()) {
            int id = n.edge().id();
            Relation r = relations.get(id);
            if (r == null) throw new IllegalArgumentException("missing relation for edge " + id);
            rel.put(id, r);
            for (Var v : n.vars()) occurrences.merge(v, 1, Integer::sum);
            liveChildren.put(id, n.children().size());
        }
        Set<Var> o = new LinkedHashSet<>(outputVars);
        Set<Integer> removed = new LinkedHashSet<>();
        int semijoins = 0, absorptions = 0;

        List<JoinTree.Node> post = postOrder(tree.root());
        for (JoinTree.Node ri : post) {
            JoinTree.Node rp = ri.parent();
            if (rp == null) break;                                  // R_n, the root, is last
            int i = ri.edge().id(), p = rp.edge().id();
            Relation r = rel.get(i);
            Set<Var> parentVars = rel.get(p).schema();              // A_p: unprojected, its turn comes later

            Set<Var> outputOfRi = new LinkedHashSet<>(r.schema());  // A_i ∩ O
            outputOfRi.retainAll(o);
            if (liveChildren.get(i) == 0 && parentVars.containsAll(outputOfRi)) {
                rel.put(p, rel.get(p).join(r.project(parentVars)));   // Rp := Rp ⋈ π_{A_p}(R_i)
                rel.remove(i);
                removed.add(i);
                liveChildren.merge(p, -1, Integer::sum);
                absorptions++;
            } else {
                Relation projected = r.project(keep(r, o, occurrences));   // R_i := π_{O ∪ A_i^-}(R_i)
                rel.put(i, projected);
                rel.put(p, rel.get(p).semijoin(projected));                // Rp := Rp ⋉ R_i
                semijoins++;
            }
        }
        int rootId = tree.root().edge().id();
        rel.put(rootId, rel.get(rootId).project(keep(rel.get(rootId), o, occurrences)));   // R_n := π_{O ∪ A_n^-}(R_n)

        return new Reduced(reducedTree(tree, removed), rel, o, semijoins, absorptions);
    }

    /**
     * Relation-dominated query (Theorem 3.11): the first round is the whole evaluation.
     * {@code tree} must be rooted at the dominating relation, i.e. O ⊆ A_root; the
     * assertion that one node remains and no semijoin ran is what the theorem claims.
     */
    public static Answer evaluateRelationDominated(JoinTree tree, Set<Var> outputVars, Map<Integer, Relation> relations) {
        if (tree.root() == null || !tree.root().vars().containsAll(outputVars)) {
            throw new IllegalArgumentException("root " + tree.root() + " does not contain O = " + outputVars
                    + "; a relation-dominated tree must be rooted at the dominating relation");
        }
        Reduced reduced = firstRound(tree, outputVars, relations);
        if (!reduced.isSingleNode() || reduced.semijoins() != 0) {
            throw new IllegalStateException("Theorem 3.11 violated: " + reduced.tree().size() + " nodes remain, "
                    + reduced.semijoins() + " semijoins performed:\n" + reduced.tree().pretty());
        }
        return new Answer(reduced.singleNodeAnswer(), reduced, 0, 0);
    }

    /**
     * π_O of the BGP (with multiplicities) for a classified query: the Theorem 3.11
     * short-circuit when the query is relation-dominated, both rounds otherwise. A
     * free-connex query is expected to finish without a general merge.
     */
    public static Answer evaluate(QueryClassifier.Classification cl, Map<Integer, Relation> relations) {
        if (cl.kind() == QueryClassifier.Kind.RELATION_DOMINATED) {
            return evaluateRelationDominated(cl.joinTree(), cl.outputVars(), relations);
        }
        Reduced reduced = firstRound(cl.joinTree(), cl.outputVars(), relations);
        return secondRound(reduced, cl.kind() == QueryClassifier.Kind.FREE_CONNEX);
    }

    // ---- helpers ---------------------------------------------------------

    /** O ∪ A_i^- for {@code r}: its output variables plus its join variables (in ≥ 2 edges). */
    private static Set<Var> keep(Relation r, Set<Var> o, Map<Var, Integer> occurrences) {
        Set<Var> kept = new LinkedHashSet<>();
        for (Var v : r.schema()) {
            if (o.contains(v) || occurrences.getOrDefault(v, 0) >= 2) kept.add(v);
        }
        return kept;
    }

    /**
     * The tree minus the absorbed nodes. An absorbed node was a leaf when removed, so
     * every remaining non-root node still has its original parent: the remaining nodes
     * are a connected subtree containing the root.
     */
    private static JoinTree reducedTree(JoinTree tree, Set<Integer> removed) {
        Map<Integer, JoinTree.Node> nodes = new LinkedHashMap<>();
        for (JoinTree.Node n : tree.nodes()) {
            if (!removed.contains(n.edge().id())) nodes.put(n.edge().id(), new JoinTree.Node(n.edge()));
        }
        for (JoinTree.Node n : tree.nodes()) {
            JoinTree.Node copy = nodes.get(n.edge().id());
            if (copy == null || n.parent() == null) continue;
            JoinTree.Node parent = nodes.get(n.parent().edge().id());
            if (parent == null) throw new IllegalStateException("unreachable: absorbed node " + n.parent() + " kept child " + n);
            copy.setParent(parent);
            parent.children().add(copy);
        }
        return new JoinTree(nodes.get(tree.root().edge().id()), nodes);
    }

    private static List<JoinTree.Node> postOrder(JoinTree.Node root) {
        List<JoinTree.Node> out = new ArrayList<>();
        postOrder(root, out);
        return out;
    }
    private static void postOrder(JoinTree.Node n, List<JoinTree.Node> out) {
        for (JoinTree.Node c : n.children()) postOrder(c, out);
        out.add(n);
    }
}
