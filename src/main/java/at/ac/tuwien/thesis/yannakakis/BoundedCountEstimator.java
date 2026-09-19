package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 * The in-memory (and generic) {@link CardinalityEstimator}: counts the matches of
 * {@code Graph.find(s, p, o)} up to a bound and reports the bound when it is reached.
 * On GraphMem this is an index lookup plus at most {@code limit} iterator steps, i.e.
 * never more work than materialising the same pattern afterwards. Any {@link Graph}
 * will do; nothing store-specific is touched.
 */
public final class BoundedCountEstimator implements CardinalityEstimator {

    private final Graph graph;
    private final long limit;

    public BoundedCountEstimator(Graph graph, long limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive: " + limit);
        this.graph = graph;
        this.limit = limit;
    }

    public long limit() { return limit; }

    @Override
    public long estimate(Triple pattern) {
        ExtendedIterator<Triple> it = graph.find(any(pattern.getSubject()), any(pattern.getPredicate()), any(pattern.getObject()));
        try {
            long n = 0;
            while (n < limit && it.hasNext()) {
                it.next();
                n++;
            }
            return n;
        } finally {
            it.close();
        }
    }

    static Node any(Node n) { return Var.isVar(n) ? Node.ANY : n; }
}
