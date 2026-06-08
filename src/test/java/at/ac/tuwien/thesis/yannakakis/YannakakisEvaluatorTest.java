package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YannakakisEvaluatorTest {

    private static Node node(String token) {
        if (token.startsWith("?")) return Var.alloc(token.substring(1));
        return NodeFactory.createURI("http://example.org/" + token);
    }
    private static Triple t(String s, String p, String o) {
        return Triple.create(node(s), node(p), node(o));
    }

    /** Build hypergraph -> GYO -> evaluate, with relations keyed by triple order. */
    private static Relation eval(List<Triple> triples, List<Relation> rels) {
        BasicPattern bgp = new BasicPattern();
        for (Triple tr : triples) bgp.add(tr);
        JoinTree jt = GyoReduction.decompose(QueryHypergraph.fromBasicPattern(bgp)).orElseThrow();
        Map<Integer, Relation> m = new HashMap<>();
        for (int i = 0; i < rels.size(); i++) m.put(i, rels.get(i));
        return YannakakisEvaluator.evaluate(jt, m);
    }

    /** Oracle: fold the natural join over all relations (order-independent for acyclic). */
    private static Relation naive(List<Relation> rels) {
        Relation acc = rels.get(0);
        for (int i = 1; i < rels.size(); i++) acc = acc.join(rels.get(i));
        return acc;
    }

    @Test
    void pathMatchesNaiveJoin() {
        List<Triple> q = List.of(t("?a", "r", "?b"), t("?b", "r", "?c"), t("?c", "r", "?d"));
        List<Relation> r = List.of(
                Relation.builder("a", "b").row("1", "2").row("9", "8").build(),   // 9-8 will dangle
                Relation.builder("b", "c").row("2", "3").build(),
                Relation.builder("c", "d").row("3", "4").build());
        Relation y = eval(q, r);
        assertEquals(naive(r), y);
        assertEquals(1, y.rowCount());                      // only the 1-2-3-4 chain survives
    }

    @Test
    void starMatchesNaiveJoin() {
        List<Triple> q = List.of(t("?x", "p1", "?a"), t("?x", "p2", "?b"), t("?x", "p3", "?c"));
        List<Relation> r = List.of(
                Relation.builder("x", "a").row("u", "a1").row("v", "a2").build(),
                Relation.builder("x", "b").row("u", "b1").build(),   // only u has a b
                Relation.builder("x", "c").row("u", "c1").row("v", "c2").build());
        Relation y = eval(q, r);
        assertEquals(naive(r), y);
        assertEquals(1, y.rowCount());                      // only x=u joins across all three
    }

    @Test
    void disconnectedComponentsAreCrossProduct() {
        List<Triple> q = List.of(t("?a", "p", "?b"), t("?c", "p", "?d")); // no shared variable
        List<Relation> r = List.of(
                Relation.builder("a", "b").row("1", "2").row("3", "4").build(),
                Relation.builder("c", "d").row("5", "6").row("7", "8").build());
        Relation y = eval(q, r);
        assertEquals(naive(r), y);
        assertEquals(4, y.rowCount());                      // 2 x 2 cross product
    }

    @Test
    void emptyRelationKillsTheJoin() {
        List<Triple> q = List.of(t("?a", "r", "?b"), t("?b", "r", "?c"));
        List<Relation> r = List.of(
                Relation.builder("a", "b").row("1", "2").build(),
                Relation.builder("b", "c").build());          // no rows
        Relation y = eval(q, r);
        assertTrue(y.rows().isEmpty());
    }

    @Test
    void singleTripleReturnsItself() {
        List<Triple> q = List.of(t("?a", "r", "?b"));
        Relation only = Relation.builder("a", "b").row("1", "2").row("3", "4").build();
        assertEquals(only, eval(q, List.of(only)));
    }
}