package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.op.OpBGP;

/**
 * The structure-guided rewriting (June milestone).
 *
 * <p>{@link TransformCopy} walks the algebra tree and, by default, copies every
 * node unchanged. You only override the cases you care about. For this thesis
 * the relevant one is {@link #transform(OpBGP)}:
 *
 * <ol>
 *   <li>build the query hypergraph of the BGP (one hyperedge per triple pattern);</li>
 *   <li>run the GYO reduction to test &alpha;-acyclicity and extract a join tree;</li>
 *   <li>if acyclic, rewrite the BGP into a yannakakis+ style plan
 *       (semi-join passes up and down the join tree, then the final joins);</li>
 *   <li>if cyclic, return the BGP unchanged so the native engine handles it.</li>
 * </ol>
 *
 * Right now it is a pass-through so the project compiles and runs.
 */
public class YannakakisTransform extends TransformCopy {

    @Override
    public Op transform(OpBGP opBGP) {
        // TODO (June): acyclicity test + join-tree extraction + rewrite.
        // For now, leave the BGP untouched.
        return opBGP;
    }
}
