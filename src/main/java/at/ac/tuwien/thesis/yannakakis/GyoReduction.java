package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GYO (Graham–Yu–Özsoyoğlu) reduction.
 *
 * <p>An ear is a hyperedge e whose variables shared with the rest of the
 * hypergraph are all contained in a single other edge f (its witness).
 * Variables exclusive to e are dropped implicitly. Remove ears until one edge
 * remains (acyclic; witnesses form a join tree) or no ear exists (cyclic).
 *
 * <p>An edge with no shared variables is an isolated component; it attaches to
 * an arbitrary remaining edge as a cross-product join, so acyclic BGPs with
 * independent components still produce a single join tree.
 *
 * <p>Ear removal is confluent: if the hypergraph is acyclic, every sequence of
 * valid ear removals reaches a single edge, and every witness choice yields a join
 * tree. The order is therefore a free plan-shape parameter. {@link #reduce(Map, Map)}
 * takes cardinality estimates and uses them for the second heuristic of Wang et al.
 * §5.2 ("larger relations at the top of the tree"): ears are tried smallest first, so
 * small relations become children early and the largest edge is what remains standing
 * (the root), and among several valid witnesses the largest is preferred, so large
 * relations become parents. Without estimates the BGP order is used as before.
 */
public final class GyoReduction {

    private GyoReduction() {}

    private static final AtomicInteger GYO_RUNS = new AtomicInteger();

    /**
     * Test-only instrumentation: number of ear-removal runs ({@link #reduce}), which
     * every entry point — {@link #decompose}, {@link #isAcyclic}, and
     * {@link QueryClassifier#classify} — goes through.
     */
    public static int gyoRunCount() { return GYO_RUNS.get(); }

    public static void resetGyoRunCount() { GYO_RUNS.set(0); }

    /** @return the join tree if alpha-acyclic, empty if cyclic. */
    public static Optional<JoinTree> decompose(QueryHypergraph h) {
        if (h.edges().isEmpty()) {                          // empty BGP
            return Optional.of(JoinTree.empty());
        }
        return reduce(edgeVarsOf(h)).map(d -> JoinTree.build(h, d.rootId(), d.parentOf()));
    }

    /**
     * Outcome of ear removal over abstract edges: the last edge standing and, for
     * every other edge, the witness it was removed into (child id → parent id).
     */
    record Decomposition(int rootId, Map<Integer, Integer> parentOf) {}

    /** The hypergraph as abstract edges: edge id → its variables (copied, so callers may add edges). */
    static Map<Integer, Set<Var>> edgeVarsOf(QueryHypergraph h) {
        Map<Integer, Set<Var>> edgeVars = new LinkedHashMap<>();
        for (QueryHypergraph.Hyperedge e : h.edges()) {
            edgeVars.put(e.id(), new LinkedHashSet<>(e.vars()));
        }
        return edgeVars;
    }

    /**
     * Ear removal over abstract edges (id → variable set). Package-private so that
     * {@link QueryClassifier} can run the very same GYO on the augmented hypergraph
     * H+ = H ∪ {O} and on the connex projection without wrapping synthetic edges in
     * {@link QueryHypergraph.Hyperedge}s. The input map is not modified.
     *
     * @param edges at least one edge
     * @return the decomposition if alpha-acyclic, empty if cyclic
     */
    static Optional<Decomposition> reduce(Map<Integer, Set<Var>> edges) {
        return reduce(edges, Map.of());
    }

    /**
     * {@link #reduce(Map)} with cardinality estimates (edge id → estimated size) steering
     * the ear order: candidate ears are tried in ascending, candidate witnesses in
     * descending estimate order. Edges without an estimate (e.g. the synthetic [O] edge
     * of {@link QueryClassifier}) sort last in both orders — where map order put them
     * before — and with an empty map the result is identical to the unweighted call.
     * Sorting is stable, so ties keep BGP order.
     *
     * @param edges at least one edge
     * @param estimates estimated cardinalities, may be empty or partial
     * @return the decomposition if alpha-acyclic, empty if cyclic
     */
    static Optional<Decomposition> reduce(Map<Integer, Set<Var>> edges, Map<Integer, Long> estimates) {
        if (edges.isEmpty()) throw new IllegalArgumentException("reduce needs at least one edge");
        GYO_RUNS.incrementAndGet();
        Map<Integer, Set<Var>> edgeVars = new LinkedHashMap<>(edges);
        Map<Integer, Integer> parentOf = new HashMap<>();   // child id -> parent id

        List<Integer> earOrder = new ArrayList<>(edgeVars.keySet());
        List<Integer> witnessOrder = new ArrayList<>(edgeVars.keySet());
        if (!estimates.isEmpty()) {
            // unweighted edges sort last in both orders (largest as an ear, smallest as a witness)
            earOrder.sort(Comparator.comparingLong(id -> estimates.getOrDefault(id, Long.MAX_VALUE)));
            witnessOrder.sort(Comparator.comparingLong(id -> -estimates.getOrDefault(id, Long.MIN_VALUE + 1)));
        }

        while (earOrder.size() > 1) {
            EarPick pick = findEar(earOrder, witnessOrder, edgeVars);
            if (pick == null) return Optional.empty();      // edges remain, no ear -> cyclic
            parentOf.put(pick.ear(), pick.witness());
            earOrder.remove((Integer) pick.ear());
            witnessOrder.remove((Integer) pick.ear());
            edgeVars.remove(pick.ear());
        }

        int rootId = earOrder.get(0);                       // last edge standing = root
        return Optional.of(new Decomposition(rootId, parentOf));
    }

    /** Convenience: acyclicity test only. */
    public static boolean isAcyclic(QueryHypergraph h) {
        return decompose(h).isPresent();
    }

    private record EarPick(int ear, int witness) {}

    /**
     * The first ear in {@code earOrder} together with its first valid witness in
     * {@code witnessOrder}; both lists hold the same remaining edges.
     */
    private static EarPick findEar(List<Integer> earOrder, List<Integer> witnessOrder, Map<Integer, Set<Var>> edgeVars) {
        // how many remaining edges contain each variable
        Map<Var, Integer> count = new HashMap<>();
        for (Integer id : earOrder) {
            for (Var v : edgeVars.get(id)) count.merge(v, 1, Integer::sum);
        }

        for (Integer eId : earOrder) {
            Set<Var> shared = new LinkedHashSet<>();
            for (Var v : edgeVars.get(eId)) {
                if (count.get(v) >= 2) shared.add(v);       // shared with some other edge
            }

            if (shared.isEmpty()) {                         // isolated component
                for (Integer wId : witnessOrder) {
                    if (!wId.equals(eId)) return new EarPick(eId, wId);
                }
            } else {                                        // need a single witness edge
                for (Integer wId : witnessOrder) {
                    if (wId.equals(eId)) continue;
                    if (edgeVars.get(wId).containsAll(shared)) return new EarPick(eId, wId);
                }
            }
        }
        return null;                                        // no ear
    }
}