package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.ARQ;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom ARQ physical executor. Intercepts BGP evaluation: alpha-acyclic BGPs
 * are evaluated with the standalone Yannakakis evaluator; cyclic BGPs (and the
 * no-active-graph case) fall through to stock ARQ unchanged.
 */
public class YannakakisOpExecutor extends OpExecutor {

    public static final OpExecutorFactory FACTORY = YannakakisOpExecutor::new;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static OpExecutorFactory previous;

    public YannakakisOpExecutor(ExecutionContext execCxt) {
        super(execCxt);
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
        return new Stage(pattern, input, execCxt);
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
    public static void resetCounter() { COUNTER.set(0); }

    // ---- per-input-binding evaluation -----------------------------------

    private static final class Stage extends QueryIterRepeatApply {
        private final BasicPattern pattern;
        private final Map<Object, Optional<JoinTree>> joinTreeCache = new HashMap<>();

        Stage(BasicPattern pattern, QueryIterator input, ExecutionContext execCxt) {
            super(input, execCxt);
            this.pattern = pattern;
        }

        @Override
        protected QueryIterator nextStage(Binding binding) {
            // 1. substitute the incoming binding into the patterns
            BasicPattern bound = new BasicPattern();
            for (Triple t : pattern) bound.add(substitute(t, binding));

            // 2. build hypergraph + join tree of the (substituted) BGP; the tree only
            // depends on the BGP's shape (which positions are variables and which
            // variables they are), not on the concrete bound node values, so it is
            // cached per shape for the lifetime of this Stage.
            Optional<JoinTree> jt = joinTreeCache.computeIfAbsent(shapeKey(bound),
                    k -> GyoReduction.decompose(QueryHypergraph.fromBasicPattern(bound)));

            // 3. materialize each pattern against the live graph
            Graph graph = getExecContext().getActiveGraph();
            Map<Integer, Relation> rels = new HashMap<>();
            int id = 0;
            for (Triple t : bound) rels.put(id++, matchTriple(graph, t));

            // 4. evaluate via Yannakakis; jt is never empty here. Lemma: binding
            // variables to constants only deletes vertices from the query
            // hypergraph and never adds any, so if the unsubstituted pattern
            // (checked acyclic in execute() above) has a join tree, that same
            // tree's running-intersection property survives for every vertex
            // remaining after substitution. Hence GyoReduction.decompose on the
            // substituted pattern always returns a non-empty Optional; see
            // GyoReductionTest#acyclicityPreservedUnderBinding.
            Relation result = jt.map(tree -> YannakakisEvaluator.evaluate(tree, rels))
                    .orElseThrow(() -> new IllegalStateException(
                            "unreachable: binding variables to constants only deletes hypergraph "
                                    + "vertices, never adds any, so an acyclic BGP's join tree survives "
                                    + "substitution intact (see GyoReductionTest#acyclicityPreservedUnderBinding)"));

            // 5. extend the input binding with each result row
            List<Binding> out = new ArrayList<>(result.rowCount());
            for (Map<Var, Node> row : result.rows()) {
                BindingBuilder bb = BindingFactory.builder(binding);
                for (Map.Entry<Var, Node> e : row.entrySet()) bb.add(e.getKey(), e.getValue());
                out.add(bb.build());
            }
            return QueryIterPlainWrapper.create(out.iterator(), getExecContext());
        }
    }

    // ---- helpers ---------------------------------------------------------

    /** Marks a non-variable (concrete term) position in a BGP shape key. */
    private static final Object CONST = new Object();

    /**
     * Shape key for the join-tree cache: for each triple, the kind of each position
     * (variable, or concrete term) and, for variables, the variable identity. Two
     * bound BGPs that bind the same set of variables in the same triple positions
     * produce equal keys regardless of which concrete nodes they were bound to.
     */
    private static Object shapeKey(BasicPattern bound) {
        List<Object> key = new ArrayList<>(bound.size() * 3);
        for (Triple t : bound) {
            key.add(shapeOf(t.getSubject()));
            key.add(shapeOf(t.getPredicate()));
            key.add(shapeOf(t.getObject()));
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