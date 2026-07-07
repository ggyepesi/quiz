package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.Quizable;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The headless workbench logic: seeding the default pipeline, resetting on a type
 * change, and validating an operation's field shape — all without any Swing.
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

    @Test void seedsTheDefaultOscarsPipeline() {
        TransformController c = controller();
        assertTrue(c.seedDefault());
        assertEquals("Nomination", c.selectedType());
        assertEquals(
                List.of(OperationKind.FILTER,
                        OperationKind.GROUP_BY_REFERENCE,
                        OperationKind.GROUP_BY_VALUE),
                c.pipeline().stream().map(op -> op.kind).toList());
    }

    @Test void selectingATypeResetsThePipeline() {
        TransformController c = controller();
        c.seedDefault();
        assertFalse(c.pipeline().isEmpty());
        c.selectType("Nomination");
        assertTrue(c.pipeline().isEmpty());
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

    @Test void rejectsAFieldThatDoesNotFitTheSlotShape() {
        TransformController c = controller();
        c.selectType("Nomination");
        // GROUP_BY_REFERENCE needs a REFERENCE field; `won` is scalar.
        TransformController.OpOutcome out = c.addOperation(
                OperationKind.GROUP_BY_REFERENCE,
                c.resolveFields("Nomination", List.of("won")),
                null, null, null, null);
        assertFalse(out.ok());
        assertTrue(out.message().contains("won"), out.message());
        assertTrue(c.pipeline().isEmpty());
    }

    @Test void removeAndMoveReorderThePipeline() {
        TransformController c = controller();
        c.seedDefault();                       // FILTER, GROUP_BY_REFERENCE, GROUP_BY_VALUE
        c.removeOperation(0);                   // -> GROUP_BY_REFERENCE, GROUP_BY_VALUE
        assertEquals(2, c.pipeline().size());
        assertEquals(OperationKind.GROUP_BY_REFERENCE, c.pipeline().get(0).kind);

        assertEquals(1, c.moveOperation(0, 1)); // swap the two
        assertEquals(OperationKind.GROUP_BY_VALUE, c.pipeline().get(0).kind);
        assertEquals(-1, c.moveOperation(1, 1), "can't move the last one down");
    }
}
