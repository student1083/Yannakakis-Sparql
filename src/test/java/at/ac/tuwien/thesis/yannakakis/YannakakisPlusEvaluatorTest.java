package at.ac.tuwien.thesis.yannakakis;

import at.ac.tuwien.thesis.yannakakis.QueryClassifier.Classification;
import at.ac.tuwien.thesis.yannakakis.QueryClassifier.Kind;
import at.ac.tuwien.thesis.yannakakis.YannakakisPlusEvaluator.Answer;
import at.ac.tuwien.thesis.yannakakis.YannakakisPlusEvaluator.Reduced;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link YannakakisPlusEvaluator} against the classical three-pass {@link YannakakisEvaluator}
 * as oracle: for the same join tree and relations, π_O of the classical full join (a bag:
 * projection sums multiplicities) must equal what the two rounds produce, multiplicities
 * included. On top of that the relation-dominated claim of Chapter 3 (Theorem 3.11) is
 * asserted wherever it applies — exactly one node remains and zero semijoins were performed —
 * and the free-connex claim of Algorithm 2: the general-merge branch is never reached.
 *
 * <p>The corpus of {@link OutputVariableSafetyTest} is reused at the BGP level: each query is
 * compiled, O per BGP comes from {@link AlgebraContextAnalyzer} exactly as in the executor,
 * the BGP's relations are matched against the corpus graph, and both evaluators run on the
 * classified tree. (The end-to-end run of the same corpus through the executor, against stock
 * ARQ, is {@link OutputVariableSafetyTest} itself.)
 */
class YannakakisPlusEvaluatorTest {

    @BeforeEach
    void enableInvariantChecks() { YannakakisPlusEvaluator.setInvariantChecks(true); }

    @AfterEach
    void disableInvariantChecks() { YannakakisPlusEvaluator.setInvariantChecks(false); }

    private static Node node(String token) {
        if (token.startsWith("?")) return Var.alloc(token.substring(1));
        return NodeFactory.createURI("http://example.org/" + token);
    }
    private static Triple t(String s, String p, String o) {
        return Triple.create(node(s), node(p), node(o));
    }
    private static QueryHypergraph hg(List<Triple> triples) {
        BasicPattern bgp = new BasicPattern();
        for (Triple tr : triples) bgp.add(tr);
        return QueryHypergraph.fromBasicPattern(bgp);
    }
    private static Set<Var> vars(String... names) {
        Set<Var> s = new LinkedHashSet<>();
        for (String n : names) s.add(Var.alloc(n));
        return s;
    }
    private static Map<Integer, Relation> byId(List<Relation> rels) {
        Map<Integer, Relation> m = new HashMap<>();
        for (int i = 0; i < rels.size(); i++) m.put(i, rels.get(i));
        return m;
    }

    /** Oracle: the classical evaluator's full join, projected onto O afterwards. */
    private static Relation oracle(Classification cl, Map<Integer, Relation> rels) {
        return YannakakisEvaluator.evaluate(cl.joinTree(), rels).project(cl.outputVars());
    }

    private static Classification classify(QueryHypergraph h, Set<Var> o, Kind expected) {
        Classification cl = QueryClassifier.classify(h, o).orElseThrow();
        assertEquals(expected, cl.kind(), cl.joinTree().pretty());
        return cl;
    }

    /** The Chapter 3 claim: one node left, no semijoin, and that node is the answer. */
    private static void assertTheorem311(Classification cl, Reduced red, Map<Integer, Relation> rels, String ctx) {
        assertEquals(Kind.RELATION_DOMINATED, cl.kind(), ctx);
        assertTrue(red.isSingleNode(), "reduced tree must be a single node: " + ctx + "\n" + red.tree().pretty());
        assertEquals(1, red.tree().size(), ctx);
        assertSame(cl.root().edge(), red.tree().root().edge(), "the dominating relation must be what remains: " + ctx);
        assertEquals(0, red.semijoins(), "relation-dominated queries need no semijoin: " + ctx);
        assertEquals(cl.joinTree().size() - 1, red.absorptions(), "every other node is absorbed: " + ctx);
        assertEquals(oracle(cl, rels), red.singleNodeAnswer(), ctx);
        assertEquals(oracle(cl, rels), YannakakisPlusEvaluator.evaluateRelationDominated(cl.joinTree(), cl.outputVars(), rels).relation(), ctx);
    }

