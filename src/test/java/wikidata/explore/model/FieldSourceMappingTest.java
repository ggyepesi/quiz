package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FieldSourceMappingTest {

    // Regression: copy() must carry the reify overrides — a dropped field silently
    // reverted edition to subject-default on every projectModel.copy() (Generate /
    // Remap), so the #95 fix never took effect no matter how it was set in the UI.
    @Test void copyCarriesReifyOverrides() {
        FieldSourceMapping m = new FieldSourceMapping();
        m.subjectDefault(Boolean.FALSE);
        m.inDedupKey(Boolean.TRUE);

        FieldSourceMapping c = m.copy();

        assertEquals(Boolean.FALSE, c.subjectDefault());
        assertEquals(Boolean.TRUE, c.inDedupKey());
    }

    @Test void copyKeepsInferredDefaultsNull() {
        FieldSourceMapping c = new FieldSourceMapping().copy();
        assertNull(c.subjectDefault());
        assertNull(c.inDedupKey());
    }
}
