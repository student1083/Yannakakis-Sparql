package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class YannakakisArqDemo {

    static final String NS = "http://example.org/";
    static final String QUERY = """
            PREFIX ex: <http://example.org/>
            SELECT ?p1 ?p2 ?city ?ctry WHERE {
              ?p1   ex:knows   ?p2 .
              ?p2   ex:livesIn ?city .
              ?city ex:country ?ctry .
            }""";

    public static void main(String[] args) {
        Model model = buildModel();

        List<String> stock = run(model);                 // executor NOT registered

        YannakakisOpExecutor.resetCounter();
        YannakakisOpExecutor.register();
        List<String> yann;
        try {
            yann = run(model);
        } finally {
            YannakakisOpExecutor.unregister();
        }

        System.out.println("stock ARQ rows : " + stock.size());
        System.out.println("Yannakakis rows: " + yann.size());
        System.out.println("executor fired : " + YannakakisOpExecutor.invocations() + " time(s)");
        System.out.println("results match  : " + stock.equals(yann));
        System.out.println("\nanswer:");
        yann.forEach(s -> System.out.println("  " + s));
    }

    private static List<String> run(Model model) {
        List<String> rows = new ArrayList<>();
        Query q = QueryFactory.create(QUERY);
        try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                rows.add(s.toString());
            }
        }
        Collections.sort(rows);
        return rows;
    }

    private static Model buildModel() {
        Model m = ModelFactory.createDefaultModel();
        Property knows   = m.createProperty(NS + "knows");
        Property livesIn = m.createProperty(NS + "livesIn");
        Property country = m.createProperty(NS + "country");

        // one full chain: alice -> bob -> vienna -> austria
        m.add(res(m, "alice"), knows, res(m, "bob"));
        m.add(res(m, "bob"), knows, res(m, "carol"));   // carol -> graz has no country (dangles)
        m.add(res(m, "carol"), knows, res(m, "dave"));  // dave lives nowhere (dangles)

        m.add(res(m, "bob"), livesIn, res(m, "vienna"));
        m.add(res(m, "carol"), livesIn, res(m, "graz"));
        m.add(res(m, "erin"), livesIn, res(m, "oslo"));  // nobody knows erin (dangles)

        m.add(res(m, "vienna"), country, res(m, "austria"));
        m.add(res(m, "berlin"), country, res(m, "germany")); // nobody lives in berlin (dangles)
        return m;
    }

    private static Resource res(Model m, String local) { return m.createResource(NS + local); }
}