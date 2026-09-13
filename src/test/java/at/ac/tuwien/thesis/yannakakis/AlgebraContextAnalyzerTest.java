package at.ac.tuwien.thesis.yannakakis;

import at.ac.tuwien.thesis.yannakakis.AlgebraContextAnalyzer.Analysis;
import at.ac.tuwien.thesis.yannakakis.AlgebraContextAnalyzer.FallbackReason;
import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpExt;
import org.apache.jena.sparql.algebra.op.OpProject;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.sparql.util.NodeIsomorphismMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact expected output-variable sets for hand-written queries. Trees are taken from
 * {@link Algebra#compile} (no optimizer) so the BGP structure is the one visible in the
 * query text; the analyzer itself is tree-shape agnostic.
 */
class AlgebraContextAnalyzerTest {

    private static final String NS = "http://example.org/";
    private static final String PREFIX = "PREFIX ex: <" + NS + ">\n";

    private static Op compile(String query) {
        return Algebra.compile(QueryFactory.create(PREFIX + query));
    }

    private static Set<Var> vars(String... names) {
        Set<Var> s = new java.util.LinkedHashSet<>();
        for (String n : names) s.add(Var.alloc(n));
        return s;
    }

    private static List<OpBGP> bgps(Op root) {
        List<OpBGP> found = new ArrayList<>();
        OpWalker.walk(root, new OpVisitorBase() {
            @Override public void visit(OpBGP opBGP) { found.add(opBGP); }
        });
        return found;
    }

    /** The unique BGP in {@code root} containing a triple whose predicate is {@code ex:<local>}. */
    private static OpBGP bgpWithPredicate(Op root, String local) {
        List<OpBGP> hits = new ArrayList<>();
        for (OpBGP b : bgps(root)) {
            for (Triple t : b.getPattern()) {
                if (t.getPredicate().isURI() && t.getPredicate().getURI().equals(NS + local)) {
                    hits.add(b);
                    break;
                }
            }
        }
        assertEquals(1, hits.size(), "expected exactly one BGP with predicate ex:" + local);
        return hits.get(0);
    }

    private static Set<Var> outputOf(Analysis a, OpBGP bgp) {
        Optional<Set<Var>> o = a.outputVars(bgp);
        assertTrue(o.isPresent(), "BGP should have been analysed: " + bgp);
        return o.get();
    }

    // ---- the hand-written query set ----------------------------------------

    @Test
    void plainSelectStarKeepsEveryVariable() {
        Op root = compile("SELECT * WHERE { ?a ex:p ?b . ?b ex:q ?c }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("a", "b", "c"), outputOf(a, bgpWithPredicate(root, "p")));
        assertTrue(a.fallbackReasonCounts().isEmpty());
    }

    @Test
    void selectDistinctProjectsToListedVariables() {
        Op root = compile("SELECT DISTINCT ?x ?z WHERE { ?x ex:p ?y . ?y ex:q ?z }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("x", "z"), outputOf(a, bgpWithPredicate(root, "p")));
    }

    @Test
    void projectionDropsUnneededVariables() {
        Op root = compile("SELECT ?x WHERE { ?x ex:p ?y . ?y ex:q ?z }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("x"), outputOf(a, bgpWithPredicate(root, "p")));
    }

    @Test
    void bgpUnderOptionalKeepsJoinVariableOnBothSides() {
        // (project (?x ?n) (leftjoin (bgp ?x p ?y) (bgp ?y q ?n)))
        Op root = compile("SELECT ?x ?n WHERE { ?x ex:p ?y OPTIONAL { ?y ex:q ?n } }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        // left: projected ?x, plus ?y because the OPTIONAL side needs it
        assertEquals(vars("x", "y"), outputOf(a, bgpWithPredicate(root, "p")));
        // right: ?y (shared with the left side it must stay compatible with) and projected ?n
        assertEquals(vars("y", "n"), outputOf(a, bgpWithPredicate(root, "q")));
    }

    @Test
    void bgpJoinedWithAnotherBgpKeepsSharedVariable() {
        // (project (?x) (join (bgp ?x p ?y) (bgp ?y q ?z)))
        Op root = compile("SELECT ?x WHERE { { ?x ex:p ?y } { ?y ex:q ?z } }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("x", "y"), outputOf(a, bgpWithPredicate(root, "p")));
        assertEquals(vars("y"), outputOf(a, bgpWithPredicate(root, "q")));
    }

    @Test
    void filterOnUnprojectedVariableKeepsIt() {
        // (project (?x) (filter (> ?z 1) (bgp ...)))
        Op root = compile("SELECT ?x WHERE { ?x ex:p ?y . ?y ex:q ?z FILTER(?z > 1) }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("x", "z"), outputOf(a, bgpWithPredicate(root, "p")));
    }

    @Test
    void nestedSubqueryWithGroupBy() {
        // (project (?x)
        //   (join (bgp ?x p ?y)
        //         (project (?y ?c) (extend ((?c ?.0)) (group (?y) ((?.0 (count ?w)))
        //             (bgp ?y q ?w . ?w r ?v))))))
        Op root = compile("""
                SELECT ?x WHERE {
                  ?x ex:p ?y
                  { SELECT ?y (COUNT(?w) AS ?c) WHERE { ?y ex:q ?w . ?w ex:r ?v } GROUP BY ?y }
                }""");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        // outer: projected ?x plus ?y shared with the subquery
        assertEquals(vars("x", "y"), outputOf(a, bgpWithPredicate(root, "p")));
        // inner: ?y is the group key, ?w feeds COUNT; ?v is dead after the BGP
        assertEquals(vars("y", "w"), outputOf(a, bgpWithPredicate(root, "q")));
    }

    @Test
    void bgpUnderUnionKeepsVariablesSharedWithOtherBranch() {
        // (project (?x) (union (bgp ?x p ?y) (bgp ?y q ?z)))
        Op root = compile("SELECT ?x WHERE { { ?x ex:p ?y } UNION { ?y ex:q ?z } }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("x", "y"), outputOf(a, bgpWithPredicate(root, "p")));
        assertEquals(vars("y"), outputOf(a, bgpWithPredicate(root, "q")));
    }

    @Test
    void orderByOnUnprojectedVariableKeepsIt() {
        Op root = compile("SELECT ?x WHERE { ?x ex:p ?y . ?y ex:q ?z } ORDER BY ?z");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("x", "z"), outputOf(a, bgpWithPredicate(root, "p")));
    }

    @Test
    void groupByAndAggregateVariablesAreKept() {
        // (project (?x ?s) (extend ((?s ?.0)) (group (?x) ((?.0 (sum ?z))) (bgp ...))))
        Op root = compile("SELECT ?x (SUM(?z) AS ?s) WHERE { ?x ex:p ?y . ?y ex:q ?z } GROUP BY ?x");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("x", "z"), outputOf(a, bgpWithPredicate(root, "p")));
    }

    @Test
    void minusRightSideOnlyNeedsVariablesSharedWithLeft() {
        // (project (?x) (minus (bgp ?x p ?y) (bgp ?y q ?z)))
        Op root = compile("SELECT ?x WHERE { ?x ex:p ?y MINUS { ?y ex:q ?z } }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        assertEquals(vars("x", "y"), outputOf(a, bgpWithPredicate(root, "p")));
        assertEquals(vars("y"), outputOf(a, bgpWithPredicate(root, "q")));
    }

    // ---- fallback + storage + read-only guarantees ---------------------------

    /** An operator outside the fixed ARQ algebra: the analyzer must not pretend to understand it. */
    private static OpExt mysteryOp(Op wrapped) {
        return new OpExt("mystery") {
            @Override public Op effectiveOp() { return wrapped; }
            @Override public QueryIterator eval(QueryIterator input, ExecutionContext execCxt) {
                throw new UnsupportedOperationException();
            }
            @Override public void outputArgs(IndentedWriter out, SerializationContext sCxt) {}
            @Override public int hashCode() { return System.identityHashCode(this); }
            @Override public boolean equalTo(Op other, NodeIsomorphismMap labelMap) { return this == other; }
        };
    }

    @Test
    void unrecognisedOperatorFallsBackToAllVariablesNotFewer() {
        // (project (?x) (mystery (bgp ?x p ?y . ?y q ?z))) -- a plain project would give {?x}
        BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(Var.alloc("x"), NodeFactory.createURI(NS + "p"), Var.alloc("y")));
        bp.add(Triple.create(Var.alloc("y"), NodeFactory.createURI(NS + "q"), Var.alloc("z")));
        OpBGP bgp = new OpBGP(bp);
        Op root = new OpProject(mysteryOp(bgp), List.of(Var.alloc("x")));

        Analysis a = AlgebraContextAnalyzer.analyze(root);

        assertEquals(vars("x", "y", "z"), outputOf(a, bgp), "fallback must be ALL variables");
        assertEquals(Optional.of(FallbackReason.UNRECOGNISED_OPERATOR), a.entry(bgp).orElseThrow().fallbackReason());
        assertEquals(Map.of(FallbackReason.UNRECOGNISED_OPERATOR, 1), a.fallbackReasonCounts());
    }

    @Test
    void serviceSubtreeFallsBackAndIsCounted() {
        Op root = compile("SELECT ?x WHERE { ?x ex:p ?y SERVICE <http://remote.example/sparql> { ?y ex:q ?z } }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);
        OpBGP remote = bgpWithPredicate(root, "q");
        assertEquals(vars("y", "z"), outputOf(a, remote));
        assertEquals(Optional.of(FallbackReason.SERVICE), a.entry(remote).orElseThrow().fallbackReason());
        // the local BGP is still analysed precisely
        assertEquals(vars("x", "y"), outputOf(a, bgpWithPredicate(root, "p")));
        assertFalse(a.entry(bgpWithPredicate(root, "p")).orElseThrow().isFallback());
    }

    @Test
    void bgpNotInAnalysedTreeYieldsAllVariablesOnLookup() {
        Op root = compile("SELECT ?x WHERE { ?x ex:p ?y }");
        Analysis a = AlgebraContextAnalyzer.analyze(root);

        BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(Var.alloc("s"), NodeFactory.createURI(NS + "o"), Var.alloc("t")));
        OpBGP stranger = new OpBGP(bp);

        assertTrue(a.outputVars(stranger).isEmpty());
        assertEquals(vars("s", "t"), a.outputVarsOrAll(stranger));
        assertEquals(1, a.unanalysedLookups());
    }

    @Test
    void analysisIsStoredInExecutionContextUnderSymbol() {
        Op root = compile("SELECT ?x WHERE { ?x ex:p ?y . ?y ex:q ?z }");
        Graph g = GraphFactory.createDefaultGraph();
        ExecutionContext execCxt = ExecutionContext.createForGraph(g);

        assertTrue(AlgebraContextAnalyzer.lookup(execCxt).isEmpty());
        Analysis stored = AlgebraContextAnalyzer.analyze(root, execCxt);

        assertSame(stored, execCxt.getContext().get(AlgebraContextAnalyzer.SYMBOL));
        assertSame(stored, AlgebraContextAnalyzer.lookup(execCxt).orElseThrow());
        OpBGP bgp = bgpWithPredicate(root, "p");
        assertEquals(vars("x"), AlgebraContextAnalyzer.outputVarsOrAll(execCxt, bgp));

        // a copied ExecutionContext shares the Context object, so nested executors see it
        ExecutionContext copy = ExecutionContext.copy(execCxt);
        assertSame(stored, AlgebraContextAnalyzer.lookup(copy).orElseThrow());
    }

    @Test
    void noStoredAnalysisMeansAllVariables() {
        Op root = compile("SELECT ?x WHERE { ?x ex:p ?y }");
        ExecutionContext execCxt = ExecutionContext.createForGraph(GraphFactory.createDefaultGraph());
        assertEquals(vars("x", "y"),
                AlgebraContextAnalyzer.outputVarsOrAll(execCxt, bgpWithPredicate(root, "p")));
    }

    @Test
    void analyzerDoesNotModifyTheTree() {
        Op root = compile("""
                SELECT ?x WHERE {
                  ?x ex:p ?y
                  OPTIONAL { ?y ex:q ?n }
                  { SELECT ?y (COUNT(?w) AS ?c) WHERE { ?y ex:r ?w } GROUP BY ?y }
                  FILTER(?n > 1)
                } ORDER BY ?c""");
        String before = root.toString();
        List<OpBGP> bgpsBefore = bgps(root);

        AlgebraContextAnalyzer.analyze(root);

        assertEquals(before, root.toString());
        List<OpBGP> bgpsAfter = bgps(root);
        assertEquals(bgpsBefore.size(), bgpsAfter.size());
        for (int i = 0; i < bgpsBefore.size(); i++) {
            assertSame(bgpsBefore.get(i), bgpsAfter.get(i), "BGP objects must be untouched");
        }
    }
}
