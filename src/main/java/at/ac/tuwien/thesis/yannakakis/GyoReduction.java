package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
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
 */
public final class GyoReduction {

    private GyoReduction() {}

    private static final AtomicInteger DECOMPOSE_CALLS = new AtomicInteger();

    /** Test-only instrumentation: number of times {@link #decompose} has run. */
    public static int decomposeCallCount() { return DECOMPOSE_CALLS.get(); }

    public static void resetDecomposeCallCount() { DECOMPOSE_CALLS.set(0); }

    /** @return the join tree if alpha-acyclic, empty if cyclic. */
    public static Optional<JoinTree> decompose(QueryHypergraph h) {
        DECOMPOSE_CALLS.incrementAndGet();
        Map<Integer, Set<Var>> edgeVars = new LinkedHashMap<>();
        Map<Integer, QueryHypergraph.Hyperedge> edgeById = new LinkedHashMap<>();
        for (QueryHypergraph.Hyperedge e : h.edges()) {
            edgeVars.put(e.id(), new LinkedHashSet<>(e.vars()));
            edgeById.put(e.id(), e);
        }

        if (edgeVars.isEmpty()) {                       // empty BGP
            return Optional.of(new JoinTree(null, new LinkedHashMap<>()));
        }

        Map<Integer, Integer> parentOf = new HashMap<>();   // child id -> parent id
        List<Integer> remaining = new ArrayList<>(edgeVars.keySet());

        while (remaining.size() > 1) {
            EarPick pick = findEar(remaining, edgeVars);
            if (pick == null) return Optional.empty();      // edges remain, no ear -> cyclic
            parentOf.put(pick.ear(), pick.witness());
            remaining.remove((Integer) pick.ear());
            edgeVars.remove(pick.ear());
        }

        int rootId = remaining.get(0);                      // last edge standing = root
        Map<Integer, JoinTree.Node> nodes = new LinkedHashMap<>();
        for (Integer id : edgeById.keySet()) {
            nodes.put(id, new JoinTree.Node(edgeById.get(id)));
        }
        for (Map.Entry<Integer, Integer> pc : parentOf.entrySet()) {
            JoinTree.Node child  = nodes.get(pc.getKey());
            JoinTree.Node parent = nodes.get(pc.getValue());
            child.setParent(parent);
            parent.children().add(child);
        }
        return Optional.of(new JoinTree(nodes.get(rootId), nodes));
    }

    /** Convenience: acyclicity test only. */
    public static boolean isAcyclic(QueryHypergraph h) {
        return decompose(h).isPresent();
    }

    private record EarPick(int ear, int witness) {}

    private static EarPick findEar(List<Integer> remaining, Map<Integer, Set<Var>> edgeVars) {
        // how many remaining edges contain each variable
        Map<Var, Integer> count = new HashMap<>();
        for (Integer id : remaining) {
            for (Var v : edgeVars.get(id)) count.merge(v, 1, Integer::sum);
        }

        for (Integer eId : remaining) {
            Set<Var> shared = new LinkedHashSet<>();
            for (Var v : edgeVars.get(eId)) {
                if (count.get(v) >= 2) shared.add(v);       // shared with some other edge
            }

            if (shared.isEmpty()) {                         // isolated component
                for (Integer wId : remaining) {
                    if (!wId.equals(eId)) return new EarPick(eId, wId);
                }
            } else {                                        // need a single witness edge
                for (Integer wId : remaining) {
                    if (wId.equals(eId)) continue;
                    if (edgeVars.get(wId).containsAll(shared)) return new EarPick(eId, wId);
                }
            }
        }
        return null;                                        // no ear
    }
}