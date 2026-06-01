package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

/**
 * Minimal end-to-end check that the toolchain works:
 * build a tiny in-memory RDF graph, run a one-triple BGP over it,
 * and print the result. If this prints a row, your setup is correct.
 */
public class SmokeTest {

    public static void main(String[] args) {
        String ns = "http://example.org/";

        Model model = ModelFactory.createDefaultModel();
        Resource alice = model.createResource(ns + "alice");
        Resource bob = model.createResource(ns + "bob");
        Property knows = model.createProperty(ns + "knows");
        model.add(alice, knows, bob);

        String queryString = """
                PREFIX ex: <http://example.org/>
                SELECT ?a ?b WHERE { ?a ex:knows ?b }
                """;

        Query query = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
            ResultSet rs = qe.execSelect();
            ResultSetFormatter.out(System.out, rs, query);
        }

        System.out.println("Jena " + org.apache.jena.Jena.VERSION + " is working.");
    }
}
