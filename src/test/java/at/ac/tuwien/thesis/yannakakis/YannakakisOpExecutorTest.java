package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpExt;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.sparql.util.NodeIsomorphismMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void outputVariablesAreLookedUpPerBgpWithoutChangingResults() {
        // two-hop paths: a-b-c, a-c-d, b-c-d. O = {?x} for the BGP; results (with
        // duplicates, bag semantics) must be exactly stock ARQ's.
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(NS + "a"), m.createProperty(NS + "knows"), m.createResource(NS + "b"));
        m.add(m.createResource(NS + "a"), m.createProperty(NS + "knows"), m.createResource(NS + "c"));
        m.add(m.createResource(NS + "b"), m.createProperty(NS + "knows"), m.createResource(NS + "c"));
        m.add(m.createResource(NS + "c"), m.createProperty(NS + "knows"), m.createResource(NS + "d"));
        String q = """
                PREFIX ex: <http://example.org/>
                SELECT ?x WHERE { ?x ex:knows ?y . ?y ex:knows ?z . }""";
        YannakakisOpExecutor.resetCounter();
        List<String> yann = runWithYannakakis(m, q);
        assertEquals(run(m, q), yann);
        assertEquals(List.of("x=http://example.org/a", "x=http://example.org/a", "x=http://example.org/b"), yann);
        assertEquals(1, YannakakisOpExecutor.narrowedBgps(),
                "the analyzer should have found O = {?x}, a strict subset of the BGP's variables");
    }

    @Test
    void failingAnalysisNeverFailsTheQuery() {
        // An operator whose effectiveOp() throws makes the analysis blow up; the
        // executor must swallow that, fall back to all variables, and still
        // produce stock ARQ's answer. The operator itself is executable (eval is
        // the identity), so stock ARQ handles the same plan without complaint.
        Graph g = peopleModel().getGraph();
        BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(Var.alloc("p1"), NodeFactory.createURI(NS + "knows"), Var.alloc("p2")));
        bp.add(Triple.create(Var.alloc("p2"), NodeFactory.createURI(NS + "livesIn"), Var.alloc("city")));
        Op plan = OpJoin.create(new OpExt("explodes") {
            @Override public Op effectiveOp() { throw new IllegalStateException("boom"); }
            @Override public QueryIterator eval(QueryIterator input, ExecutionContext execCxt) { return input; }
            @Override public void outputArgs(IndentedWriter out, SerializationContext sCxt) {}
            @Override public int hashCode() { return System.identityHashCode(this); }
            @Override public boolean equalTo(Op other, NodeIsomorphismMap labelMap) { return this == other; }
        }, new OpBGP(bp));

        ExecutionContext stock = ExecutionContext.createForGraph(g, ARQ.getContext().copy());
        ExecutionContext yann = ExecutionContext.createForGraph(g, ARQ.getContext().copy());
        yann.setExecutor(YannakakisOpExecutor.FACTORY);

        YannakakisOpExecutor.resetCounter();
        List<String> expected = drain(QC.execute(plan, BindingFactory.empty(), stock));
        List<String> actual = drain(QC.execute(plan, BindingFactory.empty(), yann));

        assertEquals(expected, actual);
        assertFalse(actual.isEmpty());
        assertEquals(0, YannakakisOpExecutor.analyses(), "the analysis threw and must not count as a run");
        assertTrue(YannakakisOpExecutor.invocations() >= 1, "the BGP was still intercepted");
        assertTrue(AlgebraContextAnalyzer.lookup(yann).isPresent(), "an empty table is stored so nothing is retried");
        assertEquals(0, AlgebraContextAnalyzer.lookup(yann).orElseThrow().size());
    }

    private static List<String> drain(QueryIterator it) {
        List<String> rows = new ArrayList<>();
        try {
            while (it.hasNext()) {
                Binding b = it.next();
                List<String> cells = new ArrayList<>();
                b.vars().forEachRemaining(v -> cells.add(v.getVarName() + "=" + b.get(v)));
                Collections.sort(cells);
                rows.add(String.join(" | ", cells));
            }
        } finally {
            it.close();
        }
        Collections.sort(rows);
        return rows;
    }

    @Test
    void analysisRunsOncePerQueryExecutionDespiteNestedExecutors() {
        // Three ?p rows each push the OPTIONAL body through a fresh OpExecutor
        // (ARQ's per-row index join); those must reuse the table stored in the
        // shared per-execution Context rather than re-analysing.
        Model m = ModelFactory.createDefaultModel();
        for (String p : List.of("a", "b", "c")) {
            m.add(m.createResource(NS + p), m.createProperty(NS + "hasType"), m.createResource(NS + "Person"));
            m.add(m.createResource(NS + p), m.createProperty(NS + "knows"), m.createResource(NS + "x" + p));
        }
        m.add(m.createResource(NS + "a"), m.createProperty(NS + "nick"), m.createLiteral("Al"));
        String q = """
                PREFIX ex: <http://example.org/>
                SELECT ?p ?nick WHERE {
                  ?p ex:hasType ex:Person .
                  OPTIONAL { ?p ex:nick ?nick }
                  ?p ex:knows ?f .
                }""";
        YannakakisOpExecutor.resetCounter();
        assertEquals(run(m, q), runWithYannakakis(m, q));
        assertEquals(1, YannakakisOpExecutor.analyses(), "exactly one analysis per query execution");
        assertTrue(YannakakisOpExecutor.invocations() >= 3, "nested executors should still intercept BGPs");

        // and a second execution gets its own fresh analysis (per-execution Context)
        runWithYannakakis(m, q);
        assertEquals(2, YannakakisOpExecutor.analyses());
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