package at.ac.tuwien.thesis.yannakakis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone Yannakakis evaluation over a {@link JoinTree}. Each tree node
 * carries a relation (the materialized triple pattern), supplied keyed by edge id.
 * Returns the full natural join of all relations, computed with bounded
 * intermediates via two semijoin passes followed by a bottom-up join pass.
 */
public final class YannakakisEvaluator {

    private YannakakisEvaluator() {}

    public static Relation evaluate(JoinTree tree, Map<Integer, Relation> relations) {
        if (tree.root() == null) return Relation.unit();   // empty BGP

        Map<Integer, Relation> rel = new HashMap<>();
        for (JoinTree.Node n : tree.nodes()) {
            int id = n.edge().id();
            Relation r = relations.get(id);
            if (r == null) throw new IllegalArgumentException("missing relation for edge " + id);
            rel.put(id, r);
        }

        List<JoinTree.Node> post = new ArrayList<>();
        List<JoinTree.Node> pre = new ArrayList<>();
        postOrder(tree.root(), post);
        preOrder(tree.root(), pre);

        // Phase 1 — upward semijoin: parent := parent ⋉ child  (children first)
        for (JoinTree.Node v : post) {
            JoinTree.Node p = v.parent();
            if (p != null) {
                int pid = p.edge().id(), vid = v.edge().id();
                rel.put(pid, rel.get(pid).semijoin(rel.get(vid)));
            }
        }
        // Phase 2 — downward semijoin: child := child ⋉ parent  (parents first) -> full reducer
        for (JoinTree.Node v : pre) {
            for (JoinTree.Node c : v.children()) {
                int vid = v.edge().id(), cid = c.edge().id();
                rel.put(cid, rel.get(cid).semijoin(rel.get(vid)));
            }
        }
        // Phase 3 — upward join: parent := parent ⋈ child  -> answer accumulates at the root
        for (JoinTree.Node v : post) {
            JoinTree.Node p = v.parent();
            if (p != null) {
                int pid = p.edge().id(), vid = v.edge().id();
                rel.put(pid, rel.get(pid).join(rel.get(vid)));
            }
        }
        return rel.get(tree.root().edge().id());
    }

    private static void postOrder(JoinTree.Node n, List<JoinTree.Node> out) {
        for (JoinTree.Node c : n.children()) postOrder(c, out);
        out.add(n);
    }
    private static void preOrder(JoinTree.Node n, List<JoinTree.Node> out) {
        out.add(n);
        for (JoinTree.Node c : n.children()) preOrder(c, out);
    }
}