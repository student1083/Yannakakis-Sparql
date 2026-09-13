package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A relation: a BAG of tuples, stored as distinct tuples each annotated with a
 * positive multiplicity. A tuple maps each schema variable to an RDF Node.
 * Immutable; all operations return new Relations.
 *
 * <p>The annotations form the counting semiring (ℕ, +, ×) of Wang et al.'s
 * framework: {@link #project} adds the counts of tuples that collapse, {@link #join}
 * multiplies the counts of the tuples it combines, {@link #semijoin} keeps the left
 * count. With these rules an early projection commutes with the later joins, so
 * π_O of the final result carries exactly the multiplicities SPARQL bag semantics
 * assigns to a projected BGP. A relation matched from a graph starts with every
 * count 1 (a graph is a set of triples). {@link #distinct()} collapses every count
 * to 1 — the only place where DISTINCT enters; the algorithms never branch on it.
 */
public final class Relation {

    private final Set<Var> schema;
    private final Map<Map<Var, Node>, Integer> rows;   // distinct tuple -> multiplicity (> 0)

    private Relation(Set<Var> schema, Map<Map<Var, Node>, Integer> rows) {
        this.schema = Collections.unmodifiableSet(new LinkedHashSet<>(schema));
        this.rows = rows;
    }

    public Set<Var> schema()  { return schema; }
    /** Number of distinct tuples. */
    public int rowCount()     { return rows.size(); }
    /** Sum of all multiplicities: the size of the bag. */
    public long bagSize() {
        long n = 0;
        for (int c : rows.values()) n += c;
        return n;
    }
    /** The distinct tuples. */
    public Set<Map<Var, Node>> rows() { return Collections.unmodifiableSet(rows.keySet()); }
    /** The distinct tuples with their multiplicities. */
    public Map<Map<Var, Node>, Integer> counts() { return Collections.unmodifiableMap(rows); }
    /** Multiplicity of {@code row}, 0 if absent. */
    public int count(Map<Var, Node> row) { return rows.getOrDefault(row, 0); }

    public static Relation empty(Set<Var> schema) {
        return new Relation(schema, new HashMap<>());
    }

    /** Build a relation from a set of materialized rows, each with multiplicity 1. */
    public static Relation fromRows(Set<Var> schema, Set<Map<Var, Node>> rows) {
        Map<Map<Var, Node>, Integer> counted = new HashMap<>(rows.size() * 2);
        for (Map<Var, Node> r : rows) counted.put(r, 1);
        return new Relation(schema, counted);
    }

    /** Build a relation from rows with explicit multiplicities (each must be positive). */
    public static Relation fromCounts(Set<Var> schema, Map<Map<Var, Node>, Integer> counts) {
        Map<Map<Var, Node>, Integer> copy = new HashMap<>(counts.size() * 2);
        for (Map.Entry<Map<Var, Node>, Integer> e : counts.entrySet()) {
            if (e.getValue() <= 0) throw new IllegalArgumentException("multiplicity must be positive: " + e);
            copy.put(e.getKey(), e.getValue());
        }
        return new Relation(schema, copy);
    }

    /** The join identity: one empty tuple with multiplicity 1. Used for an empty BGP. */
    public static Relation unit() {
        Map<Map<Var, Node>, Integer> r = new HashMap<>();
        r.put(new HashMap<>(), 1);
        return new Relation(Set.of(), r);
    }

    /** Variables common to both relations, in this relation's iteration order. */
    private List<Var> sharedVars(Relation other) {
        List<Var> s = new ArrayList<>();
        for (Var v : schema) if (other.schema.contains(v)) s.add(v);
        return s;
    }

    private static List<Node> key(Map<Var, Node> row, List<Var> vars) {
        List<Node> key = new ArrayList<>(vars.size());
        for (Var v : vars) key.add(row.get(v));
        return key;
    }

    /**
     * Projection π_{vars}: keeps the columns in {@code vars} ∩ schema (schema order).
     * Tuples that become equal collapse into one whose multiplicity is the sum.
     */
    public Relation project(Set<Var> vars) {
        List<Var> kept = new ArrayList<>();
        for (Var v : schema) if (vars.contains(v)) kept.add(v);
        if (kept.size() == schema.size()) return this;

        Map<Map<Var, Node>, Integer> out = new HashMap<>();
        for (Map.Entry<Map<Var, Node>, Integer> e : rows.entrySet()) {
            Map<Var, Node> m = new HashMap<>(kept.size() * 2);
            for (Var v : kept) m.put(v, e.getKey().get(v));
            out.merge(m, e.getValue(), Integer::sum);
        }
        return new Relation(new LinkedHashSet<>(kept), out);
    }

    /** Every multiplicity set to 1: the final step of a DISTINCT query. */
    public Relation distinct() {
        Map<Map<Var, Node>, Integer> out = new HashMap<>(rows.size() * 2);
        for (Map<Var, Node> r : rows.keySet()) out.put(r, 1);
        return new Relation(schema, out);
    }

    /** Semijoin: keep my tuples (with their multiplicities) that have a matching partner in {@code other}. */
    public Relation semijoin(Relation other) {
        List<Var> shared = sharedVars(other);
        if (shared.isEmpty()) {
            // agreement on no variables is trivial: all of me survive iff other is non-empty
            return other.rows.isEmpty() ? empty(schema) : this;
        }
        Set<List<Node>> keys = new HashSet<>();
        for (Map<Var, Node> s : other.rows.keySet()) keys.add(key(s, shared));

        Map<Map<Var, Node>, Integer> kept = new HashMap<>();
        for (Map.Entry<Map<Var, Node>, Integer> e : rows.entrySet()) {
            if (keys.contains(key(e.getKey(), shared))) kept.put(e.getKey(), e.getValue());
        }
        return new Relation(schema, kept);
    }

    /**
     * Natural join (hash join on shared variables; cross product if none). The
     * multiplicity of a combined tuple is the product of the two inputs' counts.
     */
    public Relation join(Relation other) {
        List<Var> shared = sharedVars(other);
        Set<Var> outSchema = new LinkedHashSet<>(schema);
        outSchema.addAll(other.schema);
        Map<Map<Var, Node>, Integer> out = new HashMap<>();

        if (shared.isEmpty()) {
            for (Map.Entry<Map<Var, Node>, Integer> r : rows.entrySet()) {
                for (Map.Entry<Map<Var, Node>, Integer> s : other.rows.entrySet()) {
                    combine(out, r, s);
                }
            }
            return new Relation(outSchema, out);
        }

        Map<List<Node>, List<Map.Entry<Map<Var, Node>, Integer>>> index = new HashMap<>();
        for (Map.Entry<Map<Var, Node>, Integer> s : other.rows.entrySet()) {
            index.computeIfAbsent(key(s.getKey(), shared), k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<Map<Var, Node>, Integer> r : rows.entrySet()) {
            List<Map.Entry<Map<Var, Node>, Integer>> matches = index.get(key(r.getKey(), shared));
            if (matches == null) continue;
            for (Map.Entry<Map<Var, Node>, Integer> s : matches) combine(out, r, s);
        }
        return new Relation(outSchema, out);
    }

    private static void combine(Map<Map<Var, Node>, Integer> out,
                                Map.Entry<Map<Var, Node>, Integer> r, Map.Entry<Map<Var, Node>, Integer> s) {
        Map<Var, Node> m = new HashMap<>(r.getKey());
        m.putAll(s.getKey());
        // Two distinct input pairs can only produce the same output tuple if they agree
        // on every column, i.e. they are the same pair — but merge anyway, for safety.
        out.merge(m, r.getValue() * s.getValue(), Integer::sum);
    }

    // ---- Test/demo convenience -----------------------------------------

    /** Build a relation; row values are turned into IRIs (kind is irrelevant for joins). */
    public static Builder builder(String... varNames) { return new Builder(varNames); }

    public static final class Builder {
        private final List<Var> vars = new ArrayList<>();
        private final Map<Map<Var, Node>, Integer> rows = new HashMap<>();

        private Builder(String... varNames) {
            for (String n : varNames) vars.add(Var.alloc(n));
        }
        /** Adds one occurrence of the row; adding the same row again raises its multiplicity. */
        public Builder row(String... values) { return rowTimes(1, values); }
        /** Adds {@code count} occurrences of the row. */
        public Builder rowTimes(int count, String... values) {
            if (values.length != vars.size())
                throw new IllegalArgumentException("row arity mismatch");
            if (count <= 0) throw new IllegalArgumentException("multiplicity must be positive");
            Map<Var, Node> m = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i++) {
                m.put(vars.get(i), NodeFactory.createURI("http://example.org/" + values[i]));
            }
            rows.merge(m, count, Integer::sum);
            return this;
        }
        public Relation build() { return new Relation(new LinkedHashSet<>(vars), new HashMap<>(rows)); }
    }

    /** Equal iff same schema and same bag: same distinct tuples with the same multiplicities. */
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Relation other)) return false;
        return schema.equals(other.schema) && rows.equals(other.rows);
    }
    @Override public int hashCode() { return Objects.hash(schema, rows); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("Relation").append(schema)
                .append(" (").append(rows.size()).append(" distinct rows, bag size ").append(bagSize()).append(")\n");
        for (Map.Entry<Map<Var, Node>, Integer> r : rows.entrySet()) {
            sb.append("  ").append(r.getKey());
            if (r.getValue() != 1) sb.append(" ×").append(r.getValue());
            sb.append('\n');
        }
        return sb.toString();
    }
}
