package workbench;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSelectionsTest {
    @Test void entityAndPropertyAreIndependentSingleValueSlots() {
        WorkbenchSelections selections = new WorkbenchSelections();
        AtomicInteger changes = new AtomicInteger();
        selections.onChange(changes::incrementAndGet);

        selections.entity("Q42", "Douglas Adams");
        selections.property("P26", "spouse");
        selections.entity("Q937", "Albert Einstein");

        assertEquals("Q937", selections.entity().orElseThrow().qid());
        assertEquals("P26", selections.property().orElseThrow().pid());
        assertEquals(3, changes.get());

        selections.clearProperty();
        assertTrue(selections.property().isEmpty());
        assertEquals("Q937", selections.entity().orElseThrow().qid());
    }
}
