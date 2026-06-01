package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryEngineFactory;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.util.Context;

/**
 * Custom ARQ query engine. This is the hook for the thesis work:
 * acyclic BGPs are detected (GYO) and rewritten into a yannakakis+ style
 * sub-plan inside {@link #modifyOp(Op)}; everything else passes through.
 *
 * <p>NOTE: this is a SCAFFOLD for the June milestone. The rewriting itself
 * (GYO acyclicity test, join-tree extraction, semi-join rewrite) goes into
 * {@code YannakakisTransform}, which you will implement. Confirm the exact
 * method signatures against the Javadoc of the jena-arq version you pulled —
 * IntelliJ autocomplete on {@code QueryEngineMain} / {@code QueryEngineFactory}
 * will show them. The extension-point pattern (factory + registry +
 * {@code modifyOp}) is the stable, documented way to plug into ARQ.
 */
public class YannakakisQueryEngine extends QueryEngineMain {

    public YannakakisQueryEngine(Query query, DatasetGraph dataset,
                                 Binding input, Context context) {
        super(query, dataset, input, context);
    }

    public YannakakisQueryEngine(Op op, DatasetGraph dataset,
                                 Binding input, Context context) {
        super(op, dataset, input, context);
    }

    @Override
    protected Op modifyOp(Op op) {
        // Let ARQ do its normal optimisation first ...
        op = super.modifyOp(op);
        // ... then apply the structure-guided rewriting on top.
        return Transformer.transform(new YannakakisTransform(), op);
    }

    // ---- Registration ---------------------------------------------------

    private static final QueryEngineFactory FACTORY = new QueryEngineFactory() {
        @Override
        public boolean accept(Query query, DatasetGraph dataset, Context context) {
            return true; // narrow this later if you only want to handle certain queries
        }

        @Override
        public Plan create(Query query, DatasetGraph dataset,
                           Binding input, Context context) {
            QueryEngineMain engine =
                    new YannakakisQueryEngine(query, dataset, input, context);
            return engine.getPlan();
        }

        @Override
        public boolean accept(Op op, DatasetGraph dataset, Context context) {
            return true;
        }

        @Override
        public Plan create(Op op, DatasetGraph dataset,
                           Binding input, Context context) {
            QueryEngineMain engine =
                    new YannakakisQueryEngine(op, dataset, input, context);
            return engine.getPlan();
        }
    };

    /** Call once at startup to make ARQ use this engine. */
    public static void register() {
        QueryEngineRegistry.addFactory(FACTORY);
    }

    /** Call to restore the stock ARQ engine (useful for A/B benchmarking). */
    public static void unregister() {
        QueryEngineRegistry.removeFactory(FACTORY);
    }
}
