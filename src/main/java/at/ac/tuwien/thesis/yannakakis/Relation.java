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
 * A relation: a SET of tuples (a graph is a set of triples, so a single BGP
 * yields set semantics — bag/DISTINCT handling lives in ARQ, around the BGP).
 * A tuple maps each schema variable to an RDF Node. Immutable; all operations
 * return new Relations.
 */
public final class Relation {

    private final Set<Var> schema;
    private final Set<Map<Var, Node>> rows;

    private Relation(Set<Var> schema, Set<Map<Var, Node>> rows) {
        this.schema = Collections.unmodifiableSet(new LinkedHashSet<>(schema));
        this.rows = rows;
    }

    public Set<Var> schema()  { return schema; }
    public int rowCount()     { return rows.size(); }
    public Set<Map<Var, Node>> rows() { return Collections.unmodifiableSet(rows); }

    public static Relation empty(Set<Var> schema) {
        return new Relation(schema, new HashSet<>());
    }

    /** Build a relation directly from materialized rows of RDF nodes. */
    public static Relation fromRows(Set<Var> schema, Set<Map<Var, Node>> rows) {
        return new Relation(schema, rows);
    }

    /** The join identity: one empty tuple. Used for an empty BGP. */
    public static Relation unit() {
        Set<Map<Var, Node>> r = new HashSet<>();
        r.add(new HashMap<>());
        return new Relation(Set.of(), r);
    }

    /** Variables common to both relations, in this relation's iteration order. */
    private List<Var> sharedVars(Relation other) {
        List<Var> s = new ArrayList<>();
        for (Var v : schema) if (other.schema.contains(v)) s.add(v);
        return s;
    }

    private static List<Node> project(Map<Var, Node> row, List<Var> vars) {
        List<Node> key = new ArrayList<>(vars.size());
        for (Var v : vars) key.add(row.get(v));
        return key;
    }

    /** Semijoin: keep my tuples that have a matching partner in {@code other}. */
    public Relation semijoin(Relation other) {
        List<Var> shared = sharedVars(other);
        if (shared.isEmpty()) {
            // agreement on no variables is trivial: all of me survive iff other is non-empty
            return other.rows.isEmpty() ? empty(schema) : this;
        }
        Set<List<Node>> keys = new HashSet<>();
        for (Map<Var, Node> s : other.rows) keys.add(project(s, shared));

        Set<Map<Var, Node>> kept = new HashSet<>();
        for (Map<Var, Node> r : rows) {
            if (keys.contains(project(r, shared))) kept.add(r);
        }
        return new Relation(schema, kept);
    }

    /** Natural join (hash join on shared variables; cross product if none). */
    public Relation join(Relation other) {
        List<Var> shared = sharedVars(other);
        Set<Var> outSchema = new LinkedHashSet<>(schema);
        outSchema.addAll(other.schema);
        Set<Map<Var, Node>> out = new HashSet<>();

        if (shared.isEmpty()) {
            for (Map<Var, Node> r : rows) {
                for (Map<Var, Node> s : other.rows) {
                    Map<Var, Node> m = new HashMap<>(r);
                    m.putAll(s);
                    out.add(m);
                }
            }
            return new Relation(outSchema, out);
        }

        Map<List<Node>, List<Map<Var, Node>>> index = new HashMap<>();
        for (Map<Var, Node> s : other.rows) {
            index.computeIfAbsent(project(s, shared), k -> new ArrayList<>()).add(s);
        }
        for (Map<Var, Node> r : rows) {
            List<Map<Var, Node>> matches = index.get(project(r, shared));
            if (matches == null) continue;
            for (Map<Var, Node> s : matches) {
                Map<Var, Node> m = new HashMap<>(r);
                m.putAll(s);
                out.add(m);
            }
        }
        return new Relation(outSchema, out);
    }

    // ---- Test/demo convenience -----------------------------------------

    /** Build a relation; row values are turned into IRIs (kind is irrelevant for joins). */
    public static Builder builder(String... varNames) { return new Builder(varNames); }

    public static final class Builder {
        private final List<Var> vars = new ArrayList<>();
        private final Set<Map<Var, Node>> rows = new HashSet<>();

        private Builder(String... varNames) {
            for (String n : varNames) vars.add(Var.alloc(n));
        }
        public Builder row(String... values) {
            if (values.length != vars.size())
                throw new IllegalArgumentException("row arity mismatch");
            Map<Var, Node> m = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i++) {
                m.put(vars.get(i), NodeFactory.createURI("http://example.org/" + values[i]));
            }
            rows.add(m);
            return this;
        }
        public Relation build() { return new Relation(new LinkedHashSet<>(vars), rows); }
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Relation other)) return false;
        return schema.equals(other.schema) && rows.equals(other.rows);
    }
    @Override public int hashCode() { return Objects.hash(schema, rows); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("Relation").append(schema)
                .append(" (").append(rows.size()).append(" rows)\n");
        for (Map<Var, Node> r : rows) sb.append("  ").append(r).append('\n');
        return sb.toString();
    }
}