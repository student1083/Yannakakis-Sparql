package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A join tree of an acyclic BGP: a rooted tree whose nodes are the hyperedges
 * (triple patterns). Produced by {@link GyoReduction}.
 *
 * <p>Guarantees the connectedness condition (running intersection property):
 * for every variable, the nodes whose pattern mentions it form a connected
 * subtree. {@link #satisfiesRunningIntersection()} verifies this and is used
 * in tests as a correctness oracle.
 */
public final class JoinTree {

    public static final class Node {
        private final QueryHypergraph.Hyperedge edge;
        private Node parent;
        private final List<Node> children = new ArrayList<>();

        Node(QueryHypergraph.Hyperedge edge) { this.edge = edge; }

        public QueryHypergraph.Hyperedge edge() { return edge; }
        public Node parent()                    { return parent; }
        public List<Node> children()            { return children; }
        public Set<Var> vars()                  { return edge.vars(); }

        void setParent(Node p) { this.parent = p; }   // package-private, set by GyoReduction

        @Override public String toString() { return "node(e" + edge.id() + ")"; }
    }

    private final Node root;                       // null only for an empty BGP
    private final Map<Integer, Node> byEdgeId;

    JoinTree(Node root, Map<Integer, Node> byEdgeId) {
        this.root = root;
        this.byEdgeId = byEdgeId;
    }

    public Node root()              { return root; }
    public Collection<Node> nodes() { return byEdgeId.values(); }
    public int size()               { return byEdgeId.size(); }

    /** Verifies the connectedness condition for every variable. */
    public boolean satisfiesRunningIntersection() {
        Set<Var> allVars = new LinkedHashSet<>();
        for (Node n : nodes()) allVars.addAll(n.vars());

        for (Var v : allVars) {
            List<Node> s = new ArrayList<>();
            for (Node n : nodes()) {
                if (n.vars().contains(v)) s.add(n);
            }
            if (s.isEmpty()) continue;
            Set<Node> sSet = new HashSet<>(s);
            int entryPoints = 0;                   // nodes in s whose parent is outside s
            for (Node n : s) {
                if (n.parent() == null || !sSet.contains(n.parent())) entryPoints++;
            }
            if (entryPoints != 1) return false;    // disconnected -> condition violated
        }
        return true;
    }

    /** Indented print of the tree, for demos and debugging. */
    public String pretty() {
        if (root == null) return "(empty)\n";
        StringBuilder sb = new StringBuilder();
        printNode(root, 0, sb);
        return sb.toString();
    }

    private void printNode(Node n, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth))
                .append("e").append(n.edge().id()).append(' ').append(n.vars()).append('\n');
        for (Node c : n.children()) printNode(c, depth + 1, sb);
    }
}