package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;

public class HypergraphDemo {

    public static void main(String[] args) {
        String queryString = """
                PREFIX ex: <http://example.org/>
                SELECT ?person ?name ?city WHERE {
                  ?person ex:name    ?name .
                  ?person ex:livesIn ?city .
                  ?city   ex:country ?country .
                }
                """;

        Op op = Algebra.compile(QueryFactory.create(queryString));

        OpWalker.walk(op, new OpVisitorBase() {
            @Override
            public void visit(OpBGP opBGP) {
                QueryHypergraph h = QueryHypergraph.fromBasicPattern(opBGP.getPattern());
                System.out.println(h);
            }
        });
    }
}