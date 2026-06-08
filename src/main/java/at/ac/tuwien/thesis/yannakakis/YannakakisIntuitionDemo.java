package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YannakakisIntuitionDemo {

    static Node n(String tok) {
        return tok.startsWith("?") ? Var.alloc(tok.substring(1))
                : NodeFactory.createURI("http://example.org/" + tok);
    }
    static Triple t(String s, String p, String o) { return Triple.create(n(s), n(p), n(o)); }

    public static void main(String[] args) {
        // BGP:  ?p1 knows ?p2 . ?p2 livesIn ?city . ?city country ?ctry .
        List<Triple> triples = List.of(
                t("?p1", "knows",   "?p2"),
                t("?p2", "livesIn", "?city"),
                t("?city", "country", "?ctry"));

        // e0: who knows whom. carol->dave is a dead end: dave lives nowhere.
        Relation knows = Relation.builder("p1", "p2")
                .row("alice", "bob")
                .row("bob",   "carol")
                .row("carol", "dave")        // dangles: dave has no livesIn
                .build();

        // e1: where people live. erin lives in oslo but no one knows erin.
        Relation livesIn = Relation.builder("p2", "city")
                .row("bob",   "vienna")
                .row("carol", "graz")
                .row("erin",  "oslo")        // dangles: nobody knows erin
                .build();

        // e2: country of each city. graz has no country row -> kills the carol chain.
        Relation country = Relation.builder("city", "ctry")
                .row("vienna", "austria")
                .row("berlin", "germany")    // dangles: nobody lives in berlin
                .build();

        Map<Integer, Relation> rels = new HashMap<>();
        rels.put(0, knows);
        rels.put(1, livesIn);
        rels.put(2, country);

        BasicPattern bgp = new BasicPattern();
        triples.forEach(bgp::add);
        JoinTree tree = GyoReduction.decompose(QueryHypergraph.fromBasicPattern(bgp)).orElseThrow();

        System.out.println("Join tree:");
        System.out.print(tree.pretty());

        System.out.println("\nStart sizes:        knows=" + knows.rowCount()
                + "  livesIn=" + livesIn.rowCount() + "  country=" + country.rowCount());

        // ---- replicate the evaluator's passes, printing after each ----
        Map<Integer, Relation> r = new HashMap<>(rels);
        List<JoinTree.Node> post = new ArrayList<>();
        List<JoinTree.Node> pre = new ArrayList<>();
        post(tree.root(), post);
        pre(tree.root(), pre);

        for (JoinTree.Node v : post) if (v.parent() != null) {
            int p = v.parent().edge().id(), c = v.edge().id();
            r.put(p, r.get(p).semijoin(r.get(c)));
        }
        System.out.println("After UP semijoin:  " + sizes(r) + "   <- parents pruned by children");

        for (JoinTree.Node v : pre) for (JoinTree.Node c : v.children()) {
            int p = v.edge().id(), cid = c.edge().id();
            r.put(cid, r.get(cid).semijoin(r.get(p)));
        }
        System.out.println("After DOWN semijoin:" + sizes(r) + "   <- every dangling tuple now gone");

        Relation result = YannakakisEvaluator.evaluate(tree, rels);
        System.out.println("\nFinal answer (" + result.rowCount() + " row):");
        System.out.print(result);

        System.out.println("Naive join answer (" + naive(rels).rowCount() + " row, must match):");
        System.out.print(naive(rels));
    }

    static String sizes(Map<Integer, Relation> r) {
        return " knows=" + r.get(0).rowCount()
                + "  livesIn=" + r.get(1).rowCount()
                + "  country=" + r.get(2).rowCount();
    }
    static Relation naive(Map<Integer, Relation> r) {
        return r.get(0).join(r.get(1)).join(r.get(2));
    }
    static void post(JoinTree.Node x, List<JoinTree.Node> o) {
        for (JoinTree.Node c : x.children()) post(c, o); o.add(x);
    }
    static void pre(JoinTree.Node x, List<JoinTree.Node> o) {
        o.add(x); for (JoinTree.Node c : x.children()) pre(c, o);
    }
}