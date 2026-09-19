package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.tdb2.solver.stats.Stats;
import org.apache.jena.tdb2.solver.stats.StatsCollector;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two {@link CardinalityEstimator} implementations against graphs whose pattern
 * cardinalities are known, and the selection in {@link CardinalityEstimator#forGraph}.
 *
 * <p>The TDB2 part uses an in-memory TDB2 dataset for the {@code NodeId} counting path and
 * one on-disk store under {@code target/tdb2-estimator/<stamp>/} for the {@code stats.opt}
 * path; like {@code Tdb2AuditScratchTest}, the directory is not deleted afterwards (TDB2
 * memory-maps its files and Windows refuses to delete a mapped file while the JVM lives).
 */
class CardinalityEstimatorTest {

    private static final String NS = "http://example.org/";

    private static Node uri(String local) { return NodeFactory.createURI(NS + local); }
    private static Node var(String name)  { return Var.alloc(name); }

    /** knows: 6 triples (n0 knows n1..n6); livesIn: 2; type: 3 Persons, 1 Place; total 12. */
    private static Model model() {
        Model m = ModelFactory.createDefaultModel();
        Property knows = m.createProperty(NS + "knows"), livesIn = m.createProperty(NS + "livesIn");
        Resource person = m.createResource(NS + "Person"), place = m.createResource(NS + "Place");
        Resource n0 = m.createResource(NS + "n0");
        for (int i = 1; i <= 6; i++) m.add(n0, knows, m.createResource(NS + "n" + i));
        m.add(n0, livesIn, m.createResource(NS + "vienna"));
        m.add(m.createResource(NS + "n1"), livesIn, m.createResource(NS + "graz"));
        m.add(n0, RDF.type, person).add(m.createResource(NS + "n1"), RDF.type, person)
                .add(m.createResource(NS + "n2"), RDF.type, person);
        m.add(m.createResource(NS + "vienna"), RDF.type, place);
        return m;
    }

    private static final Triple KNOWS   = Triple.create(var("s"), uri("knows"), var("o"));
    private static final Triple LIVES   = Triple.create(var("s"), uri("livesIn"), var("o"));
    private static final Triple PERSONS = Triple.create(var("s"), RDF.Nodes.type, uri("Person"));
    private static final Triple N0_ANY  = Triple.create(uri("n0"), var("p"), var("o"));
    private static final Triple ALL     = Triple.create(var("s"), var("p"), var("o"));
    private static final Triple MISSING = Triple.create(uri("nobody"), var("p"), var("o"));
    private static final Triple GROUND  = Triple.create(uri("n0"), uri("knows"), uri("n1"));
    private static final Triple SELF    = Triple.create(var("x"), uri("knows"), var("x"));   // wildcard twice: 6, an upper bound of the relation (0 rows)

    private static void assertExactBelowLimit(CardinalityEstimator e) {
        assertEquals(6, e.estimate(KNOWS));
        assertEquals(2, e.estimate(LIVES));
        assertEquals(3, e.estimate(PERSONS));
        assertEquals(8, e.estimate(N0_ANY));           // 6 knows + livesIn + type
        assertEquals(12, e.estimate(ALL));
        assertEquals(0, e.estimate(MISSING));
        assertEquals(1, e.estimate(GROUND));
        assertEquals(6, e.estimate(SELF));
    }

    @Nested
    class Bounded {

        @Test void exactBelowTheLimit() {
            assertExactBelowLimit(new BoundedCountEstimator(model().getGraph(), 100));
        }

        @Test void saturatesAtTheLimit() {
            CardinalityEstimator e = new BoundedCountEstimator(model().getGraph(), 4);
            assertEquals(4, e.estimate(KNOWS), "6 matches, reported as the bound");
            assertEquals(4, e.estimate(ALL));
            assertEquals(2, e.estimate(LIVES), "below the bound stays exact");
            assertEquals(0, e.estimate(MISSING));
        }

        @Test void limitMustBePositive() {
            assertThrows(IllegalArgumentException.class, () -> new BoundedCountEstimator(model().getGraph(), 0));
        }
    }

    @Nested
    class Selection {

        @Test void inMemoryGraphGetsTheBoundedCountWithTheContextLimit() {
            Context cxt = new Context();
            cxt.set(YannakakisSymbols.ESTIMATE_LIMIT, 3);
            CardinalityEstimator e = CardinalityEstimator.forGraph(model().getGraph(), cxt);
            BoundedCountEstimator bounded = assertInstanceOf(BoundedCountEstimator.class, e);
            assertEquals(3, bounded.limit());
            assertEquals(3, e.estimate(KNOWS));
        }

        @Test void defaultLimitWhenUnset() {
            CardinalityEstimator e = CardinalityEstimator.forGraph(model().getGraph(), new Context());
            assertEquals(YannakakisSymbols.ESTIMATE_LIMIT_DEFAULT, assertInstanceOf(BoundedCountEstimator.class, e).limit());
        }

        @Test void contextInstanceOverridesTheChoice() {
            Context cxt = new Context();
            cxt.set(YannakakisSymbols.CARDINALITY_ESTIMATOR, CardinalityEstimator.UNKNOWN);
            assertSame(CardinalityEstimator.UNKNOWN, CardinalityEstimator.forGraph(model().getGraph(), cxt));
            assertEquals(0, CardinalityEstimator.UNKNOWN.estimate(KNOWS));
        }

        @Test void stringValuedSymbolsParse() {
            Context cxt = new Context();
            cxt.set(YannakakisSymbols.FUSION_RATIO, "2.5");
            cxt.set(YannakakisSymbols.ESTIMATE_LIMIT, "7");
            assertEquals(2.5, YannakakisSymbols.fusionRatio(cxt));
            assertEquals(7, YannakakisSymbols.estimateLimit(cxt));
            assertEquals(YannakakisSymbols.FUSION_RATIO_DEFAULT, YannakakisSymbols.fusionRatio(new Context()));
            assertEquals(YannakakisSymbols.FUSION_RATIO_DEFAULT, YannakakisSymbols.fusionRatio(ARQ.getContext()),
                    "nothing in this test suite may leave the symbol set globally");
        }

        @Test void tdb2FactoryDeclinesNonTdb2Graphs() {
            assertTrue(Tdb2CardinalityEstimator.forGraph(model().getGraph(), 10).isEmpty());
        }
    }

    @Nested
    class Tdb2 {

        private static Dataset memStore;
        private static Dataset diskStore;
        private static Path diskDir;

        @BeforeAll
        static void openStores() throws IOException {
            memStore = TDB2Factory.createDataset();
            Txn.executeWrite(memStore, () -> memStore.getDefaultModel().add(model()));

            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            diskDir = Path.of("target", "tdb2-estimator", stamp);
            Files.createDirectories(diskDir);
            diskStore = TDB2Factory.connectDataset(diskDir.toString());
            Txn.executeWrite(diskStore, () -> {
                diskStore.getDefaultModel().add(model());
                diskStore.getNamedModel(NS + "g1").add(model());
            });
            // The statistics tdb2.tdbstats would write, gathered over the same triples and
            // stored where TDB2 looks for them (the container directory of the store).
            StatsCollector collector = Stats.gather(model().getGraph());
            Stats.write(diskDir.resolve("stats.opt").toString(), collector.results());
        }

        @AfterAll
        static void closeStores() {
            memStore.close();
            diskStore.close();
        }

        @Test void memoryStoreCountsNodeIdTuplesExactly() {
            Txn.executeRead(memStore, () -> {
                Graph g = memStore.getDefaultModel().getGraph();
                Optional<Tdb2CardinalityEstimator> e = Tdb2CardinalityEstimator.forGraph(g, 100);
                assertTrue(e.isPresent(), "a TDB2 graph must get the TDB2 estimator: " + g.getClass());
                assertFalse(e.get().hasStatistics(), "an in-memory store has no stats.opt");
                assertExactBelowLimit(e.get());
                assertEquals(4, new Tdb2CardinalityEstimator(
                        ((org.apache.jena.tdb2.store.GraphTDB) unwrap(g)).getNodeTupleTable(), null, null, 4).estimate(KNOWS),
                        "the NodeId scan saturates at the bound");
            });
        }

        @Test void forGraphPicksTdb2ForTdb2Graphs() {
            Txn.executeRead(memStore, () -> {
                CardinalityEstimator e = CardinalityEstimator.forGraph(memStore.getDefaultModel().getGraph(), new Context());
                assertInstanceOf(Tdb2CardinalityEstimator.class, e);
            });
        }

        @Test void diskStoreUsesStatisticsForPredicateTypeAndTotal() {
            Txn.executeRead(diskStore, () -> {
                Graph g = diskStore.getDefaultModel().getGraph();
                Tdb2CardinalityEstimator e = Tdb2CardinalityEstimator.forGraph(g, 1).orElseThrow();
                assertTrue(e.hasStatistics(), "stats.opt next to the store must be found");
                // limit 1: anything a scan answers saturates at 1, so these can only come from the file
                assertEquals(6, e.estimate(KNOWS));
                assertEquals(2, e.estimate(LIVES));
                assertEquals(3, e.estimate(PERSONS));
                assertEquals(12, e.estimate(ALL));
                // bound subject / unknown predicate / ground: statistics say nothing, the scan answers
                assertEquals(1, e.estimate(N0_ANY));
                assertEquals(0, e.estimate(MISSING));
                assertEquals(1, e.estimate(GROUND));
                assertEquals(0, e.estimate(Triple.create(var("s"), uri("unknownPredicate"), var("o"))),
                        "a predicate the file does not list falls back to the scan (nothing to find here)");
            });
        }

        @Test void namedGraphsScanTheQuadTableAndIgnoreStatistics() {
            Txn.executeRead(diskStore, () -> {
                Graph g = diskStore.getNamedModel(NS + "g1").getGraph();
                Tdb2CardinalityEstimator e = Tdb2CardinalityEstimator.forGraph(g, 100).orElseThrow();
                assertFalse(e.hasStatistics(), "the statistics describe the default graph only");
                assertExactBelowLimit(e);
                Graph empty = diskStore.getNamedModel(NS + "nothing").getGraph();
                assertEquals(0, Tdb2CardinalityEstimator.forGraph(empty, 100).orElseThrow().estimate(KNOWS));
            });
        }

        @Test void statisticsFileParses() {
            Tdb2CardinalityEstimator.Statistics s = Tdb2CardinalityEstimator.parse(
                    Stats.format(Stats.gather(model().getGraph()).results()));
            assertEquals(12, s.total());
            assertEquals(6, s.predicates().get(uri("knows")));
            assertEquals(2, s.predicates().get(uri("livesIn")));
            assertEquals(4, s.predicates().get(RDF.Nodes.type));
            assertEquals(3, s.types().get(uri("Person")));
            assertEquals(1, s.types().get(uri("Place")));
            assertThrows(IllegalArgumentException.class,
                    () -> Tdb2CardinalityEstimator.parse(org.apache.jena.sparql.sse.SSE.parse("(notstats)")));
        }

        private static Graph unwrap(Graph g) {
            return g instanceof org.apache.jena.tdb2.store.GraphViewSwitchable s ? s.getBaseGraph() : g;
        }
    }
}
