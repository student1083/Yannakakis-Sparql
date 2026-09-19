package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.ARQ;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.engine.iterator.QueryIterRepeatApply;
import org.apache.jena.sparql.engine.main.OpExecutor;
import org.apache.jena.sparql.engine.main.OpExecutorFactory;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom ARQ physical executor. Intercepts BGP evaluation: alpha-acyclic BGPs
 * are evaluated with the output-sensitive {@link YannakakisPlusEvaluator}; cyclic
 * BGPs (and the no-active-graph case) fall through to stock ARQ unchanged.
 *
 * <p>On the first {@link #exec} call of a query execution (which receives the root
 * of the plan) the {@link AlgebraContextAnalyzer} is run once over the whole tree
 * and its table stored in the execution's {@code Context}. Each intercepted BGP
 * then looks up its output variables O by object identity; a BGP the analyzer
 * never saw (ARQ manufactures fresh {@code OpBGP} objects for quad patterns and
 * for the substituted right side of an index join) gets all of its variables.
 * The BGP is classified against O ({@link QueryClassifier}), evaluated to π_O
 * with multiplicities, and each result row is emitted as many times as its
 * multiplicity — or once, when the analyzer found the BGP's counts collapsible
 * (a DISTINCT above with nothing multiplicity-sensitive in between).
 */
public class YannakakisOpExecutor extends OpExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(YannakakisOpExecutor.class);

    public static final OpExecutorFactory FACTORY = YannakakisOpExecutor::new;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final AtomicInteger ANALYSES = new AtomicInteger();
    private static final AtomicInteger NARROWED = new AtomicInteger();
    private static final AtomicInteger COLLAPSED = new AtomicInteger();
    private static final AtomicInteger FUSIONS = new AtomicInteger();
    private static OpExecutorFactory previous;

    public YannakakisOpExecutor(ExecutionContext execCxt) {
        super(execCxt);
    }

    /**
     * Hook for the once-per-execution algebra analysis. This is the least invasive
     * point available: ARQ enters an executor through this recursive step
     * ({@code QC.execute} → static {@code OpExecutor.execute} → {@code exec}), not
     * through {@code executeOp()}, and the very first {@code exec()} of an execution
     * receives the root of the (already optimised) plan — so no
     * {@code QueryEngineFactory} is needed to see the whole tree, and nothing is
     * rewritten: the tree is only read. Every later call — the recursion within
     * this executor, and the executors ARQ creates for sub-plans (per-row index
     * joins, EXISTS, ...) — shares this execution's {@code Context} and finds the
     * table already there. The {@code Context} is created fresh per execution by
     * {@code QueryExecDatasetBuilder}, so a table never leaks into the next query.
     */
    @Override
    protected QueryIterator exec(Op op, QueryIterator input) {
        if (AlgebraContextAnalyzer.lookup(execCxt).isEmpty()) {
            try {
                AlgebraContextAnalyzer.analyze(op, execCxt);
                ANALYSES.incrementAndGet();
            } catch (RuntimeException e) {
                // The analysis is advisory: a failure must never fail the query.
                // Store an empty table so every lookup falls back to all variables
                // (and so the analysis is not retried on every nested exec()).
                LOG.warn("algebra analysis failed; every BGP keeps all variables", e);
                execCxt.getContext().set(AlgebraContextAnalyzer.SYMBOL, AlgebraContextAnalyzer.emptyAnalysis());
            }
        }
        return super.exec(op, input);
    }

    @Override
    protected QueryIterator execute(OpBGP opBGP, QueryIterator input) {
        BasicPattern pattern = opBGP.getPattern();
        Graph activeGraph = execCxt.getActiveGraph();

        boolean acyclic = activeGraph != null
                && GyoReduction.isAcyclic(QueryHypergraph.fromBasicPattern(pattern));

        if (!acyclic) {
            return super.execute(opBGP, input);          // cyclic / no graph -> native ARQ
        }
        COUNTER.incrementAndGet();
        // O by OpBGP identity; all variables if this object was never analysed.
        // A missing O is never an error.
        Set<Var> outputVars = AlgebraContextAnalyzer.outputVarsOrAll(execCxt, opBGP);
        if (!outputVars.containsAll(AlgebraContextAnalyzer.varsOf(pattern))) {
            NARROWED.incrementAndGet();
        }
        boolean collapsible = AlgebraContextAnalyzer.countsCollapsible(execCxt, opBGP);
        if (collapsible) COLLAPSED.incrementAndGet();
        LOG.debug("BGP {} output variables O = {}, counts collapsible = {}", pattern, outputVars, collapsible);
        CardinalityEstimator estimator = CardinalityEstimator.forGraph(activeGraph, execCxt.getContext());
        double fusionRatio = YannakakisSymbols.fusionRatio(execCxt.getContext());
        return new Stage(pattern, outputVars, collapsible, estimator, fusionRatio, input, execCxt);
    }

    // ---- registration ---------------------------------------------------

    public static void register() {
        Context cxt = ARQ.getContext();
        previous = QC.getFactory(cxt);                   // remember the default
        QC.setFactory(cxt, FACTORY);
    }

    public static void unregister() {
        // If QC.getFactory is unavailable in your version, use: QC.setFactory(ARQ.getContext(), null)
        QC.setFactory(ARQ.getContext(), previous);
    }

    public static int invocations()  { return COUNTER.get(); }
    /** Number of query executions for which the algebra analysis ran (one per execution). */
    public static int analyses()     { return ANALYSES.get(); }
    /**
     * Number of intercepted BGPs whose output-variable set O (as looked up, before
     * per-binding restriction) was a strict subset of their variables.
     */
    public static int narrowedBgps() { return NARROWED.get(); }
    /** Number of intercepted BGPs whose multiplicities were collapsed to 1 (DISTINCT above). */
    public static int collapsedBgps() { return COLLAPSED.get(); }
    /** Number of dimension fusions performed ({@link DimensionFusion}), over all bindings of all BGPs. */
    public static int fusions()      { return FUSIONS.get(); }
    public static void resetCounter() {
        COUNTER.set(0);
        ANALYSES.set(0);
        NARROWED.set(0);
        COLLAPSED.set(0);
        FUSIONS.set(0);
    }

    // ---- per-input-binding evaluation -----------------------------------

    private static final class Stage extends QueryIterRepeatApply {
        private final BasicPattern pattern;
        private final Set<Var> outputVars;
        private final boolean collapsible;
        private final CardinalityEstimator estimator;
        private final double fusionRatio;
        /**
         * Per binding shape and estimate ranking: the classification (rooted join tree + O
         * restricted to the free variables), before fusion.
         */
        private final Map<Object, Optional<QueryClassifier.Classification>> planCache = new HashMap<>();

        Stage(BasicPattern pattern, Set<Var> outputVars, boolean collapsible,
              CardinalityEstimator estimator, double fusionRatio, QueryIterator input, ExecutionContext execCxt) {
            super(input, execCxt);
            this.pattern = pattern;
            this.outputVars = outputVars;
            this.collapsible = collapsible;
            this.estimator = estimator;
            this.fusionRatio = fusionRatio;
        }

        @Override
        protected QueryIterator nextStage(Binding binding) {
            // 1. substitute the incoming binding into the patterns
            BasicPattern bound = new BasicPattern();
            for (Triple t : pattern) bound.add(substitute(t, binding));

            // 2. estimate each substituted pattern's cardinality before materialising it.
            // The estimates depend on the bound constants (a bound subject makes a pattern
            // tiny), so they are taken per binding; they only shape the plan, never its answer.
            Map<Integer, Long> estimates = new LinkedHashMap<>();
            int id = 0;
            for (Triple t : bound) estimates.put(id++, estimator.estimate(t));

            // 3. classify the (substituted) BGP against O restricted to the variables
            // still free after substitution (a variable the incoming binding already
            // bound is a constant in `bound` and is carried by the parent binding, not
            // produced here). The classification — kind, rooted join tree, effective O —
            // depends on the BGP's shape (which positions are variables and which
            // variables they are) and, through the §5.2 heuristics, on the relative
            // order of the estimates, but not on their magnitudes or on the concrete
            // bound node values; so it is cached per shape and estimate ranking for the
            // lifetime of this Stage.
            Optional<QueryClassifier.Classification> plan = planCache.computeIfAbsent(planKey(bound, estimates), k -> {
                Set<Var> free = AlgebraContextAnalyzer.varsOf(bound);
                Set<Var> restricted = new LinkedHashSet<>();
                for (Var v : outputVars) if (free.contains(v)) restricted.add(v);
                LOG.debug("BGP {} under binding {}: O restricted to free variables = {}, estimates = {}",
                        bound, binding, restricted, estimates);
                return QueryClassifier.classify(QueryHypergraph.fromBasicPattern(bound), restricted, estimates);
            });

            // 4. the §5.1 fusion rule needs the magnitudes, so it runs per binding on the
            // cached tree (cheap: a pass over the tree's leaves). plan is never empty here.
            // Lemma: binding variables to constants only deletes vertices from the query
            // hypergraph and never adds any, so if the unsubstituted pattern (checked
            // acyclic in execute() above) has a join tree, that same tree's
            // running-intersection property survives for every vertex remaining after
            // substitution. Hence classify on the substituted pattern always returns a
            // non-empty Optional; see GyoReductionTest#acyclicityPreservedUnderBinding.
            DimensionFusion.Fused fused = plan.map(cl -> DimensionFusion.apply(cl, estimates, fusionRatio))
                    .orElseThrow(() -> new IllegalStateException(
                            "unreachable: binding variables to constants only deletes hypergraph "
                                    + "vertices, never adds any, so an acyclic BGP's join tree survives "
                                    + "substitution intact (see GyoReductionTest#acyclicityPreservedUnderBinding)"));
            if (!fused.isEmpty()) {
                FUSIONS.addAndGet(fused.fusions().size());
                LOG.debug("BGP {} under binding {}: fused {}", bound, binding, fused.fusions());
            }

            // 5. materialize each pattern against the live graph, then join the fused
            // leaves (the small relations) before anything touches their large parent
            Graph graph = getExecContext().getActiveGraph();
            Map<Integer, Relation> rels = new HashMap<>();
            id = 0;
            for (Triple t : bound) rels.put(id++, matchTriple(graph, t));
            rels = fused.fuseRelations(rels);

            // 6. evaluate via Yannakakis+
            Relation result = YannakakisPlusEvaluator.evaluate(fused.classification(), rels).relation();
            // The only place DISTINCT enters: collapse the multiplicities at the very end.
            if (collapsible) result = result.distinct();

            // 7. extend the input binding with each result row, once per multiplicity
            List<Binding> out = new ArrayList<>((int) Math.min(result.bagSize(), Integer.MAX_VALUE));
            for (Map.Entry<Map<Var, Node>, Integer> row : result.counts().entrySet()) {
                BindingBuilder bb = BindingFactory.builder(binding);
                for (Map.Entry<Var, Node> e : row.getKey().entrySet()) bb.add(e.getKey(), e.getValue());
                Binding b = bb.build();
                for (int n = row.getValue(); n > 0; n--) out.add(b);
            }
            return QueryIterPlainWrapper.create(out.iterator(), getExecContext());
        }
    }

    // ---- helpers ---------------------------------------------------------

    /** Marks a non-variable (concrete term) position in a BGP shape key. */
    private static final Object CONST = new Object();

    /**
     * Key for the join-tree cache: the BGP's shape — for each triple, the kind of each
     * position (variable, or concrete term) and, for variables, the variable identity —
     * followed by the ranking of the estimates (each triple's rank among the distinct
     * estimate values). Two bound BGPs that bind the same set of variables in the same
     * triple positions and order their patterns by size the same way produce equal keys,
     * regardless of which concrete nodes they were bound to and of the sizes themselves;
     * that is exactly what the estimate-aware classification depends on.
     */
    private static Object planKey(BasicPattern bound, Map<Integer, Long> estimates) {
        List<Object> key = new ArrayList<>(bound.size() * 4);
        for (Triple t : bound) {
            key.add(shapeOf(t.getSubject()));
            key.add(shapeOf(t.getPredicate()));
            key.add(shapeOf(t.getObject()));
        }
        List<Long> distinct = new ArrayList<>(new TreeSet<>(estimates.values()));
        for (int i = 0; i < bound.size(); i++) {
            key.add(Collections.binarySearch(distinct, estimates.get(i)));
        }
        return key;
    }

    private static Object shapeOf(Node n) {
        return Var.isVar(n) ? Var.alloc(n) : CONST;
    }

    private static Triple substitute(Triple t, Binding b) {
        return Triple.create(sub(t.getSubject(), b), sub(t.getPredicate(), b), sub(t.getObject(), b));
    }

    private static Node sub(Node n, Binding b) {
        if (Var.isVar(n)) {
            Node v = b.get(Var.alloc(n));
            if (v != null) return v;
        }
        return n;
    }

    private static Relation matchTriple(Graph g, Triple pat) {
        Node s = pat.getSubject(), p = pat.getPredicate(), o = pat.getObject();

        Set<Var> schema = new LinkedHashSet<>();
        addVar(schema, s); addVar(schema, p); addVar(schema, o);

        Set<Map<Var, Node>> rows = new HashSet<>();
        ExtendedIterator<Triple> it = g.find(match(s), match(p), match(o));
        try {
            while (it.hasNext()) {
                Triple m = it.next();
                Map<Var, Node> row = new HashMap<>();
                if (bindPos(row, s, m.getSubject())
                        && bindPos(row, p, m.getPredicate())
                        && bindPos(row, o, m.getObject())) {
                    rows.add(row);
                }
            }
        } finally {
            it.close();
        }
        return Relation.fromRows(schema, rows);
    }

    private static Node match(Node n) { return Var.isVar(n) ? Node.ANY : n; }

    private static void addVar(Set<Var> s, Node n) { if (Var.isVar(n)) s.add(Var.alloc(n)); }

    /** Bind a variable position consistently; repeated vars in one triple must agree. */
    private static boolean bindPos(Map<Var, Node> row, Node patNode, Node value) {
        if (!Var.isVar(patNode)) return true;            // constant: find() already matched it
        Var v = Var.alloc(patNode);
        Node prev = row.get(v);
        if (prev == null) { row.put(v, value); return true; }
        return prev.equals(value);
    }

}