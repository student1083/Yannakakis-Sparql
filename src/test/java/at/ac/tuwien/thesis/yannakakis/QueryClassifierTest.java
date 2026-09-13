package at.ac.tuwien.thesis.yannakakis;

import at.ac.tuwien.thesis.yannakakis.QueryClassifier.Classification;
import at.ac.tuwien.thesis.yannakakis.QueryClassifier.Kind;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryClassifierTest {

    private static Node node(String token) {
        if (token.startsWith("?")) return Var.alloc(token.substring(1));
        return NodeFactory.createURI("http://example.org/" + token);
    }
    private static Triple t(String s, String p, String o) {
        return Triple.create(node(s), node(p), node(o));
    }
    private static QueryHypergraph hg(Triple... triples) {
        BasicPattern bgp = new BasicPattern();
        for (Triple tr : triples) bgp.add(tr);
        return QueryHypergraph.fromBasicPattern(bgp);
    }
    private static QueryHypergraph hg(List<Triple> triples) {
        return hg(triples.toArray(new Triple[0]));
    }
    private static Set<Var> vars(String... names) {
        Set<Var> s = new LinkedHashSet<>();
        for (String n : names) s.add(Var.alloc(n));
        return s;
    }

    /** R1(x,y) R2(y,z) R3(z,w). */
    private static QueryHypergraph path() {
        return hg(t("?x", "r", "?y"), t("?y", "r", "?z"), t("?z", "r", "?w"));
    }
    /** R1(x,a) R2(x,b) R3(x,c). */
    private static QueryHypergraph star() {
        return hg(t("?x", "p1", "?a"), t("?x", "p2", "?b"), t("?x", "p3", "?c"));
    }
    /** Star on x with one extra hop on every arm: x-a-a1, x-b-b1, x-c-c1. */
    private static QueryHypergraph snowflake() {
        return hg(
                t("?x", "p1", "?a"), t("?a", "q1", "?a1"),
                t("?x", "p2", "?b"), t("?b", "q2", "?b1"),
                t("?x", "p3", "?c"), t("?c", "q3", "?c1"));
    }

    /** Every classification of an acyclic BGP must produce a valid join tree over all its edges. */
    private static Classification classified(QueryHypergraph h, Set<Var> o, Kind expected) {
        Optional<Classification> c = QueryClassifier.classify(h, o);
        assertTrue(c.isPresent(), "acyclic BGP must be classified");
        Classification cl = c.get();
        assertEquals(expected, cl.kind(), "kind for O=" + o + "\n" + cl.joinTree().pretty());
        assertEquals(h.edges().size(), cl.joinTree().size());
        assertTrue(cl.joinTree().satisfiesRunningIntersection(),
                "re-rooted / free-connex tree lost running intersection:\n" + cl.joinTree().pretty());
        assertSame(cl.joinTree().root(), cl.root());
        assertTrue(cl.joinTree().nodes().containsAll(cl.connexSubtree()));
        return cl;
    }

    /** The two free-connex classes must come with a verified connex subtree. */
    private static void assertConnex(Classification cl) {
        assertTrue(cl.satisfiesConnexProperty(),
                "connex subtree property violated for Tn=" + cl.connexSubtree() + " O=" + cl.outputVars()
                        + "\n" + cl.joinTree().pretty());
        assertTrue(cl.connexSubtree().contains(cl.root()));
    }

    @Nested
    class Path {

        @Test
        void endpointsOnlyIsAcyclicButNotFreeConnex() {
            // The standard non-free-connex example: x and w joined through hidden y, z.
            // H+ gains {x,w} and becomes a 4-cycle.
            Set<Var> o = vars("x", "w");
            assertTrue(QueryClassifier.relationDominated(path(), o).isEmpty());
            assertFalse(QueryClassifier.freeConnex(path(), o));
            assertTrue(QueryClassifier.freeConnexJoinTree(path(), o).isEmpty());

            Classification cl = classified(path(), o, Kind.ACYCLIC);
            assertEquals(new HashSet<>(cl.joinTree().nodes()), new HashSet<>(cl.connexSubtree()),
                    "a general acyclic query has to join every node");
            assertFalse(cl.satisfiesConnexProperty(), "hidden y/z join variables violate the property");
            // fallback = the tree GYO produces, root included
            assertEquals(GyoReduction.decompose(path()).orElseThrow().root().edge().id(), cl.root().edge().id());
        }

        @Test
        void twoHiddenHopsApartIsNotFreeConnexEither() {
            // {x,z} + the path: R3 is an ear, then {x,y},{y,z},{x,z} is a triangle.
            assertFalse(QueryClassifier.freeConnex(path(), vars("x", "z")));
            classified(path(), vars("x", "z"), Kind.ACYCLIC);
        }

        @Test
        void oneEdgeIsRelationDominated() {
            Set<Var> o = vars("x", "y");
            assertEquals(0, QueryClassifier.relationDominated(path(), o).orElseThrow().id());
            assertTrue(QueryClassifier.freeConnex(path(), o), "relation-dominated implies free-connex");

            Classification cl = classified(path(), o, Kind.RELATION_DOMINATED);
            assertEquals(0, cl.root().edge().id(), "the dominating relation becomes the root");
            assertEquals(Set.of(cl.root()), cl.connexSubtree());
            assertConnex(cl);
        }

        @Test
        void middleEdgeAsRootKeepsTheTreeValid() {
            Classification cl = classified(path(), vars("y", "z"), Kind.RELATION_DOMINATED);
            assertEquals(1, cl.root().edge().id());
            assertEquals(2, cl.root().children().size(), "re-rooting at R2 hangs R1 and R3 below it");
            assertConnex(cl);
        }

        @Test
        void prefixIsFreeConnexButNotDominated() {
            Set<Var> o = vars("x", "y", "z");
            assertTrue(QueryClassifier.relationDominated(path(), o).isEmpty());
            assertTrue(QueryClassifier.freeConnex(path(), o));

            Classification cl = classified(path(), o, Kind.FREE_CONNEX);
            assertConnex(cl);
            assertTrue(cl.connexSubtree().contains(cl.joinTree().node(0)));
            assertTrue(cl.connexSubtree().contains(cl.joinTree().node(1)));
        }

        @Test
        void allVariablesIsFreeConnexWithEveryNodeInTn() {
            Classification cl = classified(path(), vars("x", "y", "z", "w"), Kind.FREE_CONNEX);
            assertConnex(cl);
            assertEquals(new HashSet<>(cl.joinTree().nodes()), new HashSet<>(cl.connexSubtree()));
        }
    }

    @Nested
    class Star {

        @Test
        void leavesOnlyIsAcyclicButNotFreeConnex() {
            // All leaves output, centre hidden: H+ = star ∪ {a,b,c} has no ear.
            Set<Var> o = vars("a", "b", "c");
            assertFalse(QueryClassifier.freeConnex(star(), o));
            Classification cl = classified(star(), o, Kind.ACYCLIC);
            assertFalse(cl.satisfiesConnexProperty());
        }

        @Test
        void centreIsRelationDominatedByTheFirstArm() {
            Classification cl = classified(star(), vars("x"), Kind.RELATION_DOMINATED);
            assertEquals(0, cl.root().edge().id());
            assertNull(cl.root().parent());
            assertConnex(cl);
        }

        @Test
        void centreAndTwoLeavesIsFreeConnex() {
            Set<Var> o = vars("x", "a", "b");
            assertTrue(QueryClassifier.relationDominated(star(), o).isEmpty());
            assertTrue(QueryClassifier.freeConnex(star(), o));
            Classification cl = classified(star(), o, Kind.FREE_CONNEX);
            assertConnex(cl);
            assertTrue(cl.connexSubtree().contains(cl.joinTree().node(0)));
            assertTrue(cl.connexSubtree().contains(cl.joinTree().node(1)));
        }

        @Test
        void twoLeavesWithoutCentreIsNotFreeConnex() {
            // {a,b} + star: R3 is an ear, then {x,a},{x,b},{a,b} is a triangle.
            assertFalse(QueryClassifier.freeConnex(star(), vars("a", "b")));
            classified(star(), vars("a", "b"), Kind.ACYCLIC);
        }
    }

    @Nested
    class Snowflake {

        @Test
        void outerLeavesOnlyIsNotFreeConnex() {
            Set<Var> o = vars("a1", "b1", "c1");
            assertFalse(QueryClassifier.freeConnex(snowflake(), o));
            classified(snowflake(), o, Kind.ACYCLIC);
        }

        @Test
        void innerRingOnlyIsNotFreeConnex() {
            // the outer hops are ears, what remains is the star-with-hidden-centre case
            assertFalse(QueryClassifier.freeConnex(snowflake(), vars("a", "b", "c")));
            classified(snowflake(), vars("a", "b", "c"), Kind.ACYCLIC);
        }

        @Test
        void centreAndOuterLeafIsNotFreeConnex() {
            // x and a1 joined through hidden a: a triangle {x,a},{a,a1},{x,a1} remains
            assertFalse(QueryClassifier.freeConnex(snowflake(), vars("x", "a1")));
            classified(snowflake(), vars("x", "a1"), Kind.ACYCLIC);
        }

        @Test
        void oneArmPlusCentreIsFreeConnexAcrossFourVariables() {
            Set<Var> o = vars("x", "a", "a1", "b");
            assertTrue(QueryClassifier.relationDominated(snowflake(), o).isEmpty());
            Classification cl = classified(snowflake(), o, Kind.FREE_CONNEX);
            assertConnex(cl);
            // the arm edges carrying O must be in Tn; the c-arm's outer hop must not be
            for (int id : new int[] {0, 1, 2}) {
                assertTrue(cl.connexSubtree().contains(cl.joinTree().node(id)), "e" + id + " carries O");
            }
            assertFalse(cl.connexSubtree().contains(cl.joinTree().node(5)),
                    "e5 {c,c1} shares nothing with O and hangs below Tn");
        }

        @Test
        void singleArmIsRelationDominated() {
            Classification cl = classified(snowflake(), vars("a", "a1"), Kind.RELATION_DOMINATED);
            assertEquals(1, cl.root().edge().id());
            assertConnex(cl);
        }
    }

    @Nested
    class RelationDominatedBound {

        @Test
        void fourOutputVariablesAreNeverRelationDominated() {
            // A hyperedge is a triple pattern, so it has at most 3 variables.
            assertTrue(QueryClassifier.relationDominated(path(), vars("x", "y", "z", "w")).isEmpty());
            assertTrue(QueryClassifier.relationDominated(star(), vars("x", "a", "b", "c")).isEmpty());
            assertTrue(QueryClassifier.relationDominated(snowflake(), vars("x", "a", "b", "c")).isEmpty());

            // even a fully variable triple ?s ?p ?o tops out at |O| = 3
            QueryHypergraph spo = hg(t("?s", "?p", "?o"), t("?o", "q", "?t"));
            assertEquals(0, QueryClassifier.relationDominated(spo, vars("s", "p", "o")).orElseThrow().id());
            assertTrue(QueryClassifier.relationDominated(spo, vars("s", "p", "o", "t")).isEmpty());
            assertEquals(Kind.FREE_CONNEX, QueryClassifier.classify(spo, vars("s", "p", "o", "t")).orElseThrow().kind());
        }

        @Test
        void outputVariablesOutsideTheBgpAreIgnored() {
            // O is intersected with the BGP's variables: ?foreign cannot be produced here.
            Classification cl = classified(path(), vars("x", "y", "foreign"), Kind.RELATION_DOMINATED);
            assertEquals(vars("x", "y"), cl.outputVars());
        }
    }

    @Nested
    class Degenerate {

        @Test
        void cyclicBgpIsNotClassified() {
            QueryHypergraph triangle = hg(t("?a", "r", "?b"), t("?b", "r", "?c"), t("?c", "r", "?a"));
            assertTrue(QueryClassifier.classify(triangle, vars("a")).isEmpty());
            assertFalse(QueryClassifier.freeConnex(triangle, vars("a", "b", "c")),
                    "free-connex requires H itself to be acyclic");
            assertTrue(QueryClassifier.freeConnexJoinTree(triangle, vars("a", "b", "c")).isEmpty());
        }

        @Test
        void emptyOutputIsRelationDominatedByTheFirstEdge() {
            Classification cl = classified(path(), Set.of(), Kind.RELATION_DOMINATED);
            assertEquals(0, cl.root().edge().id());
            assertConnex(cl);
        }

        @Test
        void emptyBgp() {
            Classification cl = QueryClassifier.classify(hg(), vars("x")).orElseThrow();
            assertEquals(Kind.FREE_CONNEX, cl.kind());
            assertNull(cl.root());
            assertTrue(cl.connexSubtree().isEmpty());
            assertTrue(cl.satisfiesConnexProperty());
        }

        @Test
        void disconnectedComponentsStayInOneTree() {
            QueryHypergraph h = hg(t("?a", "p", "?b"), t("?c", "q", "?d"));
            Classification cl = classified(h, vars("a", "d"), Kind.FREE_CONNEX);
            assertConnex(cl);
            assertEquals(2, cl.connexSubtree().size(), "both components carry an output variable");
        }

        @Test
        void connexCheckRejectsAWrongSubtree() {
            Classification cl = classified(path(), vars("x", "y", "z"), Kind.FREE_CONNEX);
            JoinTree tree = cl.joinTree();
            assertFalse(QueryClassifier.satisfiesConnexProperty(tree, Set.of(), cl.outputVars()),
                    "Tn must contain the root");
            assertFalse(QueryClassifier.satisfiesConnexProperty(tree, Set.of(tree.root()), cl.outputVars()),
                    "the root alone does not cover O");
            // the free-connex tree of the path is a chain; root + its grandchild leave a gap
            JoinTree.Node deepest = tree.root();
            while (!deepest.children().isEmpty()) deepest = deepest.children().get(0);
            assertSame(tree.root(), deepest.parent().parent(), "expected a chain of three:\n" + tree.pretty());
            assertFalse(QueryClassifier.satisfiesConnexProperty(tree, Set.of(tree.root(), deepest), cl.outputVars()),
                    "Tn must be connected");
            // the full chain with a non-output variable on an inner edge fails the intersection rule
            assertFalse(QueryClassifier.satisfiesConnexProperty(tree, new LinkedHashSet<>(tree.nodes()), vars("x", "z")),
                    "y is shared inside Tn but not output");
        }
    }

    /**
     * Randomised consistency check over random acyclic BGPs and random O: every
     * classification yields a running-intersection join tree over all edges, the
     * three predicates agree with the class picked, and whenever the query is
     * free-connex the produced tree carries a verified connex subtree.
     */
    @Test
    void randomAcyclicBgpsAreClassifiedConsistently() {
        long seed = 20260913L;
        int freeConnex = 0, dominated = 0, general = 0;
        for (int round = 0; round < 200; round++) {
            Random rnd = new Random(seed + round);
            List<Triple> shape = randomAcyclicShape(rnd);
            QueryHypergraph h = hg(shape);
            List<Var> all = new ArrayList<>(h.vertices());
            Set<Var> o = new LinkedHashSet<>();
            for (Var v : all) if (rnd.nextBoolean()) o.add(v);

            Optional<QueryHypergraph.Hyperedge> dom = QueryClassifier.relationDominated(h, o);
            boolean fc = QueryClassifier.freeConnex(h, o);
            Optional<Classification> c = QueryClassifier.classify(h, o);
            assertTrue(c.isPresent(), "shape is acyclic by construction: " + shape);
            Classification cl = c.get();

            String ctx = "shape=" + shape + " O=" + o + "\n" + cl.joinTree().pretty();
            assertEquals(h.edges().size(), cl.joinTree().size(), ctx);
            assertTrue(cl.joinTree().satisfiesRunningIntersection(), ctx);
            assertSame(cl.joinTree().root(), cl.root(), ctx);
            assertNotNull(cl.root(), ctx);

            if (o.size() >= 4) assertTrue(dom.isEmpty(), "|O| >= 4 can never be relation-dominated: " + ctx);

            switch (cl.kind()) {
                case RELATION_DOMINATED -> {
                    assertTrue(dom.isPresent(), ctx);
                    assertTrue(fc, "relation-dominated implies free-connex: " + ctx);
                    assertEquals(dom.get().id(), cl.root().edge().id(), ctx);
                    assertEquals(Set.of(cl.root()), cl.connexSubtree(), ctx);
                    assertTrue(cl.satisfiesConnexProperty(), ctx);
                    dominated++;
                }
                case FREE_CONNEX -> {
                    assertTrue(dom.isEmpty(), ctx);
                    assertTrue(fc, ctx);
                    assertTrue(cl.satisfiesConnexProperty(), ctx);
                    freeConnex++;
                }
                case ACYCLIC -> {
                    assertTrue(dom.isEmpty(), ctx);
                    assertFalse(fc, ctx);
                    assertEquals(new HashSet<>(cl.joinTree().nodes()), new HashSet<>(cl.connexSubtree()), ctx);
                    assertFalse(cl.satisfiesConnexProperty(), ctx);
                    general++;
                }
            }
        }
        assertTrue(dominated > 0 && freeConnex > 0 && general > 0,
                "the random suite must exercise every class: " + dominated + "/" + freeConnex + "/" + general);
    }

    /**
     * Grows a Berge-acyclic BGP of 2..7 triples: each new triple attaches an
     * already-used variable to a fresh variable or a constant, so the join
     * structure is a tree by construction (same generator as GyoReductionTest).
     */
    private static List<Triple> randomAcyclicShape(Random rnd) {
        List<Var> vars = new ArrayList<>();
        vars.add(Var.alloc("v0"));
        vars.add(Var.alloc("v1"));
        List<Triple> triples = new ArrayList<>();
        triples.add(Triple.create(vars.get(0), pred(rnd), vars.get(1)));

        int size = 2 + rnd.nextInt(6);
        int nextVar = 2;
        for (int i = 1; i < size; i++) {
            Var anchor = vars.get(rnd.nextInt(vars.size()));
            Node other;
            if (rnd.nextInt(4) == 0) {
                other = NodeFactory.createURI("http://example.org/c" + rnd.nextInt(8));
            } else {
                Var v = Var.alloc("v" + nextVar++);
                vars.add(v);
                other = v;
            }
            Node p = pred(rnd);
            triples.add(rnd.nextBoolean()
                    ? Triple.create(anchor, p, other)
                    : Triple.create(other, p, anchor));
        }
        return triples;
    }

    private static Node pred(Random rnd) {
        return NodeFactory.createURI("http://example.org/p" + rnd.nextInt(3));
    }
}
