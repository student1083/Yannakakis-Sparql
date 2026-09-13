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

    @Test
    void joinTreeCacheAvoidsRedecomposePerBindingShape() {
        // A plain BGP that syntactically follows an OPTIONAL group is chained via
        // OpSequence: it is executed exactly once, with the OPTIONAL's full
        // multi-row output as its single input QueryIterator -- i.e. one Stage,
        // whose nextStage() runs once per row. (An OPTIONAL's own body, by
        // contrast, is re-executed via a fresh OpExecutor/Stage per incoming row
        // through ARQ's QueryIterOptionalIndex "index join" -- a per-Stage cache
        // cannot help there, so this test targets the BGP after the OPTIONAL.)
        //
        // k=1: a single ?p ex:hasType ex:Person match feeds the trailing BGP once.
        Model mOne = ModelFactory.createDefaultModel();
        mOne.add(mOne.createResource(NS + "a"), mOne.createProperty(NS + "hasType"), mOne.createResource(NS + "Person"));
        mOne.add(mOne.createResource(NS + "a"), mOne.createProperty(NS + "knows"), mOne.createResource(NS + "x1"));

        // k=3: three ?p ex:hasType ex:Person matches feed the trailing BGP three
        // times -- same binding SHAPE every time (?p bound, ?f unbound), just
        // different node values.
        Model mThree = ModelFactory.createDefaultModel();
        mThree.add(mThree.createResource(NS + "a"), mThree.createProperty(NS + "hasType"), mThree.createResource(NS + "Person"));
        mThree.add(mThree.createResource(NS + "b"), mThree.createProperty(NS + "hasType"), mThree.createResource(NS + "Person"));
        mThree.add(mThree.createResource(NS + "c"), mThree.createProperty(NS + "hasType"), mThree.createResource(NS + "Person"));
        mThree.add(mThree.createResource(NS + "a"), mThree.createProperty(NS + "nick"), mThree.createLiteral("Al"));
        mThree.add(mThree.createResource(NS + "a"), mThree.createProperty(NS + "knows"), mThree.createResource(NS + "x1"));
        mThree.add(mThree.createResource(NS + "b"), mThree.createProperty(NS + "knows"), mThree.createResource(NS + "x2"));
        mThree.add(mThree.createResource(NS + "c"), mThree.createProperty(NS + "knows"), mThree.createResource(NS + "x3"));

        String q = """
                PREFIX ex: <http://example.org/>
                SELECT * WHERE {
                  ?p ex:hasType ex:Person .
                  OPTIONAL { ?p ex:nick ?nick }
                  ?p ex:knows ?f .
                }""";

        // correctness is unaffected by the cache
        assertEquals(run(mThree, q), runWithYannakakis(mThree, q));

        GyoReduction.resetDecomposeCallCount();
        runWithYannakakis(mOne, q);
        int callsForOneBinding = GyoReduction.decomposeCallCount();

        GyoReduction.resetDecomposeCallCount();
        runWithYannakakis(mThree, q);
        int callsForThreeBindings = GyoReduction.decomposeCallCount();

        // mThree feeds 2 more rows through the pipeline than mOne. Each extra row
        // forces the OPTIONAL body (?p ex:nick ?nick) through a brand-new
        // OpExecutor/Stage -- ARQ's own "index join" (QueryIterOptionalIndex) does
        // this per row, costing exactly 2 decompose() calls per extra row (the
        // acyclicity pre-check plus that fresh Stage's one-shot decomposition);
        // no per-Stage cache can remove that, since there is no Stage to reuse.
        // The trailing "?p ex:knows ?f" BGP, however, is one Stage that receives
        // all rows from the OPTIONAL's output via ONE execute() call: if its
        // decompose() were (incorrectly) re-run per binding instead of cached by
        // shape, each extra row would cost one MORE call on top of that (3 total),
        // i.e. growth would be 6, not 4.
        int extraRows = 2;
        int growth = callsForThreeBindings - callsForOneBinding;
        assertEquals(2 * extraRows, growth,
                "decompose() must be cached per binding shape inside a Stage, "
                        + "not re-run once per incoming binding");
    }
}