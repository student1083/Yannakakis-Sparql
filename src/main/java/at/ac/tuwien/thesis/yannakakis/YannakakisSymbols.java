package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.Symbol;

/**
 * Context symbols that tune the executor. Set them on {@code ARQ.getContext()} for every
 * query, or on a dataset / query-execution context for one; the executor reads them from
 * the execution's context, so the per-execution merged context wins as usual in ARQ.
 * Values may be given as numbers or as strings (as with ARQ's own symbols), which is how
 * Chapter 5 varies them from the command line.
 */
public final class YannakakisSymbols {

    private YannakakisSymbols() {}

    private static final String NS = "at.ac.tuwien.thesis.yannakakis.";

    /**
     * Threshold θ of the dimension-fusion rule ({@link DimensionFusion}): the leaves under
     * a node R are fused when est(R) ≥ θ · ∏ est(leaf). Wang et al. §5.1 justify fusion by
     * "a join or semi-join with the large relation can be more costly than performing a
     * Cartesian product of the small relations", i.e. by the product of the small sizes
     * being below the large size — θ = 1 is that break-even, and the default. θ &gt; 1
     * demands a margin, {@code Double.POSITIVE_INFINITY} (or any NaN/negative value)
     * switches fusion off.
     */
    public static final Symbol FUSION_RATIO = Symbol.create(NS + "fusionRatio");
    public static final double FUSION_RATIO_DEFAULT = 1.0;

    /**
     * Bound for the counting estimators ({@link BoundedCountEstimator},
     * {@link Tdb2CardinalityEstimator} without statistics): a pattern with at least this
     * many matches is reported as exactly this many. Larger values make the estimates
     * sharper at the price of a longer index scan per pattern and binding.
     */
    public static final Symbol ESTIMATE_LIMIT = Symbol.create(NS + "estimateLimit");
    public static final long ESTIMATE_LIMIT_DEFAULT = 10_000;

    /**
     * A {@link CardinalityEstimator} instance to use instead of the one
     * {@link CardinalityEstimator#forGraph} would pick for the active graph. Meant for
     * tests and experiments (e.g. feeding hand-written cardinalities, or turning the
     * heuristics off with {@link CardinalityEstimator#UNKNOWN}).
     */
    public static final Symbol CARDINALITY_ESTIMATOR = Symbol.create(NS + "cardinalityEstimator");

    /** θ from the context, {@link #FUSION_RATIO_DEFAULT} if unset. */
    public static double fusionRatio(Context cxt) {
        Object v = cxt.get(FUSION_RATIO);
        if (v == null) return FUSION_RATIO_DEFAULT;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    /** The counting bound from the context, {@link #ESTIMATE_LIMIT_DEFAULT} if unset. */
    public static long estimateLimit(Context cxt) {
        return cxt.getLong(ESTIMATE_LIMIT, ESTIMATE_LIMIT_DEFAULT);
    }
}
