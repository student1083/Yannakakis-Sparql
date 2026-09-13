package at.ac.tuwien.thesis.yannakakis;

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
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D safety net. Every query in {@link #CORPUS} is run twice against the same
 * dataset — once on stock ARQ, once with {@link YannakakisOpExecutor} registered — and the
 * results must be equal under canonical comparison (variables sorted alphabetically within
 * each row, then rows sorted; never {@code QuerySolution.toString()}). The comparison is
 * <em>bag-exact</em>: the sorted row lists keep every duplicate, so multiplicities must
 * match, not just the set of rows. Where the query itself is order-sensitive (ORDER BY) the
 * ordered row lists are compared; where only a bare LIMIT makes the chosen rows arbitrary,
 * the row count is compared.
 *
 * <p>Every query that is not already {@code DISTINCT} is run a second time as its
 * {@code SELECT DISTINCT} variant ({@link #variants}), so the executor's final
 * multiplicity collapse is exercised against stock ARQ alongside the bag-preserving path.
 *
 * <p>The corpus is the union of every query in the existing test classes
 * ({@code DifferentialTest}, {@code YannakakisOpExecutorTest}, {@code EngineComparisonTest},
 * {@code Tdb2AuditScratchTest}, {@code AlgebraContextAnalyzerTest}) plus the
 * dropped-output-variable shapes added with the analyzer wiring. Exact duplicates were
 * collapsed; the {@code SERVICE} query from the analyzer tests is omitted because it would
 * contact a remote endpoint; and the two {@code ORDER BY} queries from the analyzer tests
 * gained a tie-breaking variable so their order is well-defined for both engines.
 *
 * <p><b>To add a query:</b> append one {@code q(...)} / {@code qEmpty(...)} /
 * {@code qOrdered(...)} / {@code qCountOnly(...)} line to {@link #CORPUS}. If it needs data
 * the fixture lacks, extend {@link #corpusModel()}.
 */
class OutputVariableSafetyTest {

    private static final String NS = "http://example.org/";
    private static final String PREFIX = "PREFIX ex: <" + NS + ">\n";
    private static final String G1 = NS + "g1";

    // ---- corpus -------------------------------------------------------------

    enum Mode { BAG, ORDERED, COUNT_ONLY }

    record Case(String name, String sparql, Mode mode, boolean expectRows) {
        @Override public String toString() { return name; }
    }

    private static Case q(String name, String sparql)          { return new Case(name, PREFIX + sparql, Mode.BAG, true); }
    private static Case qEmpty(String name, String sparql)     { return new Case(name, PREFIX + sparql, Mode.BAG, false); }
    private static Case qOrdered(String name, String sparql)   { return new Case(name, PREFIX + sparql, Mode.ORDERED, true); }
    private static Case qCountOnly(String name, String sparql) { return new Case(name, PREFIX + sparql, Mode.COUNT_ONLY, true); }

    static final List<Case> CORPUS = List.of(
            // -- DifferentialTest.Shapes (also EngineComparisonTest's single/path/star/snowflake/disconnected)
            q("single-triple",                    "SELECT * WHERE { ?x ex:knows ?y . }"),
            q("path",                             "SELECT * WHERE { ?x ex:knows ?y . ?y ex:livesIn ?c . ?c ex:country ?ct . }"),
            q("star",                             "SELECT * WHERE { ?p ex:knows ?q . ?p ex:name ?n . ?p ex:knows ?r . }"),
            q("star-non-output-join-var",         "SELECT DISTINCT ?n WHERE { ?p ex:knows ?q . ?p ex:name ?n . ?p ex:knows ?r . }"),
            q("snowflake",                        "SELECT * WHERE { ?p ex:knows ?q . ?q ex:name ?n . ?p ex:livesIn ?city . ?city ex:country ?ct . }"),
            q("chain-with-branch",                "SELECT * WHERE { ?a ex:knows ?b . ?b ex:knows ?c . ?b ex:livesIn ?city . }"),
            q("disconnected-components",          "SELECT * WHERE { ?p ex:name ?n . ?city ex:country ?ct . }"),
            q("ground-triple-only",               "SELECT * WHERE { ex:a ex:knows ex:b . }"),
            qEmpty("ground-triple-only-no-match", "SELECT * WHERE { ex:a ex:knows ex:vienna . }"),
            q("ground-triple-mixed",              "SELECT * WHERE { ex:vienna ex:country ex:at . ?x ex:knows ?y . }"),
            q("repeated-var-in-one-triple",       "SELECT * WHERE { ?x ex:knows ?x . }"),
            q("repeated-var-joined",              "SELECT * WHERE { ?x ex:knows ?x . ?x ex:name ?n . }"),
            q("var-predicate",                    "SELECT * WHERE { ?s ?p ?o . }"),
            q("var-predicate-joined",             "SELECT * WHERE { ?s ?p ?o . ?o ex:country ?ct . }"),
            // -- DifferentialTest.CyclicDelegation (also EngineComparisonTest / YannakakisOpExecutorTest / Tdb2Audit triangle)
            q("cyclic-triangle",                  "SELECT * WHERE { ?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z . }"),
            q("cyclic-four-cycle",                "SELECT * WHERE { ?a ex:r ?b . ?b ex:r ?c . ?c ex:r ?d . ?d ex:r ?a . }"),
            q("union-acyclic-and-cyclic",         "SELECT * WHERE { { ?x ex:knows ?y . ?y ex:livesIn ?c . } UNION { ?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z . } }"),
            q("cyclic-core-acyclic-tail",         "SELECT * WHERE { ?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z . ?z ex:knows ?w . }"),
            // -- DifferentialTest.Wrappers
            q("optional",                         "SELECT * WHERE { ?x ex:knows ?y . OPTIONAL { ?y ex:livesIn ?city . ?city ex:country ?ct . } }"),
            q("union",                            "SELECT * WHERE { { ?x ex:knows ?y . ?y ex:name ?n . } UNION { ?x ex:livesIn ?y . ?y ex:country ?n . } }"),
            q("filter",                           "SELECT * WHERE { ?x ex:knows ?y . ?y ex:name ?n . FILTER(?n != \"Bob\") }"),
            q("projection",                       "SELECT ?ct WHERE { ?x ex:knows ?y . ?y ex:livesIn ?c . ?c ex:country ?ct . }"),
            q("distinct",                         "SELECT DISTINCT ?ct WHERE { ?x ex:livesIn ?c . ?c ex:country ?ct . }"),
            qOrdered("order-by",                  "SELECT ?x ?n WHERE { ?x ex:knows ?y . ?x ex:name ?n . } ORDER BY ?n ?x"),
            qOrdered("order-by-limit",            "SELECT ?x ?y WHERE { ?x ex:knows ?y . } ORDER BY ?x ?y LIMIT 3"),
            qCountOnly("limit-only",              "SELECT * WHERE { ?x ex:knows ?y . } LIMIT 2"),
            // -- YannakakisOpExecutorTest
            q("executor-two-hop",                 "SELECT * WHERE { ?p1 ex:knows ?p2 . ?p2 ex:livesIn ?city . }"),
            q("executor-optional-then-bgp",       "SELECT * WHERE { ?p ex:hasType ex:Person . OPTIONAL { ?p ex:nick ?nick } ?p ex:knows ?f . }"),
            q("executor-optional-projected",      "SELECT ?p ?nick WHERE { ?p ex:hasType ex:Person . OPTIONAL { ?p ex:nick ?nick } ?p ex:knows ?f . }"),
            q("executor-two-hop-project-x",       "SELECT ?x WHERE { ?x ex:knows ?y . ?y ex:knows ?z . }"),
            // -- Tdb2AuditScratchTest
            q("audit-default",                    "SELECT * WHERE { ?a ex:knows ?b . ?b ex:livesIn ?c . }"),
            q("audit-graph-named",                "SELECT * WHERE { GRAPH <" + G1 + "> { ?a ex:knows ?b . ?b ex:livesIn ?c . } }"),
            q("audit-graph-var",                  "SELECT * WHERE { GRAPH ?g { ?a ex:knows ?b . ?b ex:livesIn ?c . } }"),
            q("audit-filter-last",                "SELECT * WHERE { ?a ex:knows ?b . ?b ex:livesIn ?c . FILTER(?c != ex:graz) }"),
            q("audit-filter-first",               "SELECT * WHERE { ?a ex:knows ?b . ?b ex:livesIn ?c . FILTER(?a != ex:c) }"),
            // -- AlgebraContextAnalyzerTest (ex:p / ex:q / ex:r data)
            q("analyzer-select-star",             "SELECT * WHERE { ?a ex:p ?b . ?b ex:q ?c }"),
            q("analyzer-distinct",                "SELECT DISTINCT ?x ?z WHERE { ?x ex:p ?y . ?y ex:q ?z }"),
            q("analyzer-projection-drops",        "SELECT ?x WHERE { ?x ex:p ?y . ?y ex:q ?z }"),
            q("analyzer-optional",                "SELECT ?x ?n WHERE { ?x ex:p ?y OPTIONAL { ?y ex:q ?n } }"),
            q("analyzer-join-groups",             "SELECT ?x WHERE { { ?x ex:p ?y } { ?y ex:q ?z } }"),
            q("analyzer-filter-unprojected",      "SELECT ?x WHERE { ?x ex:p ?y . ?y ex:q ?z FILTER(?z > 1) }"),
            q("analyzer-subquery-group",          "SELECT ?x WHERE { ?x ex:p ?y { SELECT ?y (COUNT(?w) AS ?c) WHERE { ?y ex:q ?w . ?w ex:r ?v } GROUP BY ?y } }"),
            q("analyzer-union",                   "SELECT ?x WHERE { { ?x ex:p ?y } UNION { ?y ex:q ?z } }"),
            qOrdered("analyzer-order-by",         "SELECT ?x WHERE { ?x ex:p ?y . ?y ex:q ?z } ORDER BY ?z ?x"),
            q("analyzer-group-sum",               "SELECT ?x (SUM(?z) AS ?s) WHERE { ?x ex:p ?y . ?y ex:q ?z } GROUP BY ?x"),
            q("analyzer-minus",                   "SELECT ?x WHERE { ?x ex:p ?y MINUS { ?y ex:q ?z } }"),
            q("analyzer-unrecognised-shape",      "SELECT ?x WHERE { ?x ex:p ?y . ?y ex:q ?z }"),
            q("analyzer-stored-lookup",           "SELECT ?x WHERE { ?x ex:p ?y }"),
            qOrdered("analyzer-tree-unmodified",  "SELECT ?x WHERE { ?x ex:p ?y OPTIONAL { ?y ex:q ?n } { SELECT ?y (COUNT(?w) AS ?c) WHERE { ?y ex:r ?w } GROUP BY ?y } FILTER(?n > 1) } ORDER BY ?c ?x"),
            // -- dropped-output-variable shapes (added with the analyzer wiring)
            q("drop-non-distinct-duplicates",     "SELECT ?x WHERE { ?x ex:knows ?y . ?y ex:livesIn ?c . }"),
            q("drop-filter",                      "SELECT ?x WHERE { ?x ex:knows ?y . ?y ex:name ?n . FILTER(?n != \"Bob\") }"),
            q("drop-filter-exists",               "SELECT ?x WHERE { ?x ex:knows ?y . ?x ex:name ?n . FILTER EXISTS { ?y ex:livesIn ?c } }"),
            q("drop-filter-not-exists",           "SELECT ?x WHERE { ?x ex:knows ?y . ?x ex:name ?n . FILTER NOT EXISTS { ?y ex:livesIn ?c } }"),
            qOrdered("drop-order-by",             "SELECT ?x WHERE { ?x ex:knows ?y . ?y ex:name ?n . } ORDER BY ?n ?x"),
            q("drop-group-count",                 "SELECT ?x (COUNT(?c) AS ?cnt) WHERE { ?x ex:knows ?y . ?y ex:livesIn ?c . } GROUP BY ?x"),
            q("drop-bind",                        "SELECT ?x ?len WHERE { ?x ex:knows ?y . ?y ex:name ?n . BIND(STRLEN(?n) AS ?len) }"),
            q("drop-optional-join-var",           "SELECT ?x ?ct WHERE { ?x ex:knows ?y . ?x ex:name ?n . OPTIONAL { ?y ex:livesIn ?c . ?c ex:country ?ct . } }"),
            q("drop-join-var-across-groups",      "SELECT ?x ?ct WHERE { { ?x ex:knows ?y . ?x ex:name ?n . } { ?y ex:livesIn ?c . ?c ex:country ?ct . } }"),
            q("drop-minus-join-var",              "SELECT ?x WHERE { ?x ex:knows ?y . ?x ex:name ?n . MINUS { ?y ex:livesIn ?c . ?c ex:country ?ct . } }"),
            q("drop-union-shared-var",            "SELECT ?n WHERE { { ?x ex:knows ?y . ?x ex:name ?n . } UNION { ?y ex:livesIn ?c . ?y ex:name ?n . } }"),
            q("drop-subquery-aggregate",          "SELECT ?x ?cnt WHERE { ?x ex:knows ?y . ?x ex:name ?n . { SELECT ?y (COUNT(?z) AS ?cnt) WHERE { ?y ex:knows ?z . ?y ex:name ?m . } GROUP BY ?y } }"),
            q("drop-values-join-var",             "SELECT ?x WHERE { VALUES ?y { ex:b ex:c } ?x ex:knows ?y . ?y ex:livesIn ?c . }")
    );

    // ---- fixture -------------------------------------------------------------

    /**
     * Superset of every fixture the corpus queries were written against: the social model
     * (knows-triangle a-b-c, chain c-d, self-loop d-d, livesIn/country, names), the
     * hasType/nick people, an ex:r 4-cycle, and an ex:p/ex:q/ex:r chain with numeric ex:q
     * objects for the analyzer queries. Package-private so
     * {@link YannakakisPlusEvaluatorTest} can run the same corpus at the BGP level.
     */
    static Model corpusModel() {
        Model m = ModelFactory.createDefaultModel();
        Property knows = m.createProperty(NS + "knows"), livesIn = m.createProperty(NS + "livesIn");
        Property country = m.createProperty(NS + "country"), name = m.createProperty(NS + "name");
        Property hasType = m.createProperty(NS + "hasType"), nick = m.createProperty(NS + "nick");
        Property p = m.createProperty(NS + "p"), q = m.createProperty(NS + "q"), r = m.createProperty(NS + "r");
        Resource a = m.createResource(NS + "a"), b = m.createResource(NS + "b");
        Resource c = m.createResource(NS + "c"), d = m.createResource(NS + "d");
        Resource vienna = m.createResource(NS + "vienna"), graz = m.createResource(NS + "graz");
        Resource at = m.createResource(NS + "at"), person = m.createResource(NS + "Person");

        // social model (DifferentialTest / EngineComparisonTest / Tdb2Audit)
        m.add(a, knows, b).add(b, knows, c).add(a, knows, c);
        m.add(c, knows, d).add(d, knows, d);
        m.add(b, livesIn, vienna).add(c, livesIn, graz);
        m.add(vienna, country, at).add(graz, country, at);
        m.add(a, name, "Alice").add(b, name, "Bob").add(d, name, "Dora");
        // people with types and nicks (YannakakisOpExecutorTest)
        m.add(a, hasType, person).add(b, hasType, person).add(c, hasType, person);
        m.add(a, nick, "Al");
        // ex:r 4-cycle (DifferentialTest.CyclicDelegation)
        Resource n1 = m.createResource(NS + "n1"), n2 = m.createResource(NS + "n2");
        Resource n3 = m.createResource(NS + "n3"), n4 = m.createResource(NS + "n4");
        m.add(n1, r, n2).add(n2, r, n3).add(n3, r, n4).add(n4, r, n1);
        // ex:p / ex:q / ex:r chain (AlgebraContextAnalyzerTest); some ex:q objects numeric
        Resource m1 = m.createResource(NS + "m1"), m2 = m.createResource(NS + "m2");
        Resource m3 = m.createResource(NS + "m3"), m4 = m.createResource(NS + "m4");
        Resource m5 = m.createResource(NS + "m5"), m6 = m.createResource(NS + "m6");
        m.add(m1, p, m2).add(m1, p, m3).add(m2, p, m3).add(m6, p, m4);   // m4 has no ex:q: survives MINUS
        m.add(m2, q, m4).add(m3, q, m4).add(m3, q, m5);
        m.add(m4, r, m6).add(m5, r, m6).add(m5, r, m1).add(m2, r, m6).add(m3, r, m6).add(m3, r, m1);
        m.addLiteral(m2, q, 5).addLiteral(m3, q, 0).addLiteral(m1, q, 7);
        return m;
    }

    /** Default graph = {@link #corpusModel()}; named graph g1 = the social subset. */
    private static Dataset corpusDataset() {
        Dataset ds = DatasetFactory.create(corpusModel());
        Model g1 = ModelFactory.createDefaultModel();
        Property knows = g1.createProperty(NS + "knows"), livesIn = g1.createProperty(NS + "livesIn");
        Resource a = g1.createResource(NS + "a"), b = g1.createResource(NS + "b"), c = g1.createResource(NS + "c");
        g1.add(a, knows, b).add(b, knows, c).add(b, livesIn, g1.createResource(NS + "vienna"));
        ds.addNamedModel(G1, g1);
        return ds;
    }

    // ---- canonical execution ------------------------------------------------

    /** One canonical row: cells sorted by variable name. Never QuerySolution.toString(). */
    private static String canonicalRow(QuerySolution s) {
        List<String> vars = new ArrayList<>();
        s.varNames().forEachRemaining(vars::add);
        Collections.sort(vars);
        List<String> cells = new ArrayList<>(vars.size());
        for (String v : vars) cells.add(v + "=" + s.get(v));
        return String.join(" | ", cells);
    }

    /** Rows in result order. */
    private static List<String> run(Dataset ds, String sparql) {
        List<String> rows = new ArrayList<>();
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qe = QueryExecutionFactory.create(query, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) rows.add(canonicalRow(rs.next()));
        }
        return rows;
    }

    private static List<String> canonical(List<String> rows, Mode mode) {
        if (mode == Mode.ORDERED) return rows;
        List<String> sorted = new ArrayList<>(rows);
        Collections.sort(sorted);
        return sorted;
    }

    private static List<String> withYannakakis(Dataset ds, String sparql) {
        YannakakisOpExecutor.register();
        try {
            return run(ds, sparql);
        } finally {
            YannakakisOpExecutor.unregister();
        }
    }

    // ---- tests -----------------------------------------------------------------

    /** The corpus, then the DISTINCT variant of every case that is not DISTINCT already. */
    static List<Case> variants() {
        List<Case> all = new ArrayList<>(CORPUS);
        for (Case c : CORPUS) {
            Query q = QueryFactory.create(c.sparql());
            if (q.isDistinct()) continue;
            q.setDistinct(true);
            all.add(new Case(c.name() + "-distinct", q.toString(), c.mode(), c.expectRows()));
        }
        return all;
    }

    static Stream<Case> corpus() { return variants().stream(); }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void yannakakisMatchesStockArq(Case c) {
        Dataset ds = corpusDataset();
        List<String> stock = run(ds, c.sparql());
        List<String> yann = withYannakakis(ds, c.sparql());

        if (c.mode() == Mode.COUNT_ONLY) {
            assertEquals(stock.size(), yann.size(), "row count differs for " + c.name() + ":\n" + c.sparql());
        } else {
            assertEquals(canonical(stock, c.mode()), canonical(yann, c.mode()),
                    "result differs from stock ARQ for " + c.name() + ":\n" + c.sparql());
        }
        if (c.expectRows()) {
            assertFalse(stock.isEmpty(), "fixture should give " + c.name() + " at least one row:\n" + c.sparql());
        } else {
            assertTrue(stock.isEmpty(), c.name() + " is expected to be empty");
        }
    }

    @Test
    void corpusIsLargeEnoughAndNamesAreUnique() {
        assertTrue(CORPUS.size() >= 30, "corpus has " + CORPUS.size() + " queries, need at least 30");
        assertEquals(CORPUS.size(), CORPUS.stream().map(Case::name).distinct().count(), "duplicate corpus names");
    }

    @Test
    void corpusExercisesTheYannakakisPath() {
        // The safety net is only meaningful if the executor under test actually runs.
        Dataset ds = corpusDataset();
        YannakakisOpExecutor.resetCounter();
        for (Case c : CORPUS) withYannakakis(ds, c.sparql());
        assertTrue(YannakakisOpExecutor.invocations() >= CORPUS.size() / 2,
                "expected the Yannakakis path to fire for most of the corpus, fired " + YannakakisOpExecutor.invocations());
        assertTrue(YannakakisOpExecutor.narrowedBgps() >= 10,
                "expected the analyzer to narrow O for many corpus queries, narrowed " + YannakakisOpExecutor.narrowedBgps());
        // Without DISTINCT only existence-only operands (MINUS right sides) may collapse counts.
        int collapsedPlain = YannakakisOpExecutor.collapsedBgps();
        assertTrue(collapsedPlain <= 5, "unexpectedly many collapsible BGPs without DISTINCT: " + collapsedPlain);

        YannakakisOpExecutor.resetCounter();
        List<Case> distinct = variants().subList(CORPUS.size(), variants().size());
        for (Case c : distinct) withYannakakis(ds, c.sparql());
        assertTrue(YannakakisOpExecutor.collapsedBgps() >= 20,
                "expected the DISTINCT variants to collapse counts for many BGPs, collapsed " + YannakakisOpExecutor.collapsedBgps());
        assertTrue(YannakakisOpExecutor.collapsedBgps() < YannakakisOpExecutor.invocations(),
                "GROUP BY / LIMIT / BIND below the DISTINCT must keep their multiplicities");
    }
}
