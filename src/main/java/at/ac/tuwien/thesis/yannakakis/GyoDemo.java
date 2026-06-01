package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;

import java.util.Optional;

public class GyoDemo {

    public static void main(String[] args) {
        run("PATH (acyclic)", """
            PREFIX ex: <http://example.org/>
            SELECT * WHERE { ?a ex:r ?b . ?b ex:r ?c . ?c ex:r ?d . }""");

        run("STAR (acyclic)", """
            PREFIX ex: <http://example.org/>
            SELECT * WHERE { ?x ex:p1 ?a . ?x ex:p2 ?b . ?x ex:p3 ?c . }""");

        run("TRIANGLE (cyclic)", """
            PREFIX ex: <http://example.org/>
            SELECT * WHERE { ?a ex:r ?b . ?b ex:r ?c . ?c ex:r ?a . }""");
    }

    private static void run(String label, String query) {
        System.out.println("=== " + label + " ===");
        Op op = Algebra.compile(QueryFactory.create(query));
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(OpBGP opBGP) {
                QueryHypergraph h = QueryHypergraph.fromBasicPattern(opBGP.getPattern());
                Optional<JoinTree> jt = GyoReduction.decompose(h);
                if (jt.isPresent()) {
                    System.out.println("acyclic — join tree:");
                    System.out.print(jt.get().pretty());
                    System.out.println("running intersection holds: "
                            + jt.get().satisfiesRunningIntersection());
                } else {
                    System.out.println("CYCLIC — would fall through to native ARQ");
                }
            }
        });
        System.out.println();
    }
}