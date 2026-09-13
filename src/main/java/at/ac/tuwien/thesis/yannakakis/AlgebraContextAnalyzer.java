package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.algebra.OpVisitor;
import org.apache.jena.sparql.algebra.op.OpAntiJoin;
import org.apache.jena.sparql.algebra.op.OpAssign;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpConditional;
import org.apache.jena.sparql.algebra.op.OpDatasetNames;
import org.apache.jena.sparql.algebra.op.OpDisjunction;
import org.apache.jena.sparql.algebra.op.OpDistinct;
import org.apache.jena.sparql.algebra.op.OpExt;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpGraph;
import org.apache.jena.sparql.algebra.op.OpGroup;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpLabel;
import org.apache.jena.sparql.algebra.op.OpLateral;
import org.apache.jena.sparql.algebra.op.OpLeftJoin;
import org.apache.jena.sparql.algebra.op.OpList;
import org.apache.jena.sparql.algebra.op.OpMinus;
import org.apache.jena.sparql.algebra.op.OpNull;
import org.apache.jena.sparql.algebra.op.OpOrder;
import org.apache.jena.sparql.algebra.op.OpPath;
import org.apache.jena.sparql.algebra.op.OpProcedure;
import org.apache.jena.sparql.algebra.op.OpProject;
import org.apache.jena.sparql.algebra.op.OpPropFunc;
import org.apache.jena.sparql.algebra.op.OpQuad;
import org.apache.jena.sparql.algebra.op.OpQuadBlock;
import org.apache.jena.sparql.algebra.op.OpQuadPattern;
import org.apache.jena.sparql.algebra.op.OpReduced;
import org.apache.jena.sparql.algebra.op.OpSemiJoin;
import org.apache.jena.sparql.algebra.op.OpSequence;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpSlice;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.algebra.op.OpTopN;
import org.apache.jena.sparql.algebra.op.OpTriple;
import org.apache.jena.sparql.algebra.op.OpUnfold;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.ExprVars;
import org.apache.jena.sparql.util.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only, single-pass analysis of an ARQ {@link Op} tree that computes, for every
 * {@link OpBGP} node, the set of <em>output variables</em> {@code O}: the variables of that
 * BGP that the rest of the plan still needs once the BGP has been evaluated. Everything
 * else the BGP binds is dead after the BGP and may be projected away early by an
 * output-sensitive evaluator.
 *
 * <p><b>This class never modifies the tree.</b> It walks the operators, reads their
 * variables/expressions, and writes its findings into a side table keyed by
 * {@code OpBGP} object identity ({@link IdentityHashMap}). No {@code Op} is copied,
 * replaced, re-parented or transformed. Algebra <em>analysis</em> (deriving facts about a
 * plan) is a different thing from algebra <em>rewriting</em> (producing a new plan via
 * {@code Transform}/{@code Transformer}); the latter remains forbidden in this project
 * (CLAUDE.md: integration happens at execution time via {@code OpExecutor} interception,
 * never by rewriting the algebra tree). The analyzer feeds the executor with facts; it does
 * not change what gets executed.
 *
 * <h2>Definition of O</h2>
 * O for a BGP is the intersection of the BGP's own variables with the <em>demand</em> that
 * reaches it from above. Demand flows top-down and is the union of:
 * <ul>
 *   <li>variables kept by the nearest enclosing {@link OpProject} (all the way to the top —
 *       a projection resets the demand to exactly its variable list);</li>
 *   <li>variables mentioned in any enclosing {@link OpFilter} expression
 *       ({@link ExprVars#varsMentioned});</li>
 *   <li>variables in enclosing ORDER BY ({@link OpOrder}, {@link OpTopN}), GROUP BY and
 *       aggregate expressions ({@link OpGroup}), BIND/assign ({@link OpExtend},
 *       {@link OpAssign}, {@link OpUnfold}) and LEFT JOIN conditions;</li>
 *   <li>every variable mentioned by any sibling operand of an enclosing {@link OpJoin},
 *       {@link OpLeftJoin}, {@link OpConditional}, {@link OpLateral}, {@link OpSequence},
 *       {@link OpUnion}, {@link OpDisjunction}, {@link OpMinus}, {@link OpSemiJoin},
 *       {@link OpAntiJoin} (a subquery boundary is just an {@code OpProject} on one side of
 *       one of these). Sibling variables are collected with
 *       {@link OpVars#mentionedVars(Op)}, which ignores scoping and therefore can only
 *       over-approximate the truly shared set.</li>
 * </ul>
 * With no enclosing projection at all (e.g. {@code SELECT *}) the demand is every variable
 * mentioned in the tree, so O is the full variable set of the BGP.
 *
 * <h2>Safety: O never under-approximates</h2>
 * Every rule above adds variables to the demand; nothing ever removes a variable except a
 * projection, which by SPARQL semantics genuinely hides them. When the walk meets an operator
 * it cannot reason about — {@link OpExt} (the only extension point, so by definition an
 * unrecognised operator), {@link OpService} (evaluated remotely), {@link OpPropFunc} and
 * {@link OpProcedure} (arbitrary Java code consuming the sub-op) — every BGP in that operator's
 * subtree is recorded with O = all of its variables together with a {@link FallbackReason}.
 * Reasons are logged at debug level and counted in {@link Analysis#fallbackReasonCounts()} so
 * their distribution can be reported. A BGP that is looked up but was never analysed (e.g.
 * because ARQ manufactured a fresh {@code OpBGP} at execution time, as it does for quad
 * patterns and for the substituted right side of an index join) likewise yields all of its
 * variables from {@link Analysis#outputVarsOrAll(OpBGP)}.
 *
 * <h2>Storage</h2>
 * {@link #analyze(Op, ExecutionContext)} stores the {@link Analysis} in the execution's
 * {@code Context} under {@link #SYMBOL}. All {@code ExecutionContext} copies made during one
 * query execution share that {@code Context} object, so nested executors see the same table.
 */
public final class AlgebraContextAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(AlgebraContextAnalyzer.class);

    /** Context key under which the per-execution {@link Analysis} is stored. */
    public static final Symbol SYMBOL =
            Symbol.create("at.ac.tuwien.thesis.yannakakis.algebraContextAnalysis");

    private AlgebraContextAnalyzer() {}

    /** Why a BGP received the conservative all-variables answer instead of a computed O. */
    public enum FallbackReason {
        /** An {@link OpExt} — an operator outside the fixed ARQ algebra, hence unrecognised. */
        UNRECOGNISED_OPERATOR,
        /** Inside {@link OpService}: the subtree is shipped to a remote endpoint. */
        SERVICE,
        /** Inside {@link OpPropFunc}: a property-function implementation consumes the sub-op. */
        PROPERTY_FUNCTION,
        /** Inside {@link OpProcedure}: a procedure implementation consumes the sub-op. */
        PROCEDURE
    }

    /** A BGP's computed output variables, plus the fallback reason if it was not computed. */
    public record Entry(Set<Var> outputVars, Optional<FallbackReason> fallbackReason) {
        public Entry {
            outputVars = Collections.unmodifiableSet(new LinkedHashSet<>(outputVars));
            Objects.requireNonNull(fallbackReason);
        }

        public boolean isFallback() { return fallbackReason.isPresent(); }
    }

    /** Result of one analysis run: a lookup table from {@code OpBGP} identity to {@link Entry}. */
    public static final class Analysis {
        private final Map<OpBGP, Entry> entries = new IdentityHashMap<>();
        private final Map<FallbackReason, Integer> reasonCounts = new LinkedHashMap<>();
        private int unanalysedLookups = 0;

        private Analysis() {}

        /** The entry for {@code bgp}, if that exact object was part of the analysed tree. */
        public Optional<Entry> entry(OpBGP bgp) {
            return Optional.ofNullable(entries.get(bgp));
        }

        /** O for {@code bgp}, if that exact object was part of the analysed tree. */
        public Optional<Set<Var>> outputVars(OpBGP bgp) {
            return entry(bgp).map(Entry::outputVars);
        }

        /**
         * O for {@code bgp}, or all of its variables if that object was never analysed. The
         * safe default: a BGP the analyzer never saw gets no early projection.
         */
        public Set<Var> outputVarsOrAll(OpBGP bgp) {
            Entry e = entries.get(bgp);
            if (e != null) return e.outputVars();
            unanalysedLookups++;
            Set<Var> all = varsOf(bgp.getPattern());
            LOG.debug("BGP {} was not part of the analysed tree; using all variables {}", bgp, all);
            return all;
        }

        /** Number of analysed BGP nodes. */
        public int size() { return entries.size(); }

        /** How many analysed BGPs fell back to all variables, per reason (insertion-ordered). */
        public Map<FallbackReason, Integer> fallbackReasonCounts() {
            return Collections.unmodifiableMap(reasonCounts);
        }

        /** Number of {@link #outputVarsOrAll} calls that hit a BGP outside the analysed tree. */
        public int unanalysedLookups() { return unanalysedLookups; }

        private void record(OpBGP bgp, Set<Var> outputVars, Optional<FallbackReason> reason) {
            Entry previous = entries.get(bgp);
            if (previous != null) {
                // The same OpBGP object reachable twice (shared subtree): keep the union of
                // both demands, and a fallback wins over a computed set. Never narrower.
                Set<Var> merged = new LinkedHashSet<>(previous.outputVars());
                merged.addAll(outputVars);
                Optional<FallbackReason> mergedReason = previous.fallbackReason().isPresent()
                        ? previous.fallbackReason() : reason;
                if (mergedReason.isPresent() && previous.fallbackReason().isEmpty()) {
                    reasonCounts.merge(mergedReason.get(), 1, Integer::sum);
                }
                entries.put(bgp, new Entry(merged, mergedReason));
                return;
            }
            reason.ifPresent(r -> reasonCounts.merge(r, 1, Integer::sum));
            entries.put(bgp, new Entry(outputVars, reason));
        }
    }

    // ---- entry points ----------------------------------------------------

    /** Analyse {@code root} and return the table. The tree is read, never modified. */
    public static Analysis analyze(Op root) {
        Objects.requireNonNull(root, "root");
        Analysis analysis = new Analysis();
        // No projection above the root: everything the tree mentions is demanded.
        Set<Var> rootDemand = new LinkedHashSet<>(OpVars.mentionedVars(root));
        walk(root, rootDemand, Optional.empty(), analysis);
        return analysis;
    }

    /**
     * Analyse {@code root} and store the table in {@code execCxt.getContext()} under
     * {@link #SYMBOL}, replacing any earlier table.
     */
    public static Analysis analyze(Op root, ExecutionContext execCxt) {
        Analysis analysis = analyze(root);
        execCxt.getContext().set(SYMBOL, analysis);
        return analysis;
    }

    /**
     * A table with no entries: every lookup misses and {@link Analysis#outputVarsOrAll}
     * yields all variables. Used by the executor when the analysis itself fails, so the
     * failure is contained and not retried on every nested call.
     */
    public static Analysis emptyAnalysis() {
        return new Analysis();
    }

    /** The table stored by {@link #analyze(Op, ExecutionContext)}, if any. */
    public static Optional<Analysis> lookup(ExecutionContext execCxt) {
        Object stored = execCxt.getContext().get(SYMBOL);
        return stored instanceof Analysis a ? Optional.of(a) : Optional.empty();
    }

    /**
     * Convenience for executors: O for {@code bgp} from the stored table, or all of the BGP's
     * variables when there is no table or the BGP is not in it.
     */
    public static Set<Var> outputVarsOrAll(ExecutionContext execCxt, OpBGP bgp) {
        return lookup(execCxt)
                .map(a -> a.outputVarsOrAll(bgp))
                .orElseGet(() -> varsOf(bgp.getPattern()));
    }

    /** All variables of a basic pattern, in first-occurrence order. */
    public static Set<Var> varsOf(BasicPattern pattern) {
        Set<Var> vars = new LinkedHashSet<>();
        for (Triple t : pattern) {
            addVar(vars, t.getSubject());
            addVar(vars, t.getPredicate());
            addVar(vars, t.getObject());
        }
        return vars;
    }

    // ---- the walk ----------------------------------------------------------

    private static void walk(Op op, Set<Var> demand, Optional<FallbackReason> fallback, Analysis analysis) {
        if (op == null) return;
        op.visit(new Walker(demand, fallback, analysis));
    }

    private static void addVar(Set<Var> acc, Node n) {
        if (Var.isVar(n)) acc.add(Var.alloc(n));
    }

    private static Set<Var> union(Set<Var> a, Iterable<Var> b) {
        Set<Var> u = new LinkedHashSet<>(a);
        for (Var v : b) u.add(v);
        return u;
    }

    private static Set<Var> mentioned(Op op) {
        return op == null ? Set.of() : new LinkedHashSet<>(OpVars.mentionedVars(op));
    }

    private static Set<Var> exprVars(ExprList exprs) {
        return exprs == null ? Set.of() : ExprVars.getVarsMentioned(exprs);
    }

    /**
     * One visitor instance per tree node: it carries the demand reaching that node and the
     * fallback state of the enclosing subtree, computes the demand for each child, and
     * recurses. Implements {@link OpVisitor} directly (not {@code OpVisitorBase}) so every
     * operator in the algebra must be handled explicitly; a new operator added to ARQ will
     * fail to compile here rather than silently skip a BGP.
     */
    private static final class Walker implements OpVisitor {
        private final Set<Var> demand;
        private final Optional<FallbackReason> fallback;
        private final Analysis analysis;

        Walker(Set<Var> demand, Optional<FallbackReason> fallback, Analysis analysis) {
            this.demand = demand;
            this.fallback = fallback;
            this.analysis = analysis;
        }

        private void recurse(Op child, Set<Var> childDemand) {
            walk(child, childDemand, fallback, analysis);
        }

        private void recurseFallback(Op child, FallbackReason reason, Op cause) {
            // An enclosing fallback keeps its (outer) reason; otherwise this operator is it.
            Optional<FallbackReason> r = fallback.isPresent() ? fallback : Optional.of(reason);
            if (fallback.isEmpty()) {
                LOG.debug("operator {} cannot be analysed ({}); every BGP below it keeps all variables",
                        cause.getName(), reason);
            }
            walk(child, demand, r, analysis);
        }

        // ---- Op0 -----------------------------------------------------------

        @Override
        public void visit(OpBGP opBGP) {
            Set<Var> all = varsOf(opBGP.getPattern());
            if (fallback.isPresent()) {
                LOG.debug("BGP {} -> all variables {} (fallback: {})", opBGP, all, fallback.get());
                analysis.record(opBGP, all, fallback);
                return;
            }
            Set<Var> out = new LinkedHashSet<>();
            for (Var v : all) if (demand.contains(v)) out.add(v);
            LOG.debug("BGP {} -> output variables {} of {}", opBGP, out, all);
            analysis.record(opBGP, out, Optional.empty());
        }

        @Override public void visit(OpQuadPattern quadPattern) {}   // no OpBGP inside
        @Override public void visit(OpQuadBlock quadBlock) {}
        @Override public void visit(OpTriple opTriple) {}
        @Override public void visit(OpQuad opQuad) {}
        @Override public void visit(OpPath opPath) {}
        @Override public void visit(OpTable opTable) {}
        @Override public void visit(OpNull opNull) {}
        @Override public void visit(OpDatasetNames dsNames) {}

        // ---- Op1 -----------------------------------------------------------

        @Override
        public void visit(OpProcedure opProc) {
            recurseFallback(opProc.getSubOp(), FallbackReason.PROCEDURE, opProc);
        }

        @Override
        public void visit(OpPropFunc opPropFunc) {
            recurseFallback(opPropFunc.getSubOp(), FallbackReason.PROPERTY_FUNCTION, opPropFunc);
        }

        @Override
        public void visit(OpFilter opFilter) {
            recurse(opFilter.getSubOp(), union(demand, exprVars(opFilter.getExprs())));
        }

        @Override
        public void visit(OpGraph opGraph) {
            Set<Var> d = new LinkedHashSet<>(demand);
            addVar(d, opGraph.getNode());
            recurse(opGraph.getSubOp(), d);
        }

        @Override
        public void visit(OpService opService) {
            recurseFallback(opService.getSubOp(), FallbackReason.SERVICE, opService);
        }

        @Override
        public void visit(OpLabel opLabel) {
            if (opLabel.hasSubOp()) recurse(opLabel.getSubOp(), demand);
        }

        @Override
        public void visit(OpAssign opAssign) {
            Set<Var> d = new LinkedHashSet<>(demand);
            opAssign.getVarExprList().forEachExpr((v, e) -> ExprVars.varsMentioned(d, e));
            recurse(opAssign.getSubOp(), d);
        }

        @Override
        public void visit(OpExtend opExtend) {
            Set<Var> d = new LinkedHashSet<>(demand);
            opExtend.getVarExprList().forEachExpr((v, e) -> ExprVars.varsMentioned(d, e));
            recurse(opExtend.getSubOp(), d);
        }

        @Override
        public void visit(OpUnfold opUnfold) {
            Set<Var> d = new LinkedHashSet<>(demand);
            ExprVars.varsMentioned(d, opUnfold.getExpr());
            recurse(opUnfold.getSubOp(), d);
        }

        // ---- Op2 -----------------------------------------------------------

        /** Both operands contribute to the output: each side needs D plus the other side's vars. */
        private void symmetric(Op left, Op right, Set<Var> extra) {
            recurse(left, union(union(demand, mentioned(right)), extra));
            recurse(right, union(union(demand, mentioned(left)), extra));
        }

        /** Only the left operand contributes to the output; the right is a filter on it. */
        private void leftOnly(Op left, Op right) {
            recurse(left, union(demand, mentioned(right)));
            recurse(right, mentioned(left));
        }

        @Override public void visit(OpJoin opJoin)               { symmetric(opJoin.getLeft(), opJoin.getRight(), Set.of()); }
        @Override public void visit(OpLeftJoin opLeftJoin)       { symmetric(opLeftJoin.getLeft(), opLeftJoin.getRight(), exprVars(opLeftJoin.getExprs())); }
        @Override public void visit(OpUnion opUnion)             { symmetric(opUnion.getLeft(), opUnion.getRight(), Set.of()); }
        @Override public void visit(OpConditional opCondition)   { symmetric(opCondition.getLeft(), opCondition.getRight(), Set.of()); }
        @Override public void visit(OpLateral opLateral)         { symmetric(opLateral.getLeft(), opLateral.getRight(), Set.of()); }
        @Override public void visit(OpMinus opMinus)             { leftOnly(opMinus.getLeft(), opMinus.getRight()); }
        @Override public void visit(OpSemiJoin opSemiJoin)       { leftOnly(opSemiJoin.getLeft(), opSemiJoin.getRight()); }
        @Override public void visit(OpAntiJoin opAntiJoin)       { leftOnly(opAntiJoin.getLeft(), opAntiJoin.getRight()); }

        // ---- OpN -----------------------------------------------------------

        private void nary(List<Op> elements) {
            for (int i = 0; i < elements.size(); i++) {
                Set<Var> d = new LinkedHashSet<>(demand);
                for (int j = 0; j < elements.size(); j++) {
                    if (j != i) d.addAll(mentioned(elements.get(j)));
                }
                recurse(elements.get(i), d);
            }
        }

        @Override public void visit(OpSequence opSequence)       { nary(opSequence.getElements()); }
        @Override public void visit(OpDisjunction opDisjunction) { nary(opDisjunction.getElements()); }

        @Override
        public void visit(OpExt opExt) {
            recurseFallback(opExt.effectiveOp(), FallbackReason.UNRECOGNISED_OPERATOR, opExt);
        }

        // ---- modifiers -----------------------------------------------------

        @Override public void visit(OpList opList)         { recurse(opList.getSubOp(), demand); }
        @Override public void visit(OpReduced opReduced)   { recurse(opReduced.getSubOp(), demand); }
        @Override public void visit(OpDistinct opDistinct) { recurse(opDistinct.getSubOp(), demand); }
        @Override public void visit(OpSlice opSlice)       { recurse(opSlice.getSubOp(), demand); }

        @Override
        public void visit(OpOrder opOrder) {
            recurse(opOrder.getSubOp(), union(demand, ExprVars.getVarsMentioned(opOrder.getConditions())));
        }

        @Override
        public void visit(OpTopN opTop) {
            recurse(opTop.getSubOp(), union(demand, ExprVars.getVarsMentioned(opTop.getConditions())));
        }

        @Override
        public void visit(OpProject opProject) {
            // A projection hides everything it does not list: the demand is reset, not extended.
            recurse(opProject.getSubOp(), new LinkedHashSet<>(opProject.getVars()));
        }

        @Override
        public void visit(OpGroup opGroup) {
            // The group's output is its group keys plus aggregate results, so nothing above it
            // can demand anything else from below. Below it we need: plain group-by variables,
            // variables inside group-by expressions, and variables inside aggregate arguments.
            Set<Var> d = new LinkedHashSet<>();
            opGroup.getGroupVars().forEachVarExpr((v, e) -> {
                if (e == null) d.add(v); else ExprVars.varsMentioned(d, e);
            });
            for (ExprAggregator agg : opGroup.getAggregators()) {
                d.addAll(exprVars(agg.getAggregator().getExprList()));   // null for COUNT(*)
            }
            recurse(opGroup.getSubOp(), d);
        }
    }
}
