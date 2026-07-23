package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.Quizable;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The headless workbench logic: seeding the default pipeline, remembering each
 * type's steps across a type change, and validating an operation's field shape —
 * all without any Swing.
 */
class TransformControllerTest {

    /** A minimal domain: Nomination with a scalar `won`/`year` and a reference
     *  `category`, enough to seed and to check slot validation. */
    private static DomainModel domain() {
        return new DomainModel() {
            @Override public List<String> types() { return List.of("Nomination"); }
            @Override public List<DomainField> fields(String type) {
                if (!"Nomination".equals(type)) {
                    return List.of();
                }
                return List.of(
                        new DomainField("Nomination", "won", false, false),
                        new DomainField("Nomination", "category", true, false),
                        new DomainField("Nomination", "year", false, false));
            }
            @Override public Collection<? extends Quizable> instances() { return List.of(); }
            @Override public Class<? extends Quizable> universe() { return Quizable.class; }
        };
    }

    private static TransformController controller() {
        return new TransformController(domain(), null);
    }

    /** Build a "winners by category by year" view onto {@code Nomination} the way the
     *  UI does — filter + two nested group-bys — so tests exercise a real pipeline
     *  without any baked-in seed. */
    private static void nominationView(TransformController c) {
        c.selectType("Nomination");
        OperationSpec byYear =
                new OperationSpec(OperationKind.GROUP_BY, c.field("Nomination", "year"), null);
        byYear.depth = 1;
        c.replaceViewPipeline(List.of(
                new OperationSpec(OperationKind.FILTER, c.field("Nomination", "won"), Boolean.TRUE),
                new OperationSpec(OperationKind.GROUP_BY, c.field("Nomination", "category"), null),
                byYear));
    }

    @Test void switchingToAFreshTypeGivesAnEmptyPipeline() {
        TransformController c = controller();
        nominationView(c);
        assertFalse(c.pipeline().isEmpty());
        c.selectType("Human");      // a type never configured — starts blank
        assertTrue(c.pipeline().isEmpty());
    }

    @Test void returningToATypeRestoresItsPipeline() {
        TransformController c = controller();
        nominationView(c);
        c.selectType("Human");      // stash Nomination's steps, blank slate
        c.selectType("Nomination"); // its steps come back, not a reset
        assertEquals("Nomination", c.selectedType());
        assertEquals(
                List.of(OperationKind.FILTER,
                        OperationKind.GROUP_BY,
                        OperationKind.GROUP_BY),
                c.pipeline().stream().map(op -> op.kind).toList());
    }

    @Test void addingAValidFilterStepGrowsThePipeline() {
        TransformController c = controller();
        c.selectType("Nomination");
        TransformController.OpOutcome out = c.addOperation(
                OperationKind.FILTER, c.resolveFields("Nomination", List.of("won")),
                "true", null, null, null);
        assertTrue(out.ok());
        assertNull(out.createdType());
        assertEquals(1, c.pipeline().size());
        assertEquals(Boolean.TRUE, c.pipeline().get(0).value);
    }

    @Test void filterIsOnePredicateWithAndConditions() {
        TransformController c = controller();
        c.selectType("Nomination");
        c.addOperation(OperationKind.FILTER,
                c.resolveFields("Nomination", List.of("won")), "true", null, null, null);
        c.addOperation(OperationKind.FILTER,
                c.resolveFields("Nomination", List.of("year")), "1994", null, null, null);

        // One FILTER node holding two AND conditions — not two pipeline steps.
        assertEquals(1, c.pipeline().size());
        OperationSpec filter = c.pipeline().get(0);
        assertEquals(OperationKind.FILTER, filter.kind);
        assertEquals(2, filter.conditions.size());
        assertEquals("won", filter.conditions.get(0).field().field());
        assertEquals("year", filter.conditions.get(1).field().field());
        assertEquals(1994, filter.conditions.get(1).value());
    }

    @Test void groupByAcceptsAnyFieldNotJustReferences() {
        TransformController c = controller();
        c.selectType("Nomination");
        // One Group by: a scalar keys by value, a reference by the entity — so a
        // scalar like `won` is valid (no more separate value/reference choice).
        TransformController.OpOutcome scalar = c.addOperation(
                OperationKind.GROUP_BY, c.resolveFields("Nomination", List.of("won")),
                null, null, null, null);
        assertTrue(scalar.ok(), scalar.message());
        TransformController.OpOutcome reference = c.addOperation(
                OperationKind.GROUP_BY, c.resolveFields("Nomination", List.of("category")),
                null, null, null, null);
        assertTrue(reference.ok(), reference.message());
        assertEquals(2, c.pipeline().size());
    }

    @Test void removeAndMoveReorderThePipeline() {
        TransformController c = controller();
        nominationView(c);                      // FILTER won, GROUP_BY category, GROUP_BY year
        c.removeOperation(0);                   // -> GROUP_BY category, GROUP_BY year
        assertEquals(2, c.pipeline().size());
        // Both group steps share the kind now, so reorder is verified by FIELD.
        assertEquals("category", c.pipeline().get(0).field.field());

        assertEquals(1, c.moveOperation(0, 1)); // swap the two
        assertEquals("year", c.pipeline().get(0).field.field());
        assertEquals(-1, c.moveOperation(1, 1), "can't move the last one down");
    }
}
