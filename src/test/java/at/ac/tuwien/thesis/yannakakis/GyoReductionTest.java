package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.Test;

import java.util.Optional;

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
}