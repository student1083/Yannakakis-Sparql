package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.core.Var;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies an alpha-acyclic BGP with respect to its output variables O and
 * picks the join-tree root the evaluator should use.
 *
 * <p>The classes are nested: relation-dominated ⊂ free-connex ⊂ acyclic.
 * <ul>
 *   <li><b>Relation-dominated</b> (Wang et al., Definition 3.10): one hyperedge
 *       contains all of O. Root = that hyperedge.</li>
 *   <li><b>Free-connex</b> (Bagan, Durand, Grandjean 2007): the augmented
 *       hypergraph H+ = H ∪ {O} is acyclic too. Root = the root of the
 *       free-connex join tree built by {@link #freeConnexJoinTree}.</li>
 *   <li><b>Acyclic</b> otherwise. Root = whatever GYO happened to leave standing.</li>
 * </ul>
 *
 * <p>A free-connex join tree carries a <em>connex subtree</em> Tn: a connected set
 * of nodes containing the root with O ⊆ vars(Tn) such that every non-root node R
 * in Tn shares with its parent only output variables, vars(R) ∩ vars(parent) ⊆ O.
 * Joining Tn (after full semijoin reduction) and projecting onto O then never
 * materialises an intermediate result larger than the output.
 * {@link #satisfiesConnexProperty} verifies this and is the test oracle, in the
 * same spirit as {@link JoinTree#satisfiesRunningIntersection()}.
 *
 * <p>All methods intersect O with the hypergraph's vertices first: a variable the
 * BGP does not mention cannot be produced by it ({@link AlgebraContextAnalyzer}
 * already guarantees O ⊆ vars(BGP), this only makes the classifier total).
 */
public final class QueryClassifier {

    private QueryClassifier() {}

    public enum Kind { RELATION_DOMINATED, FREE_CONNEX, ACYCLIC }

    /**
     * Result of {@link #classify}: the class, the join tree rooted as chosen for that
     * class, the nodes the evaluator has to fully join, and the effective O.
     *
     * <p>{@code connexSubtree} is Tn for the two free-connex classes (for a
     * relation-dominated query Tn is just the root) and <em>all</em> nodes for a
     * general acyclic query, where nothing can be left to semijoins alone.
     */
    public record Classification(Kind kind, JoinTree joinTree, Set<JoinTree.Node> connexSubtree,
                                 Set<Var> outputVars) {
        public Classification {
            connexSubtree = Collections.unmodifiableSet(new LinkedHashSet<>(connexSubtree));
            outputVars = Collections.unmodifiableSet(new LinkedHashSet<>(outputVars));
        }

        /** The chosen root; null only for an empty BGP (as {@link JoinTree#root()}). */
        public JoinTree.Node root() { return joinTree.root(); }

        /** {@link QueryClassifier#satisfiesConnexProperty} on this tree, Tn and O. */
        public boolean satisfiesConnexProperty() {
            return QueryClassifier.satisfiesConnexProperty(joinTree, connexSubtree, outputVars);
        }
    }

    // ---- classification --------------------------------------------------

    /**
     * Classify {@code h} with respect to {@code outputVars}, most specific class first.
     *
     * @return empty iff {@code h} is cyclic (no join tree exists at all)
     */
    public static Optional<Classification> classify(QueryHypergraph h, Set<Var> outputVars) {
        Set<Var> o = restrict(h, outputVars);
        if (h.edges().isEmpty()) {                          // empty BGP: nothing to join, nothing to output
            return Optional.of(new Classification(Kind.FREE_CONNEX, JoinTree.empty(), Set.of(), o));
        }
        Optional<GyoReduction.Decomposition> gyo = GyoReduction.reduce(GyoReduction.edgeVarsOf(h));
        if (gyo.isEmpty()) return Optional.empty();         // cyclic

        Optional<QueryHypergraph.Hyperedge> dominating = relationDominated(h, o);
        if (dominating.isPresent()) {
            int rootId = dominating.get().id();
            JoinTree tree = JoinTree.build(h, rootId, reroot(gyo.get().parentOf(), rootId));
            return Optional.of(new Classification(Kind.RELATION_DOMINATED, tree, Set.of(tree.root()), o));
        }

        Optional<Classification> connex = freeConnexJoinTree(h, o);
        if (connex.isPresent()) return connex;

        JoinTree tree = JoinTree.build(h, gyo.get().rootId(), gyo.get().parentOf());
        return Optional.of(new Classification(Kind.ACYCLIC, tree, new LinkedHashSet<>(tree.nodes()), o));
    }

    /**
     * Wang et al., Definition 3.10: the query is relation-dominated if a single
     * hyperedge contains all of O.
     *
     * <p>In RDF a hyperedge is a triple pattern and has at most three variables, so
     * this can only succeed when |O| ≤ 3 (and |O| = 3 needs a pattern with three
     * distinct variables, {@code ?s ?p ?o}).
     *
     * @return the first dominating hyperedge in BGP order, empty if there is none
     */
    public static Optional<QueryHypergraph.Hyperedge> relationDominated(QueryHypergraph h, Set<Var> outputVars) {
        Set<Var> o = restrict(h, outputVars);
        for (QueryHypergraph.Hyperedge e : h.edges()) {
            if (e.vars().containsAll(o)) return Optional.of(e);
        }
        return Optional.empty();
    }

    /**
     * Bagan, Durand, Grandjean: Q is free-connex iff both H and the augmented
     * hypergraph H+ = H ∪ {O} are alpha-acyclic. Both tests run the existing GYO.
     */
    public static boolean freeConnex(QueryHypergraph h, Set<Var> outputVars) {
        if (h.edges().isEmpty()) return true;
        return GyoReduction.reduce(GyoReduction.edgeVarsOf(h)).isPresent()
                && GyoReduction.reduce(augmented(h, restrict(h, outputVars))).isPresent();
    }

    /**
     * The free-connex join tree of {@code h}, or empty if the query is not free-connex.
     *
     * <p>Construction: run GYO on H+, root the resulting tree at the added node [O]
     * and delete it. Its children C1..Ck become Tn. Every variable two different
     * subtrees under [O] share lies on the path through [O] and is therefore in O
     * (running intersection), and every output variable that occurs in a subtree
     * occurs in that subtree's top node Ci for the same reason. Hence the
     * O-projections Ei = vars(Ci) ∩ O together carry all of O and — as the vertex
     * restriction of the acyclic H to O, minus edges that are subsets of some Ei —
     * form an acyclic hypergraph. A second GYO on {E1..Ek} yields the shape of Tn;
     * each Ci keeps the subtree it had under [O]. Adjacent Tn nodes intersect only
     * within O by the first observation, and running intersection holds for the
     * whole tree: an output variable's nodes are connected through Tn, any other
     * variable lives inside a single unchanged subtree.
     *
     * <p>The returned classification always has kind {@link Kind#FREE_CONNEX}; it does
     * not check whether the query is also relation-dominated — {@link #classify} does.
     */
    public static Optional<Classification> freeConnexJoinTree(QueryHypergraph h, Set<Var> outputVars) {
        Set<Var> o = restrict(h, outputVars);
        if (h.edges().isEmpty()) {
            return Optional.of(new Classification(Kind.FREE_CONNEX, JoinTree.empty(), Set.of(), o));
        }
        // H itself must be acyclic as well; H+ acyclic alone is not enough in general.
        if (GyoReduction.reduce(GyoReduction.edgeVarsOf(h)).isEmpty()) return Optional.empty();

        int oId = h.edges().size();                         // ids are 0..n-1, so n is free
        Map<Integer, Set<Var>> plus = augmented(h, o);
        Optional<GyoReduction.Decomposition> gyoPlus = GyoReduction.reduce(plus);
        if (gyoPlus.isEmpty()) return Optional.empty();     // H+ cyclic -> not free-connex

        // Root T+ at [O]; its children are the tops of the subtrees, i.e. Tn.
        Map<Integer, Integer> parentAtO = reroot(gyoPlus.get().parentOf(), oId);
        Map<Integer, Set<Var>> connexEdges = new LinkedHashMap<>();  // Ci -> Ei = vars(Ci) ∩ O
        for (QueryHypergraph.Hyperedge e : h.edges()) {
            if (Integer.valueOf(oId).equals(parentAtO.get(e.id()))) {
                Set<Var> ei = new LinkedHashSet<>(e.vars());
                ei.retainAll(o);
                connexEdges.put(e.id(), ei);
            }
        }
        // [O] is the root of T+, so it has at least one child (h is non-empty).
        GyoReduction.Decomposition connex = GyoReduction.reduce(connexEdges)
                .orElseThrow(() -> new IllegalStateException(
                        "unreachable: the O-projections of the children of [O] are the vertex "
                                + "restriction of the acyclic H to O minus subset edges, hence acyclic"));

        // Final tree: Tn shaped by the second GYO, every other node keeps its parent.
        Map<Integer, Integer> parentOf = new HashMap<>();
        for (Map.Entry<Integer, Integer> pc : parentAtO.entrySet()) {
            if (pc.getValue() != oId) parentOf.put(pc.getKey(), pc.getValue());
        }
        parentOf.putAll(connex.parentOf());
        JoinTree tree = JoinTree.build(h, connex.rootId(), parentOf);

        Set<JoinTree.Node> tn = new LinkedHashSet<>();
        for (Integer id : connexEdges.keySet()) tn.add(tree.node(id));
        return Optional.of(new Classification(Kind.FREE_CONNEX, tree, tn, o));
    }

    /**
     * Verifies the connex-subtree property: {@code tn} contains the root and is
     * connected, O ⊆ vars(tn), and every non-root R in {@code tn} satisfies
     * vars(R) ∩ vars(parent(R)) ⊆ O. Does not re-check running intersection; use
     * {@link JoinTree#satisfiesRunningIntersection()} for that.
     */
    public static boolean satisfiesConnexProperty(JoinTree tree, Set<JoinTree.Node> tn, Set<Var> outputVars) {
        if (tree.root() == null) return tn.isEmpty() && outputVars.isEmpty();   // empty BGP
        if (!tn.contains(tree.root())) return false;

        Set<Var> covered = new LinkedHashSet<>();
        for (JoinTree.Node r : tn) {
            covered.addAll(r.vars());
            if (r == tree.root()) continue;
            JoinTree.Node rp = r.parent();
            if (rp == null || !tn.contains(rp)) return false;          // Tn not connected to the root
            for (Var v : r.vars()) {
                if (rp.vars().contains(v) && !outputVars.contains(v)) return false;   // non-output join variable inside Tn
            }
        }
        return covered.containsAll(outputVars);
    }

    // ---- helpers ---------------------------------------------------------

    /** O ∩ vertices(h), in O's order. */
    private static Set<Var> restrict(QueryHypergraph h, Set<Var> outputVars) {
        Set<Var> o = new LinkedHashSet<>();
        for (Var v : outputVars) if (h.vertices().contains(v)) o.add(v);
        return o;
    }

    /** H+ = H ∪ {[O]}, with [O] under the id {@code h.edges().size()}. */
    private static Map<Integer, Set<Var>> augmented(QueryHypergraph h, Set<Var> o) {
        Map<Integer, Set<Var>> plus = GyoReduction.edgeVarsOf(h);
        plus.put(h.edges().size(), new LinkedHashSet<>(o));
        return plus;
    }

    /**
     * Re-roots a tree given as child → parent map at {@code newRoot}. The join-tree
     * property is undirected, so the result is a join tree of the same hypergraph.
     * Nodes are the keys and values of {@code parentOf} plus {@code newRoot}.
     */
    private static Map<Integer, Integer> reroot(Map<Integer, Integer> parentOf, int newRoot) {
        Map<Integer, List<Integer>> adjacent = new HashMap<>();
        for (Map.Entry<Integer, Integer> pc : parentOf.entrySet()) {
            adjacent.computeIfAbsent(pc.getKey(), k -> new ArrayList<>()).add(pc.getValue());
            adjacent.computeIfAbsent(pc.getValue(), k -> new ArrayList<>()).add(pc.getKey());
        }
        Map<Integer, Integer> rerooted = new HashMap<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        seen.add(newRoot);
        queue.add(newRoot);
        while (!queue.isEmpty()) {
            int n = queue.remove();
            for (Integer m : adjacent.getOrDefault(n, List.of())) {
                if (seen.add(m)) {
                    rerooted.put(m, n);
                    queue.add(m);
                }
            }
        }
        return rerooted;
    }
}
