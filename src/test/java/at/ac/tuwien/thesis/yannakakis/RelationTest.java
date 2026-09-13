package at.ac.tuwien.thesis.yannakakis;

import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The counting-semiring rules of {@link Relation}: projection adds, join multiplies,
 * semijoin keeps the left count, {@link Relation#distinct()} resets to 1. These are what
 * make early projection exact under SPARQL bag semantics.
 */
class RelationTest {

    private static Set<Var> vars(String... names) {
        Set<Var> s = new LinkedHashSet<>();
        for (String n : names) s.add(Var.alloc(n));
        return s;
    }

    @Test
    void projectionSumsCollapsedRows() {
        Relation r = Relation.builder("a", "b").row("1", "x").row("1", "y").rowTimes(3, "2", "x").build();
        Relation p = r.project(vars("a"));
        assertEquals(Relation.builder("a").rowTimes(2, "1").rowTimes(3, "2").build(), p);
        assertEquals(5, p.bagSize());
        assertEquals(2, p.rowCount());
        // projecting onto nothing leaves one empty tuple carrying the whole bag size
        Relation unit = r.project(Set.of());
        assertEquals(1, unit.rowCount());
        assertEquals(5, unit.bagSize());
        assertEquals(Relation.unit(), unit.distinct());
    }

    @Test
    void joinMultipliesCounts() {
        Relation r = Relation.builder("a", "b").rowTimes(2, "1", "x").row("2", "x").build();
        Relation s = Relation.builder("b", "c").rowTimes(3, "x", "p").row("x", "q").build();
        Relation j = r.join(s);
        assertEquals(Relation.builder("a", "b", "c")
                .rowTimes(6, "1", "x", "p").rowTimes(2, "1", "x", "q")
                .rowTimes(3, "2", "x", "p").row("2", "x", "q").build(), j);
        assertEquals(12, j.bagSize());
        // cross product multiplies too
        Relation t = Relation.builder("d").rowTimes(2, "z").build();
        assertEquals(24, j.join(t).bagSize());
    }

    @Test
    void semijoinKeepsTheLeftCount() {
        Relation r = Relation.builder("a", "b").rowTimes(2, "1", "x").row("2", "y").build();
        Relation s = Relation.builder("b").rowTimes(5, "x").build();
        assertEquals(Relation.builder("a", "b").rowTimes(2, "1", "x").build(), r.semijoin(s));
        // an empty-schema partner: all-or-nothing, counts untouched
        assertEquals(r, r.semijoin(Relation.unit()));
        assertEquals(0, r.semijoin(Relation.empty(Set.of())).bagSize());
    }

    @Test
    void projectionCommutesWithJoinOnTheBag() {
        // π_{a}(R ⋈ S) == π_{a}(π_{a,b}(R) ⋈ π_{b}(S)) with multiplicities — the property the
        // early projections of Yannakakis+ rely on.
        Relation r = Relation.builder("a", "b", "u").row("1", "x", "u1").row("1", "x", "u2").row("2", "y", "u3").build();
        Relation s = Relation.builder("b", "c").row("x", "p").row("x", "q").row("y", "p").build();
        Relation late = r.join(s).project(vars("a"));
        Relation early = r.project(vars("a", "b")).join(s.project(vars("b"))).project(vars("a"));
        assertEquals(late, early);
        assertEquals(Relation.builder("a").rowTimes(4, "1").row("2").build(), early);
    }

    @Test
    void distinctResetsEveryCountToOne() {
        Relation r = Relation.builder("a").rowTimes(4, "1").row("2").build();
        assertEquals(Relation.builder("a").row("1").row("2").build(), r.distinct());
        assertEquals(2, r.distinct().bagSize());
    }

    @Test
    void graphRowsStartWithCountOne() {
        Relation r = Relation.fromRows(vars("a"), Relation.builder("a").row("1").row("2").build().rows());
        assertEquals(2, r.bagSize());
        assertEquals(1, r.count(r.rows().iterator().next()));
        assertThrows(IllegalArgumentException.class, () -> Relation.builder("a").rowTimes(0, "1"));
    }
}
