package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.util.Context;

/**
 * A cheap cardinality estimate for a triple pattern against the active graph, obtained
 * <em>before</em> the pattern is materialised into a {@link Relation}. The estimates
 * steer plan shape only ({@link QueryClassifier#classify(QueryHypergraph, Set, Map)},
 * {@link DimensionFusion}); a wrong estimate can cost time, never correctness.
 *
 * <p><b>What a {@code Graph} offers without a store-specific cast.</b> The generic API
 * has no statistics: {@code Graph.find(s, p, o)} is the only way to learn anything about
 * a pattern's cardinality, and {@code Graph.size()} (exact, O(1) on GraphMem) counts the
 * whole graph — by a full index scan on TDB2 ({@code GraphTDB.graphBaseSize}) — and says
 * nothing about a pattern. Nothing under {@code Graph.getCapabilities()},
 * {@code GraphView.getDataset().getContext()} (TDB2 only stores its {@code OpExecutor}
 * factory there) or the dataset's {@code ReorderTransformation} (whose
 * {@code StatsMatcher} is a private field of {@code ReorderWeighted}) exposes the
 * predicate statistics TDB2 may have. Hence {@link BoundedCountEstimator} is the generic
 * fallback, counting {@code find} results up to a bound, and
 * {@link Tdb2CardinalityEstimator} is the one implementation that casts (to
 * {@code GraphTDB}) in order to read the store's {@code stats.opt} and to count
 * {@code NodeId} tuples without decoding nodes.
 *
 * <p>Estimates are non-negative; counting estimators saturate at their bound, i.e. an
 * estimate equal to the bound means "at least this many". A variable position is a
 * wildcard, so for a pattern repeating a variable the estimate is an upper bound.
 */
@FunctionalInterface
public interface CardinalityEstimator {

    long estimate(Triple pattern);

    /**
     * No information: every pattern estimates to 0. With it the estimate-aware
     * classification degenerates to BGP order and {@link DimensionFusion} never fires.
     */
    CardinalityEstimator UNKNOWN = pattern -> 0L;

    /**
     * The estimator for {@code graph}: the instance stored under
     * {@link YannakakisSymbols#CARDINALITY_ESTIMATOR} if there is one, else the TDB2
     * implementation when the graph is a TDB2 graph, else the bounded {@code find} count.
     * The bound comes from {@link YannakakisSymbols#ESTIMATE_LIMIT}.
     */
    static CardinalityEstimator forGraph(Graph graph, Context cxt) {
        Object configured = cxt.get(YannakakisSymbols.CARDINALITY_ESTIMATOR);
        if (configured instanceof CardinalityEstimator estimator) return estimator;
        long limit = YannakakisSymbols.estimateLimit(cxt);
        return Tdb2CardinalityEstimator.forGraph(graph, limit)
                .<CardinalityEstimator>map(e -> e)
                .orElseGet(() -> new BoundedCountEstimator(graph, limit));
    }
}
