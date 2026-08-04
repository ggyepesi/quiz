package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 of #99: the reify records per-field origin as sidecar provenance that
 * is readable by later passes but never leaks into the object's rendered/served
 * fields.
 */
class WikidataDynamicObjectOriginTest {

    @Test
    void recordsAndReadsOrigins() {
        WikidataDynamicObject o = new WikidataDynamicObject("Q1", "Test");
        o.recordOrigin("nominee", FieldOrigin.SUBJECT_FALLBACK);
        o.recordOrigin("forWork", FieldOrigin.QUALIFIER);

        assertEquals(FieldOrigin.SUBJECT_FALLBACK, o.origin("nominee"));
        assertEquals(FieldOrigin.QUALIFIER, o.origin("forWork"));
        assertNull(o.origin("edition"), "unrecorded field has no origin");
    }

    @Test
    void originsAreSidecarNotDataFields() {
        WikidataDynamicObject o = new WikidataDynamicObject("Q1", "Test");
        o.put("forWork", "Qfilm");
        o.recordOrigin("forWork", FieldOrigin.QUALIFIER);

        // The data field is served/rendered...
        assertTrue(o.dynamicFieldValues().containsKey("forWork"));
        // ...but the origin sidecar never appears as a served/rendered field.
        assertFalse(o.dynamicFieldValues().containsKey("fieldOrigins"),
                "field origins must not leak into dynamicFieldValues");
        assertFalse(o.dynamicFieldValues().values().contains(FieldOrigin.QUALIFIER),
                "origin enum values must never appear as data");
    }
}
