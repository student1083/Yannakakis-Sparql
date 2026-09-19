package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query hypergraph of a BGP.
 *
 * <p>Vertices  = query variables (the join points).
 * <p>Hyperedges = triple patterns, each carrying the set of variables it mentions.
 * <p>Constants (IRIs, literals, blank nodes) are NOT vertices — they restrict a
 * single pattern locally and play no role in the join structure.
 *
 * <p>Exposes both incidence directions, because Phase 2 (GYO + join tree) needs
 * "edge -> its variables" and "variable -> edges containing it".
 */
public class QueryHypergraph {

    /**
     * One hyperedge = one triple pattern of the BGP — or, after {@link DimensionFusion},
     * the union of several ({@link #isFused()}), whose relation is the join of its parts.
     */
    public static final class Hyperedge {
        private final int id;          // position of the triple within the BGP; ≥ BGP size for fused edges
        private final Triple triple;   // null for a fused edge
        private final Set<Var> vars;   // the vertices this edge covers

        Hyperedge(int id, Triple triple, Set<Var> vars) {
            this.id = id;
            this.triple = triple;
            this.vars = Collections.unmodifiableSet(vars);
        }

        /** A fused edge over the variables of several triple patterns; it has no triple of its own. */
        static Hyperedge fused(int id, Set<Var> vars) {
            return new Hyperedge(id, null, new LinkedHashSet<>(vars));
        }

        public int id()          { return id; }
        /** The triple pattern; null for a fused edge. */
        public Triple triple()   { return triple; }
        public Set<Var> vars()   { return vars; }
        public boolean isFused() { return triple == null; }

        @Override public String toString() { return (isFused() ? "f" : "e") + id + vars; }
    }

    private final List<Hyperedge> edges;
    private final Map<Var, Set<Integer>> vertexToEdges; // variable -> ids of edges containing it

    private QueryHypergraph(List<Hyperedge> edges, Map<Var, Set<Integer>> vertexToEdges) {
        this.edges = Collections.unmodifiableList(edges);
        this.vertexToEdges = vertexToEdges;
    }

    public List<Hyperedge> edges()             { return edges; }
    public Set<Var> vertices()                 { return vertexToEdges.keySet(); }
    public Set<Integer> edgesContaining(Var v) { return vertexToEdges.getOrDefault(v, Set.of()); }

    /** Build the hypergraph from a BGP's basic pattern. */
    public static QueryHypergraph fromBasicPattern(BasicPattern pattern) {
        List<Hyperedge> edges = new ArrayList<>();
        Map<Var, Set<Integer>> vertexToEdges = new LinkedHashMap<>();

        int id = 0;
        for (Triple t : pattern) {
            Set<Var> vars = new LinkedHashSet<>();
            collectVar(t.getSubject(), vars);
            collectVar(t.getPredicate(), vars);
            collectVar(t.getObject(), vars);

            edges.add(new Hyperedge(id, t, vars));
            for (Var v : vars) {
                vertexToEdges.computeIfAbsent(v, k -> new LinkedHashSet<>()).add(id);
            }
            id++;
        }
        return new QueryHypergraph(edges, vertexToEdges);
    }

    private static void collectVar(Node node, Set<Var> into) {
        if (node.isVariable()) {
            into.add(Var.alloc(node));
        }
    }

    /** A variable is a JOIN variable iff it appears in two or more edges. */
    public Set<Var> joinVariables() {
        Set<Var> result = new LinkedHashSet<>();
        for (Map.Entry<Var, Set<Integer>> e : vertexToEdges.entrySet()) {
            if (e.getValue().size() >= 2) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Hypergraph: ").append(edges.size()).append(" hyperedge(s), ")
                .append(vertices().size()).append(" variable(s)\n");
        sb.append("  Hyperedges (edge -> variables):\n");
        for (Hyperedge e : edges) {
            sb.append("    e").append(e.id()).append(" -> ").append(e.vars()).append("\n");
        }
        sb.append("  Incidence (variable -> edges):\n");
        for (Var v : vertices()) {
            sb.append("    ").append(v).append(" -> ").append(edgesContaining(v)).append("\n");
        }
        sb.append("  Join variables (shared by >= 2 edges): ").append(joinVariables());
        return sb.toString();
    }
}