package at.ac.tuwien.thesis.yannakakis;

import at.ac.tuwien.thesis.yannakakis.DimensionFusion.Fused;
import at.ac.tuwien.thesis.yannakakis.DimensionFusion.Fusion;
import at.ac.tuwien.thesis.yannakakis.QueryClassifier.Classification;
import at.ac.tuwien.thesis.yannakakis.QueryClassifier.Kind;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
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
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DimensionFusion} (Wang et al. §5.1) and the estimate-aware classification
 * ({@link QueryClassifier#classify(QueryHypergraph, Set, Map)}, §5.2 heuristics), against
 * the classical {@link YannakakisEvaluator} as the bag oracle: whatever the estimates, the
 * tree they shape and the fusions they trigger must leave π_O unchanged, keep the class
 * (relation-dominated: one node, no semijoin; free-connex: no general merge), and keep
 * both structural oracles — running intersection and the connex property — satisfied.
 * The last part runs the executor end to end against stock ARQ with an estimator that
 * makes fusion fire.
 */
class DimensionFusionTest {

    private static final String NS = "http://example.org/";

    @BeforeEach
    void enableInvariantChecks() { YannakakisPlusEvaluator.setInvariantChecks(true); }

    @AfterEach
    void disableInvariantChecks() { YannakakisPlusEvaluator.setInvariantChecks(false); }

    // ---- helpers ------------------------------------------------------------------

    private static Node node(String token) {
        if (token.startsWith("?")) return Var.alloc(token.substring(1));
        return NodeFactory.createURI(NS + token);
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
    private static Map<Integer, Long> est(long... values) {
        Map<Integer, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) m.put(i, values[i]);
        return m;
    }
    private static Map<Integer, Relation> byId(List<Relation> rels) {
        Map<Integer, Relation> m = new HashMap<>();
        for (int i = 0; i < rels.size(); i++) m.put(i, rels.get(i));
        return m;
    }
    private static Set<Integer> ids(Set<JoinTree.Node> nodes) {
        Set<Integer> out = new HashSet<>();
        for (JoinTree.Node n : nodes) out.add(n.edge().id());
        return out;
    }

    /** Oracle: the classical evaluator's full join on the unfused tree, projected onto O. */
    private static Relation oracle(Classification cl, Map<Integer, Relation> rels) {
        return YannakakisEvaluator.evaluate(cl.joinTree(), rels).project(cl.outputVars());
    }

    /** {@link #checkPlan(Classification, Map, Classification, Map, String)} for a plan over the same relations. */
    private static void checkPlan(Classification original, Classification plan, Map<Integer, Relation> rels, String ctx) {
        checkPlan(original, rels, plan, rels, ctx);
    }

    /**
     * Everything a (possibly re-rooted, possibly fused) classification {@code plan} over
     * {@code planRels} must satisfy: the structural oracles, the class guarantees, and the
     * oracle bag computed on the structure-only classification over the unfused relations.
     */
    private static void checkPlan(Classification original, Map<Integer, Relation> originalRels,
                                  Classification plan, Map<Integer, Relation> planRels, String ctx) {
        Map<Integer, Relation> rels = planRels;
        assertEquals(original.kind(), plan.kind(), "the class never changes: " + ctx);
        assertEquals(original.outputVars(), plan.outputVars(), ctx);
        assertTrue(plan.joinTree().satisfiesRunningIntersection(), "running intersection lost: " + ctx + "\n" + plan.joinTree().pretty());
        assertTrue(plan.connexSubtree().contains(plan.root()), ctx);
        switch (plan.kind()) {
            case RELATION_DOMINATED -> {
                assertTrue(plan.root().vars().containsAll(plan.outputVars()), "root must dominate: " + ctx);
                assertEquals(Set.of(plan.root()), plan.connexSubtree(), ctx);
                assertTrue(plan.satisfiesConnexProperty(), ctx);
            }
            case FREE_CONNEX -> assertTrue(plan.satisfiesConnexProperty(), "connex property lost: " + ctx + "\n" + plan.joinTree().pretty());
            case ACYCLIC -> assertEquals(new HashSet<>(plan.joinTree().nodes()), new HashSet<>(plan.connexSubtree()), ctx);
        }
        Relation expected = oracle(original, originalRels);
        // evaluate() asserts Theorem 3.11 itself for relation-dominated trees, and the
        // invariant checks (on) throw on a general merge in a free-connex tree.
        YannakakisPlusEvaluator.Answer answer = YannakakisPlusEvaluator.evaluate(plan, rels);
        assertEquals(expected, answer.relation(), "π_O changed: " + ctx + "\n" + plan.joinTree().pretty());
        if (plan.kind() != Kind.ACYCLIC) assertEquals(0, answer.generalMerges(), ctx);
    }

    // ---- the paper's example --------------------------------------------------------

    /** R1(a) ⋈ R2(a,b) ⋈ R3(b): triple patterns e0 = {a}, e1 = {a,b}, e2 = {b}. */
    private static final List<Triple> PAPER = List.of(t("?a", "p1", "c1"), t("?a", "p2", "?b"), t("?b", "p3", "c3"));

    private static List<Relation> paperRelations() {
        return List.of(
                Relation.builder("a").row("n1").row("n2").row("n9").build(),
                Relation.builder("a", "b").row("n1", "m1").row("n1", "m2").row("n2", "m1").row("n3", "m3").build(),
                Relation.builder("b").row("m1").row("m3").build());
    }

    @Nested
    class PaperExample {

        private final Classification cl = QueryClassifier.classify(hg(PAPER), vars("a", "b"), est(5, 1000, 5)).orElseThrow();

        @Test void largeRelationIsTheRootAndTheSmallOnesItsLeaves() {
            assertEquals(Kind.RELATION_DOMINATED, cl.kind());
            assertEquals(1, cl.root().edge().id());
            assertEquals(2, cl.root().children().size());
        }

        @Test void smallLeavesAreFusedIntoOneCartesianProduct() {
            Fused fused = DimensionFusion.apply(cl, est(5, 1000, 5), 1.0);
            assertEquals(1, fused.fusions().size());
            Fusion f = fused.fusions().get(0);
            assertEquals(1, f.parentId());
            assertEquals(List.of(0, 2), f.leafIds(), "ties keep child order");
            assertEquals(3, f.fusedId(), "fused ids continue after the BGP's");

            JoinTree tree = fused.classification().joinTree();
            assertEquals(2, tree.size());
            assertSame(cl.root().edge(), tree.root().edge());
            JoinTree.Node fusedNode = tree.node(3);
            assertTrue(fusedNode.edge().isFused());
            assertEquals(vars("a", "b"), fusedNode.vars());
            assertSame(tree.root(), fusedNode.parent());
            assertTrue(tree.satisfiesRunningIntersection());

            Map<Integer, Relation> rels = fused.fuseRelations(byId(paperRelations()));
            assertEquals(Set.of(1, 3), rels.keySet());
            assertEquals(paperRelations().get(0).join(paperRelations().get(2)), rels.get(3), "R1 × R3, 3 × 2 = 6 rows");
            assertEquals(6, rels.get(3).rowCount());
            checkPlan(cl, byId(paperRelations()), fused.classification(), rels, "paper example");

            // exactly what the paper promises: the fused leaf is absorbed in one step
            YannakakisPlusEvaluator.Reduced red = YannakakisPlusEvaluator.firstRound(fused.classification().joinTree(), cl.outputVars(), rels);
            assertEquals(1, red.absorptions(), "one absorption instead of two");
            assertEquals(0, red.semijoins());
            assertEquals(oracle(cl, byId(paperRelations())), red.singleNodeAnswer());
        }

        @Test void thresholdIsTheProductOfTheSmallSizes() {
            assertTrue(DimensionFusion.apply(cl, est(40, 1000, 30), 1.0).isEmpty(), "30 · 40 = 1200 > 1000");
            assertEquals(1, DimensionFusion.apply(cl, est(40, 1000, 30), 0.5).fusions().size(), "θ = 0.5: 600 ≤ 1000");
            assertEquals(1, DimensionFusion.apply(cl, est(25, 1000, 40), 1.0).fusions().size(), "25 · 40 = 1000 ≤ 1000");
            assertTrue(DimensionFusion.apply(cl, est(5, 1000, 5), 41).isEmpty(), "θ = 41: 25 · 41 > 1000");
            assertEquals(1, DimensionFusion.apply(cl, est(5, 1000, 5), 40).fusions().size());
        }

        @Test void greedyPrefixSmallestFirst() {
            // a third leaf too large to join the product: e3 = {a}
            List<Triple> four = new ArrayList<>(PAPER);
            four.add(t("?a", "p4", "c4"));
            Classification c4 = QueryClassifier.classify(hg(four), vars("a", "b"), est(5, 1000, 5, 100)).orElseThrow();
            assertEquals(3, c4.root().children().size());
            Fused fused = DimensionFusion.apply(c4, est(5, 1000, 5, 100), 1.0);
            assertEquals(1, fused.fusions().size());
            assertEquals(List.of(0, 2), fused.fusions().get(0).leafIds(), "5 · 5 = 25 fits, 25 · 100 does not");
            assertEquals(3, fused.classification().joinTree().size());
        }

        @Test void switchedOffByNonFiniteOrNegativeRatio() {
            assertTrue(DimensionFusion.apply(cl, est(5, 1000, 5), Double.POSITIVE_INFINITY).isEmpty());
            assertTrue(DimensionFusion.apply(cl, est(5, 1000, 5), Double.NaN).isEmpty());
            assertTrue(DimensionFusion.apply(cl, est(5, 1000, 5), -1).isEmpty());
        }

        @Test void zeroRatioFusesEvenWithoutEstimates() {
            Classification unknown = QueryClassifier.classify(hg(PAPER), vars("a", "b"), est(0, 0, 0)).orElseThrow();
            assertTrue(DimensionFusion.apply(unknown, est(0, 0, 0), 1.0).isEmpty(), "no information, no fusion");
            assertEquals(1, DimensionFusion.apply(unknown, est(0, 0, 0), 0).fusions().size());
        }

        @Test void nothingToFuseWithOneLeaf() {
            Classification path = QueryClassifier.classify(hg(PAPER.subList(0, 2)), vars("a", "b"), est(5, 1000)).orElseThrow();
            assertTrue(DimensionFusion.apply(path, est(5, 1000), 0).isEmpty());
        }
    }

    // ---- group separation on a free-connex tree ---------------------------------------

    @Nested
    class GroupSeparation {

        /**
         * P = e0 {x,y} with O ∋ x, y. Leaf A = e1 {y,z}, z hidden: absorbable (and connex-
         * compatible). Leaf B = e2 {x,c}, c output: not absorbable, connex-compatible.
         */
        private final List<Triple> base = List.of(t("?x", "p", "?y"), t("?y", "r", "?z"), t("?x", "q", "?c"));

        @Test void absorbableAndConnexLeavesAreNeverFusedTogether() {
            Classification cl = QueryClassifier.classify(hg(base), vars("x", "y", "c"), est(1000, 3, 3)).orElseThrow();
            assertEquals(Kind.FREE_CONNEX, cl.kind());
            assertEquals(0, cl.root().edge().id(), "the large relation with output variables is the root");
            assertEquals(2, cl.root().children().size());
            assertTrue(DimensionFusion.apply(cl, est(1000, 3, 3), 0).isEmpty(), "one leaf per group: nothing to fuse");
        }

        @Test void twoAbsorbableLeavesFuseAndAreAbsorbed() {
            List<Triple> shape = new ArrayList<>(base);
            shape.add(t("?y", "r2", "?w"));                 // e3 = {y,w}, w hidden: absorbable
            Map<Integer, Long> e = est(1000, 3, 3, 4);
            Classification cl = QueryClassifier.classify(hg(shape), vars("x", "y", "c"), e).orElseThrow();
            assertEquals(Kind.FREE_CONNEX, cl.kind());
            Fused fused = DimensionFusion.apply(cl, e, 1.0);
            assertEquals(1, fused.fusions().size());
            assertEquals(List.of(1, 3), fused.fusions().get(0).leafIds());
            assertEquals(vars("y", "z", "w"), fused.classification().joinTree().node(4).vars());
            // F shares only the output variable y with its parent, which is in Tn, so F joins
            // Tn: harmless for the evaluator (F is absorbed in round one anyway) and it keeps
            // the connex property, which checkPlan verifies.
            assertTrue(ids(fused.classification().connexSubtree()).contains(4));
            Map<Integer, Relation> plain = byId(List.of(
                    Relation.builder("x", "y").row("x1", "y1").row("x2", "y2").build(),
                    Relation.builder("y", "z").row("y1", "z1").row("y1", "z2").build(),
                    Relation.builder("x", "c").row("x1", "c1").row("x2", "c2").row("x2", "c3").build(),
                    Relation.builder("y", "w").row("y1", "w1").row("y2", "w2").build()));
            Map<Integer, Relation> rels = fused.fuseRelations(plain);
            checkPlan(cl, plain, fused.classification(), rels, "two absorbable leaves");
            YannakakisPlusEvaluator.Reduced red = YannakakisPlusEvaluator.firstRound(fused.classification().joinTree(), cl.outputVars(), rels);
            assertEquals(1, red.absorptions(), "the fused node is absorbed");
            assertEquals(1, red.semijoins(), "the connex leaf is semijoined");
        }

        @Test void twoConnexLeavesFuseAndTakeTheirPlaceInTn() {
            List<Triple> shape = new ArrayList<>(base);
            shape.add(t("?x", "q2", "?d"));                 // e3 = {x,d}, d output: connex-compatible, not absorbable
            Map<Integer, Long> e = est(1000, 3, 3, 4);
            Classification cl = QueryClassifier.classify(hg(shape), vars("x", "y", "c", "d"), e).orElseThrow();
            assertEquals(Kind.FREE_CONNEX, cl.kind());
            assertEquals(Set.of(0, 2, 3), ids(cl.connexSubtree()));
            Fused fused = DimensionFusion.apply(cl, e, 1.0);
            assertEquals(1, fused.fusions().size());
            assertEquals(List.of(2, 3), fused.fusions().get(0).leafIds());
            assertEquals(Set.of(0, 4), ids(fused.classification().connexSubtree()), "F replaces both leaves in Tn");
            Map<Integer, Relation> plain = byId(List.of(
                    Relation.builder("x", "y").row("x1", "y1").row("x2", "y2").row("x3", "y3").build(),
                    Relation.builder("y", "z").row("y1", "z1").row("y2", "z2").build(),
                    Relation.builder("x", "c").row("x1", "c1").row("x2", "c2").row("x2", "c3").build(),
                    Relation.builder("x", "d").row("x1", "d1").row("x1", "d2").row("x2", "d1").build()));
            Map<Integer, Relation> rels = fused.fuseRelations(plain);
            checkPlan(cl, plain, fused.classification(), rels, "two connex leaves");
            YannakakisPlusEvaluator.Answer answer = YannakakisPlusEvaluator.evaluate(fused.classification(), rels);
            assertEquals(1, answer.merges(), "one reducible merge with the fused node");
        }
    }

    // ---- §5.2 root choice --------------------------------------------------------------

    @Nested
    class RootChoice {

        @Test void largestDominatingRelationBecomesTheRoot() {
            // path e0 {x,y}, e1 {y,z}; O = {y}: both dominate
            List<Triple> path = List.of(t("?x", "p", "?y"), t("?y", "q", "?z"));
            assertEquals(0, QueryClassifier.classify(hg(path), vars("y")).orElseThrow().root().edge().id(), "structure only: first in BGP order");
            assertEquals(1, QueryClassifier.classify(hg(path), vars("y"), est(10, 100)).orElseThrow().root().edge().id());
            assertEquals(0, QueryClassifier.classify(hg(path), vars("y"), est(100, 10)).orElseThrow().root().edge().id());
            assertEquals(0, QueryClassifier.classify(hg(path), vars("y"), est(7, 7)).orElseThrow().root().edge().id(), "ties: BGP order");
        }

        @Test void generalAcyclicRootPrefersOutputVariablesThenSize() {
            // star centre x hidden, arms a, b, c output: general acyclic; e3 = {c,w} adds a
            // node without any output variable that is nevertheless the largest.
            List<Triple> star = List.of(t("?x", "p", "?a"), t("?x", "q", "?b"), t("?x", "r", "?c"), t("?w", "s", "?v"));
            Set<Var> o = vars("a", "b", "c");
            Classification cl = QueryClassifier.classify(hg(star), o, est(5, 50, 20, 1000)).orElseThrow();
            assertEquals(Kind.ACYCLIC, cl.kind());
            assertEquals(1, cl.root().edge().id(), "largest node that mentions an output variable");
            assertTrue(cl.joinTree().satisfiesRunningIntersection());
        }

        @Test void freeConnexRootIsTheLargestConnexNodeWithOutput() {
            // e0 {x,z}, e1 {z,w}, e2 {x,c}, e3 {c,d}; O = {x, c, d}: z and w are hidden, so
            // e1's only witness is e0 and e1 can never be in Tn; Tn = {e0, e2, e3}, shaped by
            // the second GYO with e2 (largest) as the witness of both others.
            List<Triple> shape = List.of(t("?x", "p", "?z"), t("?z", "r", "?w"), t("?x", "q", "?c"), t("?c", "s", "?d"));
            Classification cl = QueryClassifier.classify(hg(shape), vars("x", "c", "d"), est(10, 5000, 100, 20)).orElseThrow();
            assertEquals(Kind.FREE_CONNEX, cl.kind());
            assertEquals(Set.of(0, 2, 3), ids(cl.connexSubtree()), cl.joinTree().pretty());
            assertEquals(2, cl.root().edge().id(), "e1 is largest but outside Tn; e2 is the largest Tn node");
            assertTrue(cl.satisfiesConnexProperty());
            assertTrue(cl.joinTree().satisfiesRunningIntersection());
            assertEquals(0, cl.joinTree().node(1).parent().edge().id(), "e1 keeps its parent below Tn");
            assertEquals(2, cl.joinTree().node(3).parent().edge().id());
            assertEquals(2, cl.joinTree().node(0).parent().edge().id());
        }

        @Test void largeRelationsBecomeParentsInTheGyoTree() {
            // chain e0 {a,b} e1 {b,c} e2 {c,d} with O = {} (dominated by anything): the
            // largest is the root, and both orders of the estimates give the mirror tree.
            List<Triple> chain = List.of(t("?a", "p", "?b"), t("?b", "q", "?c"), t("?c", "r", "?d"));
            Classification up = QueryClassifier.classify(hg(chain), vars(), est(1, 10, 100)).orElseThrow();
            assertEquals(2, up.root().edge().id());
            assertEquals(1, up.root().children().get(0).edge().id());
            Classification down = QueryClassifier.classify(hg(chain), vars(), est(100, 10, 1)).orElseThrow();
            assertEquals(0, down.root().edge().id());
            assertEquals(1, down.root().children().get(0).edge().id());
        }
    }

    // ---- randomised: estimates shape the plan, never the answer ------------------------

    @Test
    void randomEstimatesKeepClassAndAnswer() {
        long seed = 20260919L;
        int fusionsAtOne = 0, fusionsAtZero = 0, rerooted = 0;
        int[] kinds = new int[3];
        for (int round = 0; round < 300; round++) {
            Random rnd = new Random(seed + round);
            List<Triple> shape = randomAcyclicShape(rnd);
            QueryHypergraph h = hg(shape);
            Set<Var> o = new LinkedHashSet<>();
            for (Var v : h.vertices()) if (rnd.nextBoolean()) o.add(v);
            Map<Integer, Relation> rels = new HashMap<>();
            Map<Integer, Long> estimates = new LinkedHashMap<>();
            for (QueryHypergraph.Hyperedge e : h.edges()) {
                rels.put(e.id(), randomRelation(rnd, e.vars()));
                estimates.put(e.id(), rnd.nextInt(3) == 0 ? (long) rnd.nextInt(3) : (long) rnd.nextInt(200));
            }
            Classification structural = QueryClassifier.classify(h, o).orElseThrow();
            Classification cl = QueryClassifier.classify(h, o, estimates).orElseThrow();
            String ctx = "round " + round + " shape=" + shape + " O=" + o + " est=" + estimates + "\n" + cl.joinTree().pretty();
            kinds[cl.kind().ordinal()]++;
            if (cl.root().edge().id() != structural.root().edge().id()) rerooted++;
            checkPlan(structural, cl, rels, "estimate-aware classification: " + ctx);
            assertRootPreference(h, cl, o, estimates, ctx);

            for (double ratio : new double[] { 1.0, 0.0 }) {
                Fused fused = DimensionFusion.apply(cl, estimates, ratio);
                Map<Integer, Relation> fusedRels = fused.fuseRelations(rels);
                assertEquals(fused.classification().joinTree().size(), fusedRels.size(), ctx);
                assertEquals(cl.joinTree().size() - fusedLeafCount(fused) + fused.fusions().size(),
                        fused.classification().joinTree().size(), ctx);
                checkPlan(structural, rels, fused.classification(), fusedRels, "θ=" + ratio + ": " + ctx);
                if (ratio == 1.0) fusionsAtOne += fused.fusions().size(); else fusionsAtZero += fused.fusions().size();
            }
        }
        assertTrue(kinds[0] > 0 && kinds[1] > 0 && kinds[2] > 0, "all classes exercised: " + kinds[0] + "/" + kinds[1] + "/" + kinds[2]);
        assertTrue(rerooted > 20, "estimates must actually move the root sometimes: " + rerooted);
        assertTrue(fusionsAtOne > 10, "fusion at θ = 1 must fire regularly: " + fusionsAtOne);
        assertTrue(fusionsAtZero > fusionsAtOne, "θ = 0 fuses everything eligible: " + fusionsAtZero);
    }

    private static int fusedLeafCount(Fused fused) {
        int n = 0;
        for (Fusion f : fused.fusions()) n += f.leafIds().size();
        return n;
    }

    /** The §5.2 root rules, checked against the candidate pool of the class. */
    private static void assertRootPreference(QueryHypergraph h, Classification cl, Set<Var> o, Map<Integer, Long> estimates, String ctx) {
        List<Integer> candidates = new ArrayList<>();
        switch (cl.kind()) {
            case RELATION_DOMINATED -> {
                for (QueryHypergraph.Hyperedge e : h.edges()) if (e.vars().containsAll(cl.outputVars())) candidates.add(e.id());
            }
            case FREE_CONNEX -> candidates.addAll(ids(cl.connexSubtree()));
            case ACYCLIC -> { for (QueryHypergraph.Hyperedge e : h.edges()) candidates.add(e.id()); }
        }
        boolean anyWithOutput = false;
        for (Integer id : candidates) anyWithOutput |= !Collections.disjoint(h.edges().get(id).vars(), cl.outputVars());
        int root = cl.root().edge().id();
        assertTrue(candidates.contains(root), ctx);
        if (anyWithOutput) {
            assertFalse(Collections.disjoint(cl.root().vars(), cl.outputVars()), "root without output variable although possible: " + ctx);
            long best = Long.MIN_VALUE;
            for (Integer id : candidates) {
                if (!Collections.disjoint(h.edges().get(id).vars(), cl.outputVars())) best = Math.max(best, estimates.get(id));
            }
            assertEquals(best, estimates.get(root), "root is not the largest eligible node: " + ctx);
        }
    }

    // ---- corpus BGPs with estimates from the corpus graph -------------------------------

    @Test
    void corpusBgpsWithBoundedEstimates() {
        Graph g = OutputVariableSafetyTest.corpusModel().getGraph();
        CardinalityEstimator estimator = new BoundedCountEstimator(g, 1000);
        int checked = 0, fusions = 0;
        for (OutputVariableSafetyTest.Case c : OutputVariableSafetyTest.CORPUS) {
            Op op = Algebra.compile(QueryFactory.create(c.sparql()));
            AlgebraContextAnalyzer.Analysis analysis = AlgebraContextAnalyzer.analyze(op);
            List<OpBGP> bgps = new ArrayList<>();
            OpWalker.walk(op, new OpVisitorBase() {
                @Override public void visit(OpBGP opBGP) { bgps.add(opBGP); }
            });
            for (OpBGP bgp : bgps) {
                QueryHypergraph h = QueryHypergraph.fromBasicPattern(bgp.getPattern());
                Set<Var> o = analysis.outputVarsOrAll(bgp);
                if (QueryClassifier.classify(h, o).isEmpty()) continue;    // cyclic
                Map<Integer, Relation> rels = new HashMap<>();
                Map<Integer, Long> estimates = new LinkedHashMap<>();
                int id = 0;
                for (Triple t : bgp.getPattern()) {
                    rels.put(id, matchTriple(g, t));
                    estimates.put(id, estimator.estimate(t));
                    assertEquals(rels.get(id).rowCount() <= estimates.get(id), true,
                            "a bounded count is exact here and never below the relation's size: " + t);
                    id++;
                }
                Classification structural = QueryClassifier.classify(h, o).orElseThrow();
                Classification cl = QueryClassifier.classify(h, o, estimates).orElseThrow();
                String ctx = c.name() + " " + bgp + " O=" + o + " est=" + estimates;
                checkPlan(structural, cl, rels, ctx);
                for (double ratio : new double[] { YannakakisSymbols.FUSION_RATIO_DEFAULT, 0.0 }) {
                    Fused fused = DimensionFusion.apply(cl, estimates, ratio);
                    checkPlan(structural, rels, fused.classification(), fused.fuseRelations(rels), "θ=" + ratio + " " + ctx);
                    if (ratio == 0.0) fusions += fused.fusions().size();
                }
                checked++;
            }
        }
        assertTrue(checked >= 60, "corpus BGPs checked: " + checked);
        assertTrue(fusions > 0, "the corpus must contain fusable leaf pairs: " + fusions);
    }

    // ---- end to end through the executor ------------------------------------------------

    @Nested
    class EndToEnd {

        private static final String PREFIX = "PREFIX ex: <" + NS + ">\n";

        /** A fact relation (knows, 60 triples) over two small dimension relations (name: 4, livesIn: 3). */
        private static Model starSchema() {
            Model m = ModelFactory.createDefaultModel();
            Property knows = m.createProperty(NS + "knows"), name = m.createProperty(NS + "name"), livesIn = m.createProperty(NS + "livesIn");
            Resource[] people = new Resource[12];
            for (int i = 0; i < people.length; i++) people[i] = m.createResource(NS + "p" + i);
            for (int i = 0; i < people.length; i++) {
                for (int j = 1; j <= 5; j++) m.add(people[i], knows, people[(i + j) % people.length]);
            }
            m.add(people[0], name, "Ann").add(people[1], name, "Bob").add(people[2], name, "Cy").add(people[2], name, "Cyrus");
            m.add(people[1], livesIn, m.createResource(NS + "vienna")).add(people[3], livesIn, m.createResource(NS + "graz"))
                    .add(people[1], livesIn, m.createResource(NS + "linz"));
            return m;
        }

        private static final String QUERY = PREFIX + "SELECT ?x ?y WHERE { ?x ex:knows ?y . ?x ex:name ?n . ?y ex:livesIn ?c }";
        /** O = {x}: knows and name both dominate; the larger knows is the root, name and livesIn its leaves. */
        private static final String PROJECTED = PREFIX + "SELECT ?x WHERE { ?x ex:knows ?y . ?x ex:name ?n . ?y ex:livesIn ?c }";
        private static final String DISTINCT = PREFIX + "SELECT DISTINCT ?x WHERE { ?x ex:knows ?y . ?x ex:name ?n . ?y ex:livesIn ?c }";
        /**
         * O = {n, c}: H+ closes the cycle x–y–c–n, so this is general acyclic, rooted at
         * the largest node with an output variable (name); knows is its only child and
         * livesIn the only grandchild — no leaf siblings, nothing to fuse.
         */
        private static final String NO_SIBLINGS = PREFIX + "SELECT ?n ?c WHERE { ?x ex:knows ?y . ?x ex:name ?n . ?y ex:livesIn ?c }";
        /**
         * The OPTIONAL body is a separate BGP evaluated once per binding of ?x, with ?x
         * substituted: the estimates are then per binding (knows: 5, name: 1 or 2,
         * livesIn: 3), and 1 · 3 ≤ 5 fuses the leaves for the bindings with one name.
         */
        private static final String NESTED = PREFIX + "SELECT ?x ?y ?z WHERE { ?z ex:knows ?x . OPTIONAL { ?x ex:knows ?y . ?x ex:name ?n . ?y ex:livesIn ?c } }";

        private static List<String> run(Model m, String query) {
            List<String> rows = new ArrayList<>();
            Query q = QueryFactory.create(query);
            try (QueryExecution qe = QueryExecutionFactory.create(q, m)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution s = rs.next();
                    List<String> names = new ArrayList<>();
                    s.varNames().forEachRemaining(names::add);
                    Collections.sort(names);
                    List<String> cells = new ArrayList<>();
                    for (String v : names) cells.add(v + "=" + s.get(v));
                    rows.add(String.join(" | ", cells));
                }
            }
            Collections.sort(rows);
            return rows;
        }

        private static List<String> withYannakakis(Model m, String query, Object ratio) {
            YannakakisOpExecutor.register();
            if (ratio != null) ARQ.getContext().set(YannakakisSymbols.FUSION_RATIO, ratio);
            try {
                return run(m, query);
            } finally {
                ARQ.getContext().unset(YannakakisSymbols.FUSION_RATIO);
                YannakakisOpExecutor.unregister();
            }
        }

        @Test void fusionFiresWithTheDefaultEstimatorAndMatchesStockArq() {
            Model m = starSchema();
            for (String query : List.of(QUERY, PROJECTED, DISTINCT, NESTED)) {
                List<String> stock = run(m, query);
                assertFalse(stock.isEmpty(), query);
                YannakakisOpExecutor.resetCounter();
                assertEquals(stock, withYannakakis(m, query, null), query);
                assertTrue(YannakakisOpExecutor.invocations() >= 1, query);
                assertTrue(YannakakisOpExecutor.fusions() >= 1, "60 ≥ 4 · 3: the two dimension leaves must fuse for\n" + query);
            }
            List<String> stock = run(m, NO_SIBLINGS);
            YannakakisOpExecutor.resetCounter();
            assertEquals(stock, withYannakakis(m, NO_SIBLINGS, null));
            assertEquals(0, YannakakisOpExecutor.fusions(), NO_SIBLINGS);
        }

        @Test void fusionOffByRatioGivesTheSameBag() {
            Model m = starSchema();
            List<String> stock = run(m, QUERY);
            YannakakisOpExecutor.resetCounter();
            assertEquals(stock, withYannakakis(m, QUERY, Double.POSITIVE_INFINITY));
            assertEquals(0, YannakakisOpExecutor.fusions());
            YannakakisOpExecutor.resetCounter();
            assertEquals(stock, withYannakakis(m, QUERY, "100"), "string-valued symbol: 12 · 100 > 60");
            assertEquals(0, YannakakisOpExecutor.fusions());
            YannakakisOpExecutor.resetCounter();
            assertEquals(stock, withYannakakis(m, QUERY, 5.0));
            assertEquals(1, YannakakisOpExecutor.fusions(), "12 · 5 = 60 ≤ 60");
        }

        @Test void handWrittenEstimatorViaContextIsUsed() {
            Model m = starSchema();
            List<String> stock = run(m, QUERY);
            // claims knows is tiny: the leaves are then "large" relative to it and never fuse
            CardinalityEstimator lies = t -> t.getPredicate().equals(node("knows")) ? 1L : 1000L;
            ARQ.getContext().set(YannakakisSymbols.CARDINALITY_ESTIMATOR, lies);
            try {
                YannakakisOpExecutor.resetCounter();
                assertEquals(stock, withYannakakis(m, QUERY, null), "a wrong estimate costs time, never the answer");
                assertEquals(0, YannakakisOpExecutor.fusions());
                YannakakisOpExecutor.resetCounter();
                ARQ.getContext().set(YannakakisSymbols.CARDINALITY_ESTIMATOR, CardinalityEstimator.UNKNOWN);
                assertEquals(stock, withYannakakis(m, QUERY, null));
                assertEquals(0, YannakakisOpExecutor.fusions(), "no information, no fusion");
            } finally {
                ARQ.getContext().unset(YannakakisSymbols.CARDINALITY_ESTIMATOR);
            }
            assertNotEquals(0, stock.size());
        }
    }

    // ---- generators (as in YannakakisPlusEvaluatorTest) ---------------------------------

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
                if (bind(row, s, m.getSubject()) && bind(row, p, m.getPredicate()) && bind(row, o, m.getObject())) rows.add(row);
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

    /** Berge-acyclic by construction, 2..7 patterns; stars and chains both occur. */
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
            triples.add(rnd.nextBoolean() ? Triple.create(anchor, p, other) : Triple.create(other, p, anchor));
        }
        return triples;
    }

    private static Node pred(Random rnd) {
        return NodeFactory.createURI(NS + "p" + rnd.nextInt(3));
    }
}
