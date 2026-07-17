package quiz.transform.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static quiz.transform.ui.OperationKind.Nature;

/**
 * The primary split: VIEW ops shape instances of an existing class (filter /
 * group), STRUCTURAL ops produce a new product class (project / join).
 */
class OperationKindTest {

    @Test void viewOpsShapeInstancesStructuralOpsProduceClasses() {
        assertEquals(Nature.VIEW, OperationKind.FILTER.nature());
        assertEquals(Nature.VIEW, OperationKind.GROUP_BY.nature());
        assertEquals(Nature.STRUCTURAL, OperationKind.PROJECT_TO_CLASS.nature());
        assertEquals(Nature.STRUCTURAL, OperationKind.JOIN.nature());

        assertTrue(OperationKind.PROJECT_TO_CLASS.producesClass());
        assertTrue(OperationKind.JOIN.producesClass());
        assertFalse(OperationKind.FILTER.producesClass());
        assertFalse(OperationKind.GROUP_BY.producesClass());
    }
}