    /**
     * What every evaluation must satisfy, whatever the class: the first round's structural
     * invariants, then both rounds (invariant checks on) equal to the oracle bag, with the
     * general-merge branch untouched unless the query is general acyclic.
     */
    private static Reduced checkedFirstRound(Classification cl, Map<Integer, Relation> rels, String ctx) {
        Reduced red = YannakakisPlusEvaluator.firstRound(cl.joinTree(), cl.outputVars(), rels);
        assertTrue(red.tree().size() >= 1 && red.tree().size() <= cl.joinTree().size(), ctx);
        assertSame(cl.root().edge(), red.tree().root().edge(), "the root is never absorbed: " + ctx);
        assertTrue(red.tree().satisfiesRunningIntersection(), "reduced tree lost running intersection: " + ctx);
        assertEquals(cl.joinTree().size() - 1, red.semijoins() + red.absorptions(), "each non-root node is handled once: " + ctx);
        assertEquals(red.tree().size(), red.relations().size(), ctx);
        for (JoinTree.Node n : red.tree().nodes()) {
            Set<Var> schema = red.relation(n).schema();
            assertTrue(n.vars().containsAll(schema), "projection only removes columns: " + ctx);
            for (Var v : n.vars()) {
                if (cl.outputVars().contains(v)) assertTrue(schema.contains(v), "output variable " + v + " projected away: " + ctx);
            }
        }
        Relation expected = oracle(cl, rels);
        boolean reducibleOnly = cl.kind() != Kind.ACYCLIC;
        Answer answer = YannakakisPlusEvaluator.secondRound(red, reducibleOnly);
        assertEquals(expected, answer.relation(), "two rounds differ from the classical evaluator: " + ctx);
        assertEquals(red.tree().size() - 1, answer.merges() + answer.generalMerges(), "one merge per remaining non-root node: " + ctx);
        if (reducibleOnly) assertEquals(0, answer.generalMerges(), "free-connex: the else branch is never taken: " + ctx);
        assertEquals(expected, YannakakisPlusEvaluator.evaluate(cl, rels).relation(), ctx);
        if (cl.kind() == Kind.RELATION_DOMINATED) assertTheorem311(cl, red, rels, ctx);
        return red;
    }

    // ---- fixed shapes ---------------------------------------------------------

    /** R0(a,b) R1(b,c) R2(c,d), with a dangling tuple in each end relation. */
    private static final List<Triple> PATH = List.of(t("?a", "r", "?b"), t("?b", "r", "?c"), t("?c", "r", "?d"));
    private static List<Relation> pathRelations() {
        return List.of(
                Relation.builder("a", "b").row("1", "2").row("9", "8").row("5", "2").build(),   // 9-8 dangles
                Relation.builder("b", "c").row("2", "3").row("2", "6").build(),
                Relation.builder("c", "d").row("3", "4").row("7", "7").build());                 // 7-7 dangles
    }

    @Nested
    class RelationDominated {

        @Test
        void firstEdgeDominatesAndAbsorbsTheWholeChain() {
            Classification cl = classify(hg(PATH), vars("a", "b"), Kind.RELATION_DOMINATED);
            Map<Integer, Relation> rels = byId(pathRelations());
            Reduced red = checkedFirstRound(cl, rels, "path O={a,b}");
            assertTheorem311(cl, red, rels, "path O={a,b}");
            // only the chains 1-2-3-4 and 5-2-3-4 survive: {a=1,b=2} and {a=5,b=2}
            assertEquals(Relation.builder("a", "b").row("1", "2").row("5", "2").build(), red.singleNodeAnswer());
        }

        @Test
        void middleEdgeDominatesWithTwoChildren() {
            Classification cl = classify(hg(PATH), vars("b", "c"), Kind.RELATION_DOMINATED);
            assertEquals(2, cl.root().children().size());
            Map<Integer, Relation> rels = byId(pathRelations());
            Reduced red = checkedFirstRound(cl, rels, "path O={b,c}");
            assertTheorem311(cl, red, rels, "path O={b,c}");
            // two full rows (1-2-3-4 and 5-2-3-4) project onto the same {b=2,c=3}: multiplicity 2
            assertEquals(Relation.builder("b", "c").rowTimes(2, "2", "3").build(), red.singleNodeAnswer());
        }

        @Test
        void singleOutputVariableProjectsTheDominatingRelation() {
            Classification cl = classify(hg(PATH), vars("c"), Kind.RELATION_DOMINATED);
            Map<Integer, Relation> rels = byId(pathRelations());
            Reduced red = checkedFirstRound(cl, rels, "path O={c}");
            assertTheorem311(cl, red, rels, "path O={c}");
            assertEquals(Relation.builder("c").rowTimes(2, "3").build(), red.singleNodeAnswer());
        }

        @Test
        void starCentreIsDominated() {
            List<Triple> star = List.of(t("?x", "p1", "?a"), t("?x", "p2", "?b"), t("?x", "p3", "?c"));
            Map<Integer, Relation> rels = byId(List.of(
                    Relation.builder("x", "a").row("u", "a1").row("v", "a2").row("w", "a3").build(),
                    Relation.builder("x", "b").row("u", "b1").row("w", "b2").build(),
                    Relation.builder("x", "c").row("u", "c1").row("v", "c2").row("u", "c3").build()));
            Classification cl = classify(hg(star), vars("x"), Kind.RELATION_DOMINATED);
            Reduced red = checkedFirstRound(cl, rels, "star O={x}");
            assertTheorem311(cl, red, rels, "star O={x}");
            // u-a1-b1-c1 and u-a1-b1-c3: x=u twice
            assertEquals(Relation.builder("x").rowTimes(2, "u").build(), red.singleNodeAnswer());
        }

        @Test
        void emptyOutputIsDominatedAndYieldsUnitOrEmpty() {
            Classification cl = classify(hg(PATH), Set.of(), Kind.RELATION_DOMINATED);
            Reduced red = checkedFirstRound(cl, byId(pathRelations()), "path O={}");
            // π_∅ of the two full rows: the empty tuple with multiplicity 2
            assertEquals(1, red.singleNodeAnswer().rowCount());
            assertEquals(2, red.singleNodeAnswer().bagSize());
            assertEquals(Relation.unit(), red.singleNodeAnswer().distinct());

            List<Relation> dead = new ArrayList<>(pathRelations());
            dead.set(1, Relation.builder("b", "c").build());                    // no rows: nothing joins
            Reduced empty = checkedFirstRound(cl, byId(dead), "path O={} dead middle");
            assertEquals(0, empty.singleNodeAnswer().rowCount());
        }

        @Test
        void disconnectedComponentIsAbsorbedAsACrossProductFactor() {
            List<Triple> two = List.of(t("?a", "p", "?b"), t("?c", "q", "?d"));
            Relation left = Relation.builder("a", "b").row("1", "2").row("3", "4").build();
            Classification cl = classify(hg(two), vars("a"), Kind.RELATION_DOMINATED);

            Reduced red = checkedFirstRound(cl, byId(List.of(left, Relation.builder("c", "d").row("5", "6").build())), "two components");
            assertEquals(Relation.builder("a").row("1").row("3").build(), red.singleNodeAnswer());

            Reduced killed = checkedFirstRound(cl, byId(List.of(left, Relation.builder("c", "d").build())), "two components, right empty");
            assertEquals(0, killed.singleNodeAnswer().rowCount());
        }

        @Test
        void wrongRootIsRejectedInsteadOfSilentlyMisreporting() {
            // A GYO tree of the path is rooted wherever GYO stopped; pick O = the variables of a
            // non-root node, which is relation-dominated but not rooted at the dominating relation.
            JoinTree gyo = GyoReduction.decompose(hg(PATH)).orElseThrow();
            JoinTree.Node other = gyo.nodes().stream().filter(n -> n != gyo.root()).findFirst().orElseThrow();
            Set<Var> o = new LinkedHashSet<>(other.vars());
            assertTrue(QueryClassifier.relationDominated(hg(PATH), o).isPresent());
            assertThrows(IllegalArgumentException.class,
                    () -> YannakakisPlusEvaluator.evaluateRelationDominated(gyo, o, byId(pathRelations())));
        }
    }

    @Nested
    class NotDominated {

        @Test
        void endpointsNeedSemijoinsAndKeepEveryNode() {
            // O = {a,d}: no absorption is possible (each leaf's output variable is not in its parent).
            Classification cl = classify(hg(PATH), vars("a", "d"), Kind.ACYCLIC);
            Reduced red = checkedFirstRound(cl, byId(pathRelations()), "path O={a,d}");
            assertEquals(3, red.tree().size());
            assertEquals(2, red.semijoins());
            assertEquals(0, red.absorptions());
            // the hidden b and c are dropped one merge at a time; no general merge is needed
            Answer answer = YannakakisPlusEvaluator.secondRound(red, true);
            assertEquals(Relation.builder("a", "d").row("1", "4").row("5", "4").build(), answer.relation());
            assertEquals(2, answer.merges());
            assertEquals(0, answer.generalMerges());
            assertThrows(IllegalStateException.class, red::singleNodeAnswer);
        }

        @Test
        void allVariablesIsFreeConnexAndProjectsNothingAway() {
            Classification cl = classify(hg(PATH), vars("a", "b", "c", "d"), Kind.FREE_CONNEX);
            Map<Integer, Relation> rels = byId(pathRelations());
            Reduced red = checkedFirstRound(cl, rels, "path O=all");
            assertEquals(3, red.tree().size());
            assertEquals(2, red.semijoins());
            assertEquals(YannakakisEvaluator.evaluate(cl.joinTree(), rels), YannakakisPlusEvaluator.secondRound(red, true).relation());
        }

        @Test
        void hiddenJoinVariableIsProjectedAwayAfterTheSemijoin() {
            // O = {a,c}: the leaf R0 keeps a (output) and b (join variable); R2 is absorbed into R1.
            Classification cl = classify(hg(PATH), vars("a", "c"), Kind.ACYCLIC);
            Reduced red = checkedFirstRound(cl, byId(pathRelations()), "path O={a,c}");
            assertEquals(Relation.builder("a", "c").row("1", "3").row("5", "3").build(),
                    YannakakisPlusEvaluator.secondRound(red, false).relation());
            for (JoinTree.Node n : red.tree().nodes()) {
                Set<Var> schema = red.relation(n).schema();
                assertFalse(schema.contains(Var.alloc("d")), "d is neither output nor a join variable of a remaining node");
            }
        }

        @Test
        void emptyBgpIsTheUnitRelation() {
            Reduced red = YannakakisPlusEvaluator.firstRound(JoinTree.empty(), vars("x"), Map.of());
            assertEquals(0, red.tree().size());
            assertEquals(0, red.semijoins());
            assertEquals(Relation.unit(), YannakakisPlusEvaluator.secondRound(red, true).relation());
        }

        @Test
        void hiddenStarCentreNeedsTheGeneralMerge() {
            // O = all leaves, centre x hidden: not free-connex. No merge can drop x while another
            // arm still joins on it, so the else branch is the only way to finish.
            List<Triple> star = List.of(t("?x", "p1", "?a"), t("?x", "p2", "?b"), t("?x", "p3", "?c"));
            Map<Integer, Relation> rels = byId(List.of(
                    Relation.builder("x", "a").row("u", "a1").row("u", "a2").row("v", "a3").build(),
                    Relation.builder("x", "b").row("u", "b1").row("v", "b2").row("w", "b3").build(),
                    Relation.builder("x", "c").row("u", "c1").row("v", "c2").row("v", "c3").build()));
            Classification cl = classify(hg(star), vars("a", "b", "c"), Kind.ACYCLIC);
            Reduced red = checkedFirstRound(cl, rels, "star O={a,b,c}");
            assertEquals(3, red.tree().size());

            Answer answer = YannakakisPlusEvaluator.secondRound(red, false);
            assertEquals(oracle(cl, rels), answer.relation());
            assertEquals(4, answer.relation().bagSize(), "u: 2×1×1, v: 1×1×2");
            assertEquals(2, answer.merges() + answer.generalMerges());
            assertEquals(1, answer.generalMerges(), "the first merge must keep x for the third arm; the last one may drop it");

            // Declaring it free-connex is caught when the invariant checks are on ...
            assertTrue(YannakakisPlusEvaluator.invariantChecks());
            assertThrows(IllegalStateException.class, () -> YannakakisPlusEvaluator.secondRound(red, true));
            // ... and silently tolerated (same answer) when they are off, as in a benchmark.
            YannakakisPlusEvaluator.setInvariantChecks(false);
            assertEquals(answer.relation(), YannakakisPlusEvaluator.secondRound(red, true).relation());
        }

        @Test
        void multiplicitiesSurviveTheSecondRound() {
            // path a-b-c-d, O = {a,d}, with fan-out in the hidden middle: a=1 reaches d=4 by two
            // different (b,c) routes, so the bag has {a=1,d=4} twice.
            Map<Integer, Relation> rels = byId(List.of(
                    Relation.builder("a", "b").row("1", "2").row("1", "9").build(),
                    Relation.builder("b", "c").row("2", "3").row("9", "3").build(),
                    Relation.builder("c", "d").row("3", "4").build()));
            Classification cl = classify(hg(PATH), vars("a", "d"), Kind.ACYCLIC);
            checkedFirstRound(cl, rels, "path O={a,d} fan-out");
            assertEquals(Relation.builder("a", "d").rowTimes(2, "1", "4").build(),
                    YannakakisPlusEvaluator.evaluate(cl, rels).relation());
        }
    }

    // ---- the OutputVariableSafetyTest corpus at the BGP level -----------------------

    private static final String NS = "http://example.org/";

    private static List<OpBGP> bgps(Op root) {
        List<OpBGP> found = new ArrayList<>();
        OpWalker.walk(root, new OpVisitorBase() {
            @Override public void visit(OpBGP opBGP) { found.add(opBGP); }
        });
        return found;
    }

    /** One classified, materialised BGP of a corpus query. */
    private record CorpusBgp(String query, OpBGP bgp, Classification cl, Map<Integer, Relation> rels) {}

    /**
     * Every acyclic BGP of one corpus query, with O from the analyzer (as the executor looks
     * it up) and relations matched against the corpus graph.
     */
    private static List<CorpusBgp> corpusBgps(OutputVariableSafetyTest.Case c, Graph g) {
        List<CorpusBgp> out = new ArrayList<>();
        Op op = Algebra.compile(QueryFactory.create(c.sparql()));
        AlgebraContextAnalyzer.Analysis analysis = AlgebraContextAnalyzer.analyze(op);
        for (OpBGP bgp : bgps(op)) {
            Set<Var> o = analysis.outputVarsOrAll(bgp);
            Optional<Classification> cl = QueryClassifier.classify(QueryHypergraph.fromBasicPattern(bgp.getPattern()), o);
            if (cl.isEmpty()) continue;                       // cyclic: the executor delegates these
            Map<Integer, Relation> rels = new HashMap<>();
            int id = 0;
            for (Triple t : bgp.getPattern()) rels.put(id++, matchTriple(g, t));
            out.add(new CorpusBgp(c.name(), bgp, cl.get(), rels));
        }
        return out;
    }

    static Stream<OutputVariableSafetyTest.Case> corpus() { return OutputVariableSafetyTest.CORPUS.stream(); }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void corpusBgpsMatchTheClassicalEvaluator(OutputVariableSafetyTest.Case c) {
        Graph g = OutputVariableSafetyTest.corpusModel().getGraph();
        for (CorpusBgp cb : corpusBgps(c, g)) {
            checkedFirstRound(cb.cl(), cb.rels(), c.name() + " " + cb.bgp() + " O=" + cb.cl().outputVars());
        }
    }

    @Test
    void corpusExercisesEveryClassAndTheTheorem() {
        Graph g = OutputVariableSafetyTest.corpusModel().getGraph();
        List<CorpusBgp> all = new ArrayList<>();
        for (OutputVariableSafetyTest.Case c : OutputVariableSafetyTest.CORPUS) all.addAll(corpusBgps(c, g));

        int dominated = 0, freeConnex = 0, general = 0;
        for (CorpusBgp cb : all) {
            switch (cb.cl().kind()) {
                case RELATION_DOMINATED -> {
                    Reduced red = YannakakisPlusEvaluator.firstRound(cb.cl().joinTree(), cb.cl().outputVars(), cb.rels());
                    assertTheorem311(cb.cl(), red, cb.rels(), cb.query() + " " + cb.bgp());
                    dominated++;
                }
                case FREE_CONNEX -> freeConnex++;
                case ACYCLIC -> general++;
            }
        }
        assertTrue(dominated >= 10, "expected many relation-dominated corpus BGPs, got " + dominated);
        assertTrue(freeConnex >= 10, "expected many free-connex corpus BGPs, got " + freeConnex);
        assertTrue(general >= 1, "expected at least one general acyclic corpus BGP, got " + general);
    }

    @Test
    void freeConnexCorpusBgpsNeverReachTheElseBranch() {
        // With the invariant checks on, reaching the general merge on a free-connex query throws;
        // additionally the counter must read zero. Relation-dominated BGPs never enter round two.
        Graph g = OutputVariableSafetyTest.corpusModel().getGraph();
        int checked = 0;
        for (OutputVariableSafetyTest.Case c : OutputVariableSafetyTest.CORPUS) {
            for (CorpusBgp cb : corpusBgps(c, g)) {
                if (cb.cl().kind() != Kind.FREE_CONNEX) continue;
                Reduced red = YannakakisPlusEvaluator.firstRound(cb.cl().joinTree(), cb.cl().outputVars(), cb.rels());
                Answer answer = YannakakisPlusEvaluator.secondRound(red, true);
                assertEquals(0, answer.generalMerges(), c.name() + " " + cb.bgp());
                assertEquals(red.tree().size() - 1, answer.merges(), c.name() + " " + cb.bgp());
                checked++;
            }
        }
        assertTrue(checked >= 10, "expected many free-connex corpus BGPs, checked " + checked);
    }

    /** Same matching as the executor's private helper: variables bind, repeated variables must agree. */
    private static Relation matchTriple(Graph g, Triple pat) {
        Node s = pat.getSubject(), p = pat.getPredicate(), o = pat.getObject();
        Set<Var> schema = new LinkedHashSet<>();
        for (Node n : List.of(s, p, o)) if (Var.isVar(n)) schema.add(Var.alloc(n));

        Set<Map<Var, Node>> rows = new HashSet<>();
        ExtendedIterator<Triple> it = g.find(any(s), any(p), any(o));
        try {
            while (it.hasNext()) {
                Triple m = it.next();
                Map<Var, Node> row = new HashMap<>();
                if (bind(row, s, m.getSubject()) && bind(row, p, m.getPredicate()) && bind(row, o, m.getObject())) {
                    rows.add(row);
                }
            }
        } finally {
            it.close();
        }
        return Relation.fromRows(schema, rows);
    }
    private static Node any(Node n) { return Var.isVar(n) ? Node.ANY : n; }
    private static boolean bind(Map<Var, Node> row, Node patNode, Node value) {
        if (!Var.isVar(patNode)) return true;
        Node prev = row.putIfAbsent(Var.alloc(patNode), value);
        return prev == null || prev.equals(value);
    }

    // ---- randomised ------------------------------------------------------------------

    /**
     * Random acyclic BGPs, random O and random small relations, diffed against the classical
     * evaluator; the theorem is asserted on every relation-dominated draw. Shape and O are
     * drawn exactly as in {@code QueryClassifierTest} (same seed), so all three classes occur.
     */
    @Test
    void randomAcyclicBgpsMatchTheClassicalEvaluator() {
        long seed = 20260913L;
        int dominated = 0, freeConnex = 0, general = 0;
        for (int round = 0; round < 200; round++) {
            Random rnd = new Random(seed + round);
            List<Triple> shape = randomAcyclicShape(rnd);
            QueryHypergraph h = hg(shape);
            Set<Var> o = new LinkedHashSet<>();
            for (Var v : h.vertices()) if (rnd.nextBoolean()) o.add(v);
            Map<Integer, Relation> rels = new HashMap<>();
            for (QueryHypergraph.Hyperedge e : h.edges()) rels.put(e.id(), randomRelation(rnd, e.vars()));

            Classification cl = QueryClassifier.classify(h, o).orElseThrow();
            String ctx = "round " + round + " shape=" + shape + " O=" + o + "\n" + cl.joinTree().pretty();
            checkedFirstRound(cl, rels, ctx);
            switch (cl.kind()) {
                case RELATION_DOMINATED -> dominated++;
                case FREE_CONNEX -> freeConnex++;
                case ACYCLIC -> general++;
            }
        }
        assertTrue(dominated > 0 && freeConnex > 0 && general > 0,
                "the random suite must exercise every class: " + dominated + "/" + freeConnex + "/" + general);
    }

    /** 0..5 rows over a domain of four constants, so joins both hit and miss. */
    private static Relation randomRelation(Random rnd, Set<Var> schema) {
        List<Var> vars = new ArrayList<>(schema);
        Relation.Builder b = Relation.builder(vars.stream().map(Var::getVarName).toArray(String[]::new));
        int rows = rnd.nextInt(6);
        for (int i = 0; i < rows; i++) {
            String[] values = new String[vars.size()];
            for (int j = 0; j < values.length; j++) values[j] = "n" + rnd.nextInt(4);
            b.row(values);
        }
        return b.build();
    }

    /** Same generator as QueryClassifierTest / GyoReductionTest: Berge-acyclic by construction. */
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
                other = NodeFactory.createURI(NS + "c" + rnd.nextInt(8));
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
        return NodeFactory.createURI(NS + "p" + rnd.nextInt(3));
    }
}
