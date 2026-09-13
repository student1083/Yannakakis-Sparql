package at.ac.tuwien.thesis.yannakakis;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prints a wall-clock comparison table (stock ARQ vs. {@link YannakakisOpExecutor})
 * for a small, fixed set of queries against one shared in-memory graph.
 *
 * <p>This is NOT a performance benchmark: each query runs exactly once per engine, with
 * no warm-up/JIT stabilization, so the millisecond columns are indicative only. The
 * point of this test is the correctness assertion (canonical result-bag equality) that
 * runs alongside the timing; see CLAUDE.md's testing strategy and {@code DifferentialTest}
 * for the real correctness harness.
 */
class EngineComparisonTest {

    private static final String NS = "http://example.org/";
    private static final String PREFIX = "PREFIX ex: <" + NS + ">\n";

    private record QuerySpec(String name, String shape, String sparql) {}

    private static final List<QuerySpec> QUERIES = List.of(
            new QuerySpec("single-triple", "one triple pattern, no join",
                    PREFIX + "SELECT * WHERE { ?x ex:knows ?y . }"),
            new QuerySpec("path", "3-hop path: knows -> livesIn -> country",
                    PREFIX + """
                            SELECT * WHERE {
                              ?x ex:knows ?y . ?y ex:livesIn ?c . ?c ex:country ?ct .
                            }"""),
            new QuerySpec("star", "central variable ?p joined by 3 triples",
                    PREFIX + """
                            SELECT * WHERE { ?p ex:knows ?q . ?p ex:name ?n . ?p ex:knows ?r . }"""),
            new QuerySpec("snowflake", "central ?p with two branches, each extended one hop",
                    PREFIX + """
                            SELECT * WHERE {
                              ?p ex:knows ?q . ?q ex:name ?n .
                              ?p ex:livesIn ?city . ?city ex:country ?ct .
                            }"""),
            new QuerySpec("disconnected-components", "two independent triples, no shared variable",
                    PREFIX + "SELECT * WHERE { ?p ex:name ?n . ?city ex:country ?ct . }"),
            new QuerySpec("cyclic-triangle", "3-cycle knows(x,y),knows(y,z),knows(x,z) - must delegate",
                    PREFIX + """
                            SELECT * WHERE { ?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z . }""")
    );

    /** Same shape as DifferentialTest's socialModel: knows-triangle+chain, livesIn/country, names. */
    private static Model socialModel() {
        Model m = ModelFactory.createDefaultModel();
        Property knows   = m.createProperty(NS + "knows");
        Property livesIn = m.createProperty(NS + "livesIn");
        Property country = m.createProperty(NS + "country");
        Property name    = m.createProperty(NS + "name");
        Resource a = m.createResource(NS + "a"), b = m.createResource(NS + "b");
        Resource c = m.createResource(NS + "c"), d = m.createResource(NS + "d");
        Resource vienna = m.createResource(NS + "vienna"), graz = m.createResource(NS + "graz");
        Resource at = m.createResource(NS + "at");

        m.add(a, knows, b).add(b, knows, c).add(a, knows, c);   // triangle a-b-c
        m.add(c, knows, d).add(d, knows, d);                    // chain + self-loop
        m.add(b, livesIn, vienna).add(c, livesIn, graz);
        m.add(vienna, country, at).add(graz, country, at);
        m.add(a, name, "Alice").add(b, name, "Bob").add(d, name, "Dora");
        return m;
    }

    /** One canonical row: cells sorted by variable name. */
    private static String canonicalRow(QuerySolution s) {
        List<String> vars = new ArrayList<>();
        s.varNames().forEachRemaining(vars::add);
        Collections.sort(vars);
        List<String> cells = new ArrayList<>();
        for (String v : vars) cells.add(v + "=" + s.get(v));
        return String.join(" | ", cells);
    }

    /** Canonical result bag: rows sorted, so result order does not matter. */
    private static List<String> runBag(Model model, String query) {
        List<String> rows = new ArrayList<>();
        Query q = QueryFactory.create(query);
        try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) rows.add(canonicalRow(rs.next()));
        }
        Collections.sort(rows);
        return rows;
    }

    private record Timed(List<String> rows, double millis) {}

    private static Timed timedRun(Model model, String query) {
        long start = System.nanoTime();
        List<String> rows = runBag(model, query);
        double millis = (System.nanoTime() - start) / 1_000_000.0;
        return new Timed(rows, millis);
    }

    private record ComparisonRow(String name, int resultCount, double defaultMs,
                                  double yannakakisMs, boolean fired) {}

    @Test
    void compareEnginesAcrossQueries() {
        Model model = socialModel();
        List<ComparisonRow> table = new ArrayList<>();

        for (QuerySpec spec : QUERIES) {
            Timed stock = timedRun(model, spec.sparql());

            YannakakisOpExecutor.resetCounter();
            YannakakisOpExecutor.register();
            Timed yann;
            try {
                yann = timedRun(model, spec.sparql());
            } finally {
                YannakakisOpExecutor.unregister();
            }
            boolean fired = YannakakisOpExecutor.invocations() >= 1;

            assertEquals(stock.rows(), yann.rows(),
                    "result bags differ for query \"" + spec.name() + "\":\n" + spec.sparql());

            table.add(new ComparisonRow(spec.name(), stock.rows().size(),
                    stock.millis(), yann.millis(), fired));
        }

        printTable(table);
    }

    private static void printTable(List<ComparisonRow> table) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%n%-26s %8s %12s %16s %8s%n",
                "query", "results", "default ms", "yannakakis ms", "fired"));
        sb.append("-".repeat(26 + 1 + 8 + 1 + 12 + 1 + 16 + 1 + 8)).append('\n');
        for (ComparisonRow r : table) {
            sb.append(String.format(Locale.ROOT, "%-26s %8d %12.3f %16.3f %8s%n",
                    r.name(), r.resultCount(), r.defaultMs(), r.yannakakisMs(), r.fired()));
        }
        System.out.println(sb);
    }
}
