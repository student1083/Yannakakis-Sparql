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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4: differential test harness. Stock ARQ is the oracle; every query is
 * executed twice — with and without the Yannakakis executor registered — and
 * the result bags must be identical (canonical comparison: variables sorted
 * within a row, rows sorted, so neither column nor row order matters).
 *
 * <p>Where the query semantics themselves are order-sensitive (ORDER BY), the
 * ordered row lists are compared instead.
 */
class DifferentialTest {

    private static final String NS = "http://example.org/";
    private static final String PREFIX = "PREFIX ex: <" + NS + ">\n";

    // ---- fixture ----------------------------------------------------------

    /**
     * Social-style model with enough structure that every shape below has
     * non-empty results: knows-chains, a knows-triangle (a,b,c), a reflexive
     * edge (d knows d), places with countries, and name literals.
     */
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

    // ---- canonical execution helpers ---------------------------------------

    /** One canonical row: cells sorted by variable name. */
    private static String canonicalRow(QuerySolution s) {
        List<String> vars = new ArrayList<>();
        s.varNames().forEachRemaining(vars::add);
        Collections.sort(vars);
        List<String> cells = new ArrayList<>();
        for (String v : vars) cells.add(v + "=" + s.get(v));
        return String.join(" | ", cells);
    }

    /** Rows in result order (for ORDER BY comparisons). */
    private static List<String> runOrdered(Model model, String query) {
        List<String> rows = new ArrayList<>();
        Query q = QueryFactory.create(query);
        try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) rows.add(canonicalRow(rs.next()));
        }
        return rows;
    }

    /** Canonical bag: rows sorted, so result order does not matter. */
    private static List<String> runBag(Model model, String query) {
        List<String> rows = runOrdered(model, query);
        Collections.sort(rows);
        return rows;
    }

    private static List<String> withYannakakis(Model model, String query, boolean ordered) {
        YannakakisOpExecutor.register();
        try {
            return ordered ? runOrdered(model, query) : runBag(model, query);
        } finally {
            YannakakisOpExecutor.unregister();
        }
    }

    /** Assert both engines agree (bag semantics) and return the shared result. */
    private static List<String> assertSameBag(Model model, String query) {
        List<String> stock = runBag(model, query);
        List<String> yann = withYannakakis(model, query, false);
        assertEquals(stock, yann, "Yannakakis result bag differs from stock ARQ for:\n" + query);
        return stock;
    }

    private static void assertSameOrdered(Model model, String query) {
        List<String> stock = runOrdered(model, query);
        List<String> yann = withYannakakis(model, query, true);
        assertEquals(stock, yann, "Yannakakis ordered result differs from stock ARQ for:\n" + query);
    }

    /** Assert agreement AND that the Yannakakis path actually fired. */
    private static void assertSameBagAndFired(Model model, String query) {
        YannakakisOpExecutor.resetCounter();
        assertSameBag(model, query);
        assertTrue(YannakakisOpExecutor.invocations() >= 1,
                "expected the Yannakakis path to fire for:\n" + query);
    }

    /** Assert agreement AND that every BGP delegated to stock ARQ. */
    private static void assertSameBagAndDelegated(Model model, String query) {
        YannakakisOpExecutor.resetCounter();
        List<String> rows = assertSameBag(model, query);
        assertEquals(0, YannakakisOpExecutor.invocations(),
                "expected delegation to stock ARQ for:\n" + query);
        assertFalse(rows.isEmpty(), "delegation test should have non-empty results to be meaningful");
    }

    // ---- 1. BGP shapes ------------------------------------------------------

    @Nested
    @DisplayName("acyclic BGP shapes match stock ARQ and use the Yannakakis path")
    class Shapes {

        @Test void singleTriple() {
            assertSameBagAndFired(socialModel(), PREFIX + "SELECT * WHERE { ?x ex:knows ?y . }");
        }

        @Test void path() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE { ?x ex:knows ?y . ?y ex:livesIn ?c . ?c ex:country ?ct . }""");
        }

        @Test void star() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE { ?p ex:knows ?q . ?p ex:name ?n . ?p ex:knows ?r . }""");
        }

        @Test void starWithNonOutputJoinVariable() {
            // ?p is the star's central join variable across all three triples, but only ?n is projected.
            assertSameBagAndFiredNonEmpty(socialModel(), PREFIX + """
                    SELECT DISTINCT ?n WHERE { ?p ex:knows ?q . ?p ex:name ?n . ?p ex:knows ?r . }""");
        }

        @Test void snowflake() {
            // central ?p with two branches, each extended one hop
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE {
                      ?p ex:knows ?q . ?q ex:name ?n .
                      ?p ex:livesIn ?city . ?city ex:country ?ct .
                    }""");
        }

        @Test void chainWithBranch() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE {
                      ?a ex:knows ?b . ?b ex:knows ?c . ?b ex:livesIn ?city .
                    }""");
        }

        @Test void disconnectedComponents() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE { ?p ex:name ?n . ?city ex:country ?ct . }""");
        }

        @Test void groundTripleOnly() {
            // variable-free BGP: acts as an existence test (one empty row)
            List<String> rows = assertSameBag(socialModel(),
                    PREFIX + "SELECT * WHERE { ex:a ex:knows ex:b . }");
            assertEquals(1, rows.size(), "matching ground triple yields exactly one solution");
        }

        @Test void groundTripleOnlyNoMatch() {
            List<String> rows = assertSameBag(socialModel(),
                    PREFIX + "SELECT * WHERE { ex:a ex:knows ex:vienna . }");
            assertTrue(rows.isEmpty(), "non-matching ground triple yields no solution");
        }

        @Test void groundTripleMixedWithPatterns() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE { ex:vienna ex:country ex:at . ?x ex:knows ?y . }""");
        }

        @Test void repeatedVariableWithinOneTriple() {
            // matches only the self-loop d knows d
            List<String> rows = assertSameBagAndFiredNonEmpty(socialModel(),
                    PREFIX + "SELECT * WHERE { ?x ex:knows ?x . }");
            assertEquals(1, rows.size());
        }

        @Test void repeatedVariableJoinedWithSecondPattern() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE { ?x ex:knows ?x . ?x ex:name ?n . }""");
        }

        @Test void variableInPredicatePosition() {
            assertSameBagAndFired(socialModel(),
                    PREFIX + "SELECT * WHERE { ?s ?p ?o . }");
        }

        @Test void variableInPredicatePositionJoined() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE { ?s ?p ?o . ?o ex:country ?ct . }""");
        }

        private List<String> assertSameBagAndFiredNonEmpty(Model m, String q) {
            YannakakisOpExecutor.resetCounter();
            List<String> rows = assertSameBag(m, q);
            assertTrue(YannakakisOpExecutor.invocations() >= 1);
            assertFalse(rows.isEmpty(), "test data should produce at least one row for:\n" + q);
            return rows;
        }
    }

    // ---- 2. cyclic BGPs must delegate ---------------------------------------

    @Nested
    @DisplayName("cyclic BGPs delegate to stock ARQ and still match")
    class CyclicDelegation {

        @Test void triangle() {
            assertSameBagAndDelegated(socialModel(), PREFIX + """
                    SELECT * WHERE { ?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z . }""");
        }

        @Test void fourCycle() {
            Model m = ModelFactory.createDefaultModel();
            Property r = m.createProperty(NS + "r");
            Resource n1 = m.createResource(NS + "n1"), n2 = m.createResource(NS + "n2");
            Resource n3 = m.createResource(NS + "n3"), n4 = m.createResource(NS + "n4");
            m.add(n1, r, n2).add(n2, r, n3).add(n3, r, n4).add(n4, r, n1);
            assertSameBagAndDelegated(m, PREFIX + """
                    SELECT * WHERE {
                      ?a ex:r ?b . ?b ex:r ?c . ?c ex:r ?d . ?d ex:r ?a .
                    }""");
        }

        @Test void mixedAcyclicAndCyclicViaUnion() {
            // one acyclic BGP (must fire) and one cyclic BGP (must delegate),
            // side by side in the same query
            Model m = socialModel();
            String q = PREFIX + """
                    SELECT * WHERE {
                      { ?x ex:knows ?y . ?y ex:livesIn ?c . }
                      UNION
                      { ?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z . }
                    }""";
            YannakakisOpExecutor.resetCounter();
            assertSameBag(m, q);
            assertEquals(1, YannakakisOpExecutor.invocations(),
                    "exactly the acyclic branch should take the Yannakakis path");
        }

        @Test void cyclicCoreWithAcyclicTail() {
            // a single BGP that contains a triangle plus a pendant edge is
            // cyclic as a whole -> the entire BGP must delegate
            assertSameBagAndDelegated(socialModel(), PREFIX + """
                    SELECT * WHERE {
                      ?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z .
                      ?z ex:knows ?w .
                    }""");
        }
    }

    // ---- 3. wrapper operators stay ARQ's business ---------------------------

    @Nested
    @DisplayName("operators around the BGP are untouched")
    class Wrappers {

        @Test void optional() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE {
                      ?x ex:knows ?y .
                      OPTIONAL { ?y ex:livesIn ?city . ?city ex:country ?ct . }
                    }""");
        }

        @Test void union() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE {
                      { ?x ex:knows ?y . ?y ex:name ?n . }
                      UNION
                      { ?x ex:livesIn ?y . ?y ex:country ?n . }
                    }""");
        }

        @Test void filter() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT * WHERE {
                      ?x ex:knows ?y . ?y ex:name ?n .
                      FILTER(?n != "Bob")
                    }""");
        }

        @Test void projection() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT ?ct WHERE {
                      ?x ex:knows ?y . ?y ex:livesIn ?c . ?c ex:country ?ct .
                    }""");
        }

        @Test void distinct() {
            assertSameBagAndFired(socialModel(), PREFIX + """
                    SELECT DISTINCT ?ct WHERE {
                      ?x ex:livesIn ?c . ?c ex:country ?ct .
                    }""");
        }

        @Test void orderBy() {
            YannakakisOpExecutor.resetCounter();
            assertSameOrdered(socialModel(), PREFIX + """
                    SELECT ?x ?n WHERE { ?x ex:knows ?y . ?x ex:name ?n . }
                    ORDER BY ?n ?x""");
            assertTrue(YannakakisOpExecutor.invocations() >= 1);
        }

        @Test void orderByWithLimit() {
            // LIMIT alone is nondeterministic; ORDER BY over all projected
            // variables makes the prefix well-defined and comparable
            YannakakisOpExecutor.resetCounter();
            assertSameOrdered(socialModel(), PREFIX + """
                    SELECT ?x ?y WHERE { ?x ex:knows ?y . }
                    ORDER BY ?x ?y LIMIT 3""");
            assertTrue(YannakakisOpExecutor.invocations() >= 1);
        }

        @Test void limitOnlyPreservesCardinality() {
            // with a bare LIMIT the chosen rows may differ; the count must not
            String q = PREFIX + "SELECT * WHERE { ?x ex:knows ?y . } LIMIT 2";
            assertEquals(runBag(socialModel(), q).size(),
                    withYannakakis(socialModel(), q, false).size());
        }
    }

    // ---- 4. property-style randomized differential tests --------------------

    @Nested
    @DisplayName("random graphs x random acyclic BGPs match stock ARQ")
    class Randomized {

        private static final long BASE_SEED = 20260706L;   // fixed for reproducibility
        private static final int ROUNDS = 30;

        @Test void randomAcyclicBgpsMatchStockArq() {
            int fired = 0;
            for (int round = 0; round < ROUNDS; round++) {
                Random rnd = new Random(BASE_SEED + round);
                Model m = randomModel(rnd);
                String q = randomAcyclicQuery(rnd);

                YannakakisOpExecutor.resetCounter();
                List<String> stock = runBag(m, q);
                List<String> yann = withYannakakis(m, q, false);
                assertEquals(stock, yann,
                        "seed=" + (BASE_SEED + round) + " query:\n" + q);
                fired += YannakakisOpExecutor.invocations();
            }
            assertTrue(fired >= ROUNDS,
                    "Yannakakis path should have fired in every round (fired=" + fired + ")");
        }

        /** ~20 random triples over 8 nodes and 3 predicates. */
        private static Model randomModel(Random rnd) {
            Model m = ModelFactory.createDefaultModel();
            int tripleCount = 12 + rnd.nextInt(12);
            for (int i = 0; i < tripleCount; i++) {
                Resource s = m.createResource(NS + "n" + rnd.nextInt(8));
                Property p = m.createProperty(NS + "p" + rnd.nextInt(3));
                Resource o = m.createResource(NS + "n" + rnd.nextInt(8));
                m.add(s, p, o);
            }
            return m;
        }

        /**
         * Grows a Berge-acyclic (hence alpha-acyclic) BGP: each new pattern
         * connects an already-used variable to a fresh variable or a constant,
         * so the join structure is always a tree.
         */
        private static String randomAcyclicQuery(Random rnd) {
            List<String> vars = new ArrayList<>();
            vars.add("?v0");
            vars.add("?v1");
            StringBuilder bgp = new StringBuilder();
            bgp.append("?v0 ").append(pred(rnd, 0)).append(" ?v1 .\n");

            int size = 2 + rnd.nextInt(4);                  // 2..5 patterns
            int nextVar = 2;
            for (int i = 1; i < size; i++) {
                String anchor = vars.get(rnd.nextInt(vars.size()));
                String other;
                if (rnd.nextInt(4) == 0) {                  // sometimes a constant endpoint
                    other = "ex:n" + rnd.nextInt(8);
                } else {
                    other = "?v" + nextVar++;
                    vars.add(other);
                }
                String p = pred(rnd, i);
                if (rnd.nextBoolean()) {
                    bgp.append(anchor).append(' ').append(p).append(' ').append(other);
                } else {
                    bgp.append(other).append(' ').append(p).append(' ').append(anchor);
                }
                bgp.append(" .\n");
            }
            return PREFIX + "SELECT * WHERE {\n" + bgp + "}";
        }

        private static String pred(Random rnd, int patternIndex) {
            // occasionally a variable predicate; unique per pattern so it can
            // never join two patterns and accidentally form a cycle
            return rnd.nextInt(6) == 0 ? "?p" + patternIndex : "ex:p" + rnd.nextInt(3);
        }
    }
}
