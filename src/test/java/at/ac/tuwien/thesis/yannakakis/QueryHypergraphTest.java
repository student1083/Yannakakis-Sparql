package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryHypergraphTest {

    private static Node node(String token) {
        if (token.startsWith("?")) return Var.alloc(token.substring(1));
        return NodeFactory.createURI("http://example.org/" + token);
    }

    private static Triple t(String s, String p, String o) {
        return Triple.create(node(s), node(p), node(o));
    }

    @Test
    void buildsEdgesVerticesAndJoinVars() {
        BasicPattern bgp = new BasicPattern();
        bgp.add(t("?person", "name", "?name"));
        bgp.add(t("?person", "livesIn", "?city"));
        bgp.add(t("?city", "country", "?country"));

        QueryHypergraph h = QueryHypergraph.fromBasicPattern(bgp);

        assertEquals(3, h.edges().size());
        assertEquals(4, h.vertices().size()); // person, name, city, country
        assertEquals(Set.of(Var.alloc("person"), Var.alloc("city")), h.joinVariables());
    }

    @Test
    void constantsAreNotVertices() {
        BasicPattern bgp = new BasicPattern();
        bgp.add(t("?x", "type", "Person")); // only ?x is a variable
        QueryHypergraph h = QueryHypergraph.fromBasicPattern(bgp);

        assertEquals(1, h.vertices().size());
        assertTrue(h.joinVariables().isEmpty());
    }
}