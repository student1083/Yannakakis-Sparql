package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.atlas.lib.tuple.Tuple;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.dboe.sys.Names;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.sse.Item;
import org.apache.jena.sparql.sse.ItemList;
import org.apache.jena.sparql.sse.SSE;
import org.apache.jena.tdb2.store.GraphTDB;
import org.apache.jena.tdb2.store.GraphViewSwitchable;
import org.apache.jena.tdb2.store.NodeId;
import org.apache.jena.tdb2.store.nodetupletable.NodeTupleTable;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CardinalityEstimator} for TDB2 graphs. Two sources, in order of preference:
 * <ol>
 *   <li><b>The store's statistics file</b> {@code stats.opt} (written by
 *       {@code tdb2.tdbstats}, looked up where TDB2 itself looks: the storage directory
 *       {@code Data-000N}, then its container). It records the number of triples per
 *       predicate, per {@code rdf:type} object, and in total, so it answers exactly the
 *       patterns a bounded scan is worst at: {@code ?s <p> ?o}, {@code ?s rdf:type <T>} and
 *       {@code ?s ?p ?o}. The statistics describe the default graph, so they are only
 *       consulted for the default graph. TDB2 keeps no statistics unless that file was
 *       generated; then this source is simply absent.</li>
 *   <li><b>A bounded index scan on {@code NodeId} tuples</b>
 *       ({@code NodeTupleTable.findAsNodeIds}): the same B+tree range scan
 *       {@code Graph.find} performs, minus the node-table decoding of every tuple, counted
 *       up to the bound. A pattern mentioning a term the node table does not know is 0
 *       without touching an index.</li>
 * </ol>
 * The cast to {@code GraphTDB} is confined to {@link #forGraph}; see
 * {@link CardinalityEstimator} for why nothing better is reachable through {@code Graph}.
 * Statistics files are parsed once per path and modification time.
 */
public final class Tdb2CardinalityEstimator implements CardinalityEstimator {

    private static final Logger LOG = LoggerFactory.getLogger(Tdb2CardinalityEstimator.class);

    /** The contents of a {@code stats.opt}: (count &lt;p&gt; n), ((VAR rdf:type &lt;T&gt;) n), and the total. */
    record Statistics(Map<Node, Long> predicates, Map<Node, Long> types, long total) {
        Statistics {
            predicates = Collections.unmodifiableMap(new HashMap<>(predicates));
            types = Collections.unmodifiableMap(new HashMap<>(types));
        }
    }

    private record StatsKey(String path, long lastModified) {}

    private static final Map<StatsKey, Optional<Statistics>> STATS_CACHE = new ConcurrentHashMap<>();

    private final NodeTupleTable table;
    private final Node graphName;          // null: default graph (triple table); else quad table, Node.ANY for the union graph
    private final Statistics stats;        // null when the store has no usable stats.opt or the graph is not the default graph
    private final long limit;

    Tdb2CardinalityEstimator(NodeTupleTable table, Node graphName, Statistics stats, long limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive: " + limit);
        this.table = table;
        this.graphName = graphName;
        this.stats = stats;
        this.limit = limit;
    }

    /**
     * The estimator for a TDB2 graph ({@code GraphTDB}, or a {@code GraphViewSwitchable}
     * over a switchable dataset, unwrapped to the current {@code GraphTDB}); empty for
     * any other graph.
     */
    public static Optional<Tdb2CardinalityEstimator> forGraph(Graph graph, long limit) {
        Graph g = graph;
        if (g instanceof GraphViewSwitchable switchable) g = switchable.getBaseGraph();
        if (!(g instanceof GraphTDB tdb)) return Optional.empty();

        Node gn = tdb.getGraphName();                       // null for the default graph
        Node tableGraph = gn == null ? null : Quad.isUnionGraph(gn) ? Node.ANY : gn;
        Statistics stats = gn == null ? statistics(tdb.getDSG().getLocation()).orElse(null) : null;
        return Optional.of(new Tdb2CardinalityEstimator(tdb.getNodeTupleTable(), tableGraph, stats, limit));
    }

    /** Whether a statistics file backs the predicate / type / total estimates. */
    public boolean hasStatistics() { return stats != null; }

    public long limit() { return limit; }

    @Override
    public long estimate(Triple pattern) {
        Node s = BoundedCountEstimator.any(pattern.getSubject());
        Node p = BoundedCountEstimator.any(pattern.getPredicate());
        Node o = BoundedCountEstimator.any(pattern.getObject());

        if (stats != null && s == Node.ANY) {
            if (p == Node.ANY && o == Node.ANY && stats.total() >= 0) return stats.total();
            if (p != Node.ANY && o == Node.ANY) {
                Long n = stats.predicates().get(p);
                if (n != null) return n;
            }
            if (RDF.Nodes.type.equals(p) && o != Node.ANY) {
                Long n = stats.types().get(o);
                if (n != null) return n;
            }
        }
        return boundedCount(s, p, o);
    }

    private long boundedCount(Node s, Node p, Node o) {
        Iterator<Tuple<NodeId>> it = graphName == null
                ? table.findAsNodeIds(s, p, o)
                : table.findAsNodeIds(graphName, s, p, o);
        long n = 0;
        while (n < limit && it.hasNext()) {
            it.next();
            n++;
        }
        return n;
    }

    // ---- stats.opt ---------------------------------------------------------

    /**
     * The statistics of the store at {@code location}, from {@code stats.opt} in the
     * storage directory or, failing that, in its parent (the container directory of a
     * switchable store). Empty for in-memory stores and stores without the file.
     */
    static Optional<Statistics> statistics(Location location) {
        if (location == null || location.isMem()) return Optional.empty();
        File storage = new File(location.getDirectoryPath());
        for (File dir : new File[] { storage, storage.getParentFile() }) {
            if (dir == null) continue;
            File f = new File(dir, Names.optStats);
            if (f.isFile()) return load(f);
        }
        return Optional.empty();
    }

    private static Optional<Statistics> load(File f) {
        return STATS_CACHE.computeIfAbsent(new StatsKey(f.getAbsolutePath(), f.lastModified()), k -> {
            try {
                return Optional.of(parse(SSE.readFile(f.getAbsolutePath())));
            } catch (RuntimeException e) {
                LOG.warn("ignoring unreadable statistics file {}", f, e);
                return Optional.empty();
            }
        });
    }

    /**
     * Parses the {@code (stats (meta ... (count N)) (<p> n) ... ((VAR rdf:type <T>) n) ... (other 0))}
     * form written by {@code org.apache.jena.tdb2.solver.stats.Stats}. Unknown entries are skipped.
     */
    static Statistics parse(Item stats) {
        if (!stats.isTagged("stats")) throw new IllegalArgumentException("not a stats file: " + stats);
        Map<Node, Long> predicates = new HashMap<>();
        Map<Node, Long> types = new HashMap<>();
        long total = -1;
        for (Item elt : stats.getList().cdr()) {             // skip the tag
            if (elt.isTagged("meta")) {
                Item count = Item.find(elt.getList(), "count");
                if (count != null && count.getList().size() == 2) total = count.getList().get(1).asLong();
                continue;
            }
            if (!elt.isList() || elt.getList().size() != 2) continue;
            Item key = elt.getList().get(0);
            long n;
            try {
                n = elt.getList().get(1).asLong();
            } catch (RuntimeException e) {
                continue;
            }
            if (key.isNode() && key.getNode().isURI()) {
                predicates.put(key.getNode(), n);
            } else if (key.isList() && key.getList().size() == 3) {
                ItemList triple = key.getList();
                Item pred = triple.get(1), obj = triple.get(2);
                if (pred.isNode() && RDF.Nodes.type.equals(pred.getNode()) && obj.isNode()) {
                    types.put(obj.getNode(), n);
                }
            }
        }
        return new Statistics(predicates, types, total);
    }
}
