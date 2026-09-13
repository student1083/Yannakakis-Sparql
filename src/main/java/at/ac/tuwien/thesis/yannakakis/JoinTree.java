package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

        void setParent(Node p) { this.parent = p; }   // package-private, set by JoinTree.build

        @Override public String toString() { return "node(e" + edge.id() + ")"; }
    }

    private final Node root;                       // null only for an empty BGP
    private final Map<Integer, Node> byEdgeId;

    JoinTree(Node root, Map<Integer, Node> byEdgeId) {
        this.root = root;
        this.byEdgeId = byEdgeId;
    }

    /** Tree of an empty BGP: no root, no nodes. */
    static JoinTree empty() {
        return new JoinTree(null, new LinkedHashMap<>());
    }

    /**
     * Builds the tree over all hyperedges of {@code h} from a child-id → parent-id
     * map; {@code rootId} is the one edge without a parent. Shared by
     * {@link GyoReduction} (arbitrary GYO root) and {@link QueryClassifier}
     * (re-rooted / free-connex trees).
     */
    static JoinTree build(QueryHypergraph h, int rootId, Map<Integer, Integer> parentOf) {
        Map<Integer, Node> nodes = new LinkedHashMap<>();
        for (QueryHypergraph.Hyperedge e : h.edges()) {
            nodes.put(e.id(), new Node(e));
        }
        for (Map.Entry<Integer, Integer> pc : parentOf.entrySet()) {
            Node child  = nodes.get(pc.getKey());
            Node parent = nodes.get(pc.getValue());
            child.setParent(parent);
            parent.children().add(child);
        }
        return new JoinTree(nodes.get(rootId), nodes);
    }

    public Node root()              { return root; }
    public Collection<Node> nodes() { return byEdgeId.values(); }
    public int size()               { return byEdgeId.size(); }
    /** The node of the hyperedge with this id (the triple's position in the BGP), or null. */
    public Node node(int edgeId)    { return byEdgeId.get(edgeId); }

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