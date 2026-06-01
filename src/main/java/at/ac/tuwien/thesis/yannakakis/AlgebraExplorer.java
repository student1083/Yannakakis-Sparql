package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.core.BasicPattern;

/**
 * Throwaway tool to learn the ARQ algebra. Compiles a SPARQL query into the
 * Op tree, prints it, then locates each BGP and breaks it down into triple
 * patterns, marking which nodes are variables (the future join vertices).
 */
public class AlgebraExplorer {

    public static void main(String[] args) {
        String queryString = """
                PREFIX ex: <http://example.org/>
                SELECT ?a ?b ?c WHERE {
                  ?a ex:r ?b . ?b ex:r ?c . ?c ex:r ?a 
                }
                """;

        Query query = QueryFactory.create(queryString);

        // 1. Compile the SPARQL query into the ARQ algebra (the Op tree).
        Op op = Algebra.compile(query);

        // 2. Print the whole algebra tree (SSE format).
        System.out.println("=== Algebra (Op) tree ===");
        System.out.println(op);

        // 3. Walk the tree and inspect every BGP we find.
        System.out.println("\n=== BGP inspection ===");
        OpWalker.walk(op, new OpVisitorBase() {
            @Override
            public void visit(OpBGP opBGP) {
                inspectBgp(opBGP.getPattern());
            }
        });
    }

    private static void inspectBgp(BasicPattern pattern) {
        System.out.println("BGP with " + pattern.size() + " triple pattern(s):");
        int i = 1;
        for (Triple t : pattern) {
            System.out.println("  Pattern " + (i++) + ":");
            describeNode("subject  ", t.getSubject());
            describeNode("predicate", t.getPredicate());
            describeNode("object   ", t.getObject());
        }
    }

    private static void describeNode(String role, Node node) {
        String kind;
        if (node.isVariable())     kind = "VARIABLE (join vertex) -> ?" + node.getName();
        else if (node.isURI())     kind = "IRI (constant)        -> " + node.getURI();
        else if (node.isLiteral()) kind = "LITERAL (constant)    -> " + node.getLiteralLexicalForm();
        else if (node.isBlank())   kind = "BLANK NODE            -> " + node.getBlankNodeLabel();
        else                       kind = "OTHER                 -> " + node;
        System.out.println("    " + role + " : " + kind);
    }
}
