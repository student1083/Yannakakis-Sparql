package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Graph;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.main.OpExecutor;
import org.apache.jena.sparql.engine.main.OpExecutorFactory;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.engine.main.StageBuilder;
import org.apache.jena.sparql.engine.main.StageGenerator;
import org.apache.jena.sparql.mgt.Explain;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.TDB2Factory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * SCRATCH / AUDIT ONLY - not part of the regular suite's assertions.
 *
 * Empirically answers: which OpExecutor is constructed for a TDB2 dataset after
 * YannakakisOpExecutor.register(), which algebra reaches execution (quad rewriting),
 * whether execute(OpBGP) is ever called, and how filtered BGPs are routed.
 * The in-memory model/dataset serves as the control.
 *
 * The TDB2 store is written to target/tdb2-audit/&lt;timestamp&gt;/store and deliberately
 * NOT deleted (Windows: memory-mapped files cannot be removed while the JVM lives).
 * A human-readable report is written next to it as report.txt.
 */
class Tdb2AuditScratchTest {

    private static final String NS = "http://example.org/";
    private static final String PREFIX = "PREFIX ex: <" + NS + ">\n";
    private static final String G1 = NS + "g1";

    private static final String Q_DEFAULT = PREFIX + """
            SELECT * WHERE { ?a ex:knows ?b . ?b ex:livesIn ?c . }""";
    private static final String Q_GRAPH_NAMED = PREFIX + """
            SELECT * WHERE { GRAPH <http://example.org/g1> { ?a ex:knows ?b . ?b ex:livesIn ?c . } }""";
    private static final String Q_GRAPH_VAR = PREFIX + """
            SELECT * WHERE { GRAPH ?g { ?a ex:knows ?b . ?b ex:livesIn ?c . } }""";
    // filter on a variable bound by the LAST triple -> filter placement keeps (filter (bgp t1 t2))
    private static final String Q_FILTER_LAST = PREFIX + """
            SELECT * WHERE { ?a ex:knows ?b . ?b ex:livesIn ?c . FILTER(?c != ex:graz) }""";
    // filter on a variable bound by the FIRST triple -> filter placement may split the BGP
    private static final String Q_FILTER_FIRST = PREFIX + """
            SELECT * WHERE { ?a ex:knows ?b . ?b ex:livesIn ?c . FILTER(?a != ex:c) }""";
    // triangle -> cyclic -> YannakakisOpExecutor must call super.execute(OpBGP, input)
    private static final String Q_CYCLIC = PREFIX + """
            SELECT * WHERE { ?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z . }""";

    /** Wraps the global stage generator so we can see whether OpExecutor.execute(OpBGP) reached it. */
    private static final List<String> STAGE_CALLS = new ArrayList<>();
    private static StageGenerator originalStageGenerator;

    private static Path auditDir;
    private static Dataset tdb2;
    private static Dataset mem;
    private static Model memModel;
    private static final StringBuilder REPORT = new StringBuilder();

    @BeforeAll
    static void setUp() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        auditDir = Path.of("target", "tdb2-audit", stamp);
        Files.createDirectories(auditDir);
        Path store = auditDir.resolve("store");
        Files.createDirectories(store);

        tdb2 = TDB2Factory.connectDataset(store.toString());
        Txn.executeWrite(tdb2, () -> {
            load(tdb2.getDefaultModel());
            load(tdb2.getNamedModel(G1));
        });

        memModel = load(ModelFactory.createDefaultModel());
        mem = DatasetFactory.create();
        mem.setDefaultModel(memModel);
        mem.addNamedModel(G1, load(ModelFactory.createDefaultModel()));

        ARQ.setExecutionLogging(Explain.InfoLevel.ALL);
        originalStageGenerator = StageBuilder.chooseStageGenerator(ARQ.getContext());
        StageBuilder.setGenerator(ARQ.getContext(), (pattern, input, execCxt) -> {
            Graph g = execCxt.getActiveGraph();
            STAGE_CALLS.add("stageGenerator(" + originalStageGenerator.getClass().getSimpleName()
                    + ") size=" + pattern.size() + " activeGraph=" + (g == null ? "null" : g.getClass().getName()));
            return originalStageGenerator.execute(pattern, input, execCxt);
        });
        log("Jena ARQ version: " + ARQ.VERSION);
        log("TDB2 store dir: " + store.toAbsolutePath());
        log("TDB2 dataset class: " + tdb2.asDatasetGraph().getClass().getName());
        log("TDB2 dataset ctx factory: " + describeFactory(QC.getFactory(tdb2.asDatasetGraph().getContext())));
        log("mem dataset class: " + mem.asDatasetGraph().getClass().getName());
        log("mem dataset ctx factory: " + describeFactory(QC.getFactory(mem.asDatasetGraph().getContext())));
        log("global ARQ ctx factory (before register): " + describeFactory(QC.getFactory(ARQ.getContext())));
        log("global ARQ ctx stage generator: " + StageBuilder.chooseStageGenerator(ARQ.getContext()).getClass().getName());
        log("global optFilterPlacement: " + ARQ.getContext().get(ARQ.optFilterPlacement)
                + " optFilterPlacementBGP: " + ARQ.getContext().get(ARQ.optFilterPlacementBGP));
        log("");
    }

    @AfterAll
    static void tearDown() throws IOException {
        ARQ.setExecutionLogging(Explain.InfoLevel.NONE);
        StageBuilder.setGenerator(ARQ.getContext(), originalStageGenerator);
        Files.writeString(auditDir.resolve("report.txt"), REPORT.toString(), StandardCharsets.UTF_8);
        System.out.println("=== audit report written to " + auditDir.resolve("report.txt").toAbsolutePath());
    }

    private static Model load(Model m) {
        m.add(m.createResource(NS + "a"), m.createProperty(NS + "knows"),   m.createResource(NS + "b"));
        m.add(m.createResource(NS + "b"), m.createProperty(NS + "knows"),   m.createResource(NS + "c"));
        m.add(m.createResource(NS + "b"), m.createProperty(NS + "livesIn"), m.createResource(NS + "vienna"));
        m.add(m.createResource(NS + "c"), m.createProperty(NS + "livesIn"), m.createResource(NS + "graz"));
        m.add(m.createResource(NS + "vienna"), m.createProperty(NS + "country"), m.createResource(NS + "at"));
        return m;
    }

    // ---- the probes ------------------------------------------------------

    @Test
    void auditTdb2GlobalRegistration() {
        section("A. TDB2 dataset, YannakakisOpExecutor.register() (global ARQ context)");
        for (String[] q : queries()) probe(q[0], tdb2, q[1], Mode.GLOBAL);
    }

    @Test
    void auditTdb2NoRegistration() {
        section("B. TDB2 dataset, nothing registered (baseline)");
        for (String[] q : queries()) probe(q[0], tdb2, q[1], Mode.NONE);
    }

    @Test
    void auditTdb2PerDatasetRegistration() {
        section("C. TDB2 dataset, factory set on the DATASET context only (proposed fix, probe only)");
        for (String[] q : queries()) probe(q[0], tdb2, q[1], Mode.PER_DATASET);
    }

    @Test
    void auditMemDatasetGlobalRegistration() {
        section("D. in-memory general Dataset, YannakakisOpExecutor.register() (control)");
        for (String[] q : queries()) probe(q[0], mem, q[1], Mode.GLOBAL);
    }

    @Test
    void auditMemModelGlobalRegistration() {
        section("E. in-memory Model via QueryExecutionFactory.create(q, model), register() (control, as existing tests)");
        // GRAPH queries make no sense on a bare model; run the default-graph and filter ones
        probe("default-bgp", memModel, Q_DEFAULT, Mode.GLOBAL);
        probe("filter-last-var", memModel, Q_FILTER_LAST, Mode.GLOBAL);
        probe("filter-first-var", memModel, Q_FILTER_FIRST, Mode.GLOBAL);
        probe("cyclic-triangle", memModel, Q_CYCLIC, Mode.GLOBAL);
    }

    private static List<String[]> queries() {
        return List.of(
                new String[]{"default-bgp", Q_DEFAULT},
                new String[]{"graph-named", Q_GRAPH_NAMED},
                new String[]{"graph-var", Q_GRAPH_VAR},
                new String[]{"filter-last-var", Q_FILTER_LAST},
                new String[]{"filter-first-var", Q_FILTER_FIRST},
                new String[]{"cyclic-triangle", Q_CYCLIC});
    }

    private enum Mode { NONE, GLOBAL, PER_DATASET }

    private static void probe(String label, Dataset ds, String sparql, Mode mode) {
        DatasetGraph dsg = ds.asDatasetGraph();
        OpExecutorFactory savedDsFactory = QC.getFactory(dsg.getContext());
        if (mode == Mode.GLOBAL) YannakakisOpExecutor.register();
        if (mode == Mode.PER_DATASET) QC.setFactory(dsg.getContext(), YannakakisOpExecutor.FACTORY);
        try {
            Txn.executeRead(ds, () -> run(label, dsg, () -> QueryExecutionFactory.create(QueryFactory.create(sparql), ds)));
        } finally {
            if (mode == Mode.GLOBAL) YannakakisOpExecutor.unregister();
            if (mode == Mode.PER_DATASET) QC.setFactory(dsg.getContext(), savedDsFactory);
        }
    }

    private static void probe(String label, Model model, String sparql, Mode mode) {
        if (mode == Mode.GLOBAL) YannakakisOpExecutor.register();
        try {
            run(label, null, () -> QueryExecutionFactory.create(QueryFactory.create(sparql), model));
        } finally {
            if (mode == Mode.GLOBAL) YannakakisOpExecutor.unregister();
        }
    }

    private static void run(String label, DatasetGraph dsg, Supplier<QueryExecution> factory) {
        YannakakisOpExecutor.resetCounter();
        STAGE_CALLS.clear();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        List<String> rows;
        Context cxt;
        String factoryDesc, executorDesc, stageGen;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (QueryExecution qe = factory.get()) {
            cxt = qe.getContext();
            OpExecutorFactory f = QC.getFactory(cxt);
            factoryDesc = describeFactory(f);
            // mirror OpExecutor.createOpExecutor: null factory -> stdFactory
            DatasetGraph execDsg = (dsg != null) ? dsg : qe.getDataset().asDatasetGraph();
            ExecutionContext ec = ExecutionContext.create(execDsg, cxt);
            OpExecutor constructed = (f == null ? OpExecutor.stdFactory : f).create(ec);
            executorDesc = constructed.getClass().getName();
            stageGen = StageBuilder.chooseStageGenerator(cxt).getClass().getName();
            rows = collect(qe.execSelect());
        } finally {
            System.setErr(origErr);
        }
        Graph dft = (dsg != null) ? dsg.getDefaultGraph() : null;

        log("--- query: " + label);
        log("  factory in query ctx   : " + factoryDesc);
        log("  executor constructed   : " + executorDesc);
        log("  stage generator        : " + stageGen + " (wrapped by the audit recorder)");
        if (dft != null) {
            log("  dataset default graph  : " + dft.getClass().getName()
                    + "  (identity-stable across getDefaultGraph() calls: " + (dft == dsg.getDefaultGraph()) + ")");
        }
        log("  yannakakis invocations : " + YannakakisOpExecutor.invocations());
        log("  stage generator calls  : " + (STAGE_CALLS.isEmpty() ? "<none>" : "") );
        for (String c : STAGE_CALLS) log("      " + c);
        log("  explain log (stderr)   :\n" + indent(captured.toString(StandardCharsets.UTF_8).strip(), 6));
        log("  rows (" + rows.size() + ")           : " + rows);
        log("");
    }

    // ---- helpers ---------------------------------------------------------

    private static String describeFactory(OpExecutorFactory f) {
        if (f == null) return "<null> (-> OpExecutor.stdFactory)";
        String name = f.getClass().getName();
        if (f == YannakakisOpExecutor.FACTORY) name += " [== YannakakisOpExecutor.FACTORY]";
        return name;
    }

    private static List<String> collect(ResultSet rs) {
        List<String> rows = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution s = rs.next();
            List<String> vars = new ArrayList<>();
            s.varNames().forEachRemaining(vars::add);
            Collections.sort(vars);
            List<String> cells = new ArrayList<>();
            for (String v : vars) cells.add(v + "=" + s.get(v));
            rows.add(String.join(" | ", cells));
        }
        Collections.sort(rows);
        return rows;
    }

    private static String indent(String s, int n) {
        String pad = " ".repeat(n);
        return pad + s.replace("\n", "\n" + pad);
    }

    private static void section(String title) {
        log("==================================================================");
        log(title);
        log("==================================================================");
    }

    private static void log(String s) {
        System.out.println(s);
        REPORT.append(s).append('\n');
    }
}
