package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GyoReductionTest {

    private static Node node(String token) {
        if (token.startsWith("?")) return Var.alloc(token.substring(1));
        return NodeFactory.createURI("http://example.org/" + token);
    }
    private static Triple t(String s, String p, String o) {
        return Triple.create(node(s), node(p), node(o));
    }
    private static QueryHypergraph hg(Triple... triples) {
        BasicPattern bgp = new BasicPattern();
        for (Triple tr : triples) bgp.add(tr);
        return QueryHypergraph.fromBasicPattern(bgp);
    }

    @Test
    void pathIsAcyclic() {
        QueryHypergraph h = hg(t("?a", "r", "?b"), t("?b", "r", "?c"), t("?c", "r", "?d"));
        Optional<JoinTree> jt = GyoReduction.decompose(h);
        assertTrue(jt.isPresent(), "a path BGP must be acyclic");
        assertEquals(3, jt.get().size());
        assertTrue(jt.get().satisfiesRunningIntersection());
    }

    @Test
    void starIsAcyclic() {
        QueryHypergraph h = hg(t("?x", "p1", "?a"), t("?x", "p2", "?b"), t("?x", "p3", "?c"));
        Optional<JoinTree> jt = GyoReduction.decompose(h);
        assertTrue(jt.isPresent(), "a star BGP must be acyclic");
        assertEquals(3, jt.get().size());
        assertTrue(jt.get().satisfiesRunningIntersection());
    }

    @Test
    void triangleIsCyclic() {
        QueryHypergraph h = hg(t("?a", "r", "?b"), t("?b", "r", "?c"), t("?c", "r", "?a"));
        assertFalse(GyoReduction.isAcyclic(h), "a triangle BGP must be cyclic");
        assertTrue(GyoReduction.decompose(h).isEmpty());
    }

    @Test
    void fourCycleIsCyclic() {
        QueryHypergraph h = hg(
                t("?a", "r", "?b"), t("?b", "r", "?c"),
                t("?c", "r", "?d"), t("?d", "r", "?a"));
        assertFalse(GyoReduction.isAcyclic(h), "a 4-cycle BGP must be cyclic");
    }

    @Test
    void singleTripleIsAcyclic() {
        QueryHypergraph h = hg(t("?a", "r", "?b"));
        Optional<JoinTree> jt = GyoReduction.decompose(h);
        assertTrue(jt.isPresent());
        assertEquals(1, jt.get().size());
        assertNull(jt.get().root().parent(), "the single edge is the root");
        assertTrue(jt.get().satisfiesRunningIntersection());
    }

    @Test
    void disconnectedComponentsAreAcyclic() {
        QueryHypergraph h = hg(t("?a", "p", "?b"), t("?c", "q", "?d")); // share no variable
        Optional<JoinTree> jt = GyoReduction.decompose(h);
        assertTrue(jt.isPresent(), "independent components form a join forest, still acyclic");
        assertEquals(2, jt.get().size());
        assertTrue(jt.get().satisfiesRunningIntersection());
    }

    /**
     * Lemma: substituting bound variables by constants only deletes vertices
     * from the query hypergraph and never adds any, so an existing join tree's
     * running-intersection property survives for every remaining vertex.
     * Hence binding any subset of variables of an alpha-acyclic BGP can never
     * make it cyclic. Exhaustively checks this over path/star/snowflake shapes,
     * randomly generated acyclic shapes, and degenerate edge cases.
     */
    @Test
    void acyclicityPreservedUnderBinding() {
        List<List<Triple>> shapes = new ArrayList<>();

        shapes.add(List.of(t("?a", "p", "?b"), t("?b", "p", "?c"), t("?c", "p", "?d")));   // path
        shapes.add(List.of(t("?x", "p1", "?a"), t("?x", "p2", "?b"), t("?x", "p3", "?c"))); // star
        shapes.add(List.of(                                                                // snowflake
                t("?x", "p1", "?a"), t("?a", "q1", "?a1"),
                t("?x", "p2", "?b"), t("?b", "q2", "?b1"),
                t("?x", "p3", "?c"), t("?c", "q3", "?c1")));

        long seed = 20260913L;
        for (int i = 0; i < 20; i++) {
            shapes.add(randomAcyclicShape(new Random(seed + i)));
        }

        shapes.add(List.of(t("s", "p", "o")));                                     // fully ground triple
        shapes.add(List.of(t("?a", "p", "?b"), t("?a", "p", "?b")));               // two identical triples
        shapes.add(List.of(t("?x", "p", "?x")));                                   // self-loop alone
        shapes.add(List.of(t("?x", "p", "?x"), t("?x", "q", "?y")));               // self-loop + attached edge

        for (List<Triple> shape : shapes) {
            assertTrue(GyoReduction.isAcyclic(hg(shape.toArray(new Triple[0]))),
                    "shape itself must be acyclic: " + shape);

            List<Var> vars = new ArrayList<>(varsOf(shape));
            int n = vars.size();
            for (int mask = 1; mask < (1 << n); mask++) {
                Set<Var> toBind = new LinkedHashSet<>();
                for (int b = 0; b < n; b++) {
                    if ((mask & (1 << b)) != 0) toBind.add(vars.get(b));
                }

                List<Triple> bound = bindVars(shape, toBind);
                QueryHypergraph h = hg(bound.toArray(new Triple[0]));
                assertTrue(GyoReduction.isAcyclic(h),
                        "binding " + toBind + " broke acyclicity: " + shape + " -> " + bound);
            }
        }
    }

    /**
     * Grows a Berge-acyclic BGP of 2..6 triples: each new triple attaches an
     * already-used variable (the anchor) to either a fresh variable or a
     * constant, so the join structure is always a tree by construction.
     */
    private static List<Triple> randomAcyclicShape(Random rnd) {
        List<Var> vars = new ArrayList<>();
        vars.add(Var.alloc("v0"));
        vars.add(Var.alloc("v1"));
        List<Triple> triples = new ArrayList<>();
        triples.add(Triple.create(vars.get(0), pred(rnd), vars.get(1)));

        int size = 2 + rnd.nextInt(5);          // 2..6 triples total
        int nextVar = 2;
        for (int i = 1; i < size; i++) {
            Var anchor = vars.get(rnd.nextInt(vars.size()));
            Node other;
            if (rnd.nextInt(4) == 0) {
                other = NodeFactory.createURI("http://example.org/c" + rnd.nextInt(8));
            } else {
                Var v = Var.alloc("v" + nextVar++);
                vars.add(v);
                other = v;
            }
            Node p = pred(rnd);
            triples.add(rnd.nextBoolean()
                    ? Triple.create(anchor, p, other)
                    : Triple.create(other, p, anchor));
        }
        return triples;
    }

    private static Node pred(Random rnd) {
        return NodeFactory.createURI("http://example.org/p" + rnd.nextInt(3));
    }

    private static Set<Var> varsOf(List<Triple> shape) {
        Set<Var> vars = new LinkedHashSet<>();
        for (Triple tr : shape) {
            addIfVar(vars, tr.getSubject());
            addIfVar(vars, tr.getPredicate());
            addIfVar(vars, tr.getObject());
        }
        return vars;
    }

    private static void addIfVar(Set<Var> vars, Node n) {
        if (n.isVariable()) vars.add(Var.alloc(n));
    }

    private static List<Triple> bindVars(List<Triple> shape, Set<Var> toBind) {
        List<Triple> out = new ArrayList<>(shape.size());
        for (Triple tr : shape) {
            out.add(Triple.create(
                    bindNode(tr.getSubject(), toBind),
                    bindNode(tr.getPredicate(), toBind),
                    bindNode(tr.getObject(), toBind)));
        }
        return out;
    }

    private static Node bindNode(Node n, Set<Var> toBind) {
        if (n.isVariable() && toBind.contains(Var.alloc(n))) {
            return NodeFactory.createURI("http://example.org/bound_" + n.getName());
        }
        return n;
    }
}