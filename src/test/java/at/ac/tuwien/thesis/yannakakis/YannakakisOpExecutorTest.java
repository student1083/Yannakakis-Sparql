package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YannakakisOpExecutorTest {

    private static final String NS = "http://example.org/";

    private static Model peopleModel() {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(NS + "a"), m.createProperty(NS + "knows"),   m.createResource(NS + "b"));
        m.add(m.createResource(NS + "b"), m.createProperty(NS + "knows"),   m.createResource(NS + "c"));
        m.add(m.createResource(NS + "b"), m.createProperty(NS + "livesIn"), m.createResource(NS + "vienna"));
        m.add(m.createResource(NS + "vienna"), m.createProperty(NS + "country"), m.createResource(NS + "at"));
        return m;
    }

    private static List<String> run(Model model, String query) {
        List<String> rows = new ArrayList<>();
        Query q = QueryFactory.create(query);
        try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                // kanonische Zeile: Variablen alphabetisch sortiert, damit die
                // Reihenfolge der Ausgabe keine Rolle spielt
                List<String> cells = new ArrayList<>();
                List<String> vars = new ArrayList<>();
                s.varNames().forEachRemaining(vars::add);
                Collections.sort(vars);
                for (String v : vars) {
                    cells.add(v + "=" + s.get(v));
                }
                rows.add(String.join(" | ", cells));
            }
        }
        Collections.sort(rows);
        return rows;
    }

    private static List<String> runWithYannakakis(Model model, String query) {
        YannakakisOpExecutor.register();
        try {
            return run(model, query);
        } finally {
            YannakakisOpExecutor.unregister();
        }
    }

    @Test
    void acyclicResultMatchesStockArq() {
        Model m = peopleModel();
        String q = """
                PREFIX ex: <http://example.org/>
                SELECT * WHERE {
                  ?p1 ex:knows ?p2 . ?p2 ex:livesIn ?city . ?city ex:country ?ctry .
                }""";
        assertEquals(run(m, q), runWithYannakakis(m, q));
    }

    @Test
    void executorActuallyFiresOnAcyclicBgp() {
        Model m = peopleModel();
        String q = """
                PREFIX ex: <http://example.org/>
                SELECT * WHERE { ?p1 ex:knows ?p2 . ?p2 ex:livesIn ?city . }""";
        YannakakisOpExecutor.resetCounter();
        runWithYannakakis(m, q);
        assertTrue(YannakakisOpExecutor.invocations() >= 1, "Yannakakis path should have run");
    }

    @Test
    void cyclicBgpStillMatchesStockArq() {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(NS + "a"), m.createProperty(NS + "r"), m.createResource(NS + "b"));
        m.add(m.createResource(NS + "b"), m.createProperty(NS + "r"), m.createResource(NS + "c"));
        m.add(m.createResource(NS + "c"), m.createProperty(NS + "r"), m.createResource(NS + "a"));
        // a 3-cycle in the data; the BGP pattern is also a triangle (cyclic)
        String q = """
                PREFIX ex: <http://example.org/>
                SELECT * WHERE { ?x ex:r ?y . ?y ex:r ?z . ?z ex:r ?x . }""";
        assertEquals(run(m, q), runWithYannakakis(m, q),
                "cyclic BGP must delegate to ARQ and give identical results");
    }
}