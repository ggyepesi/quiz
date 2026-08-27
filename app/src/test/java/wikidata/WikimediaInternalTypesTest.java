package wikidata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikimediaInternalTypesTest {

    @Test void onlyExclusivelyInternalKnownTypesDisqualifyAnEntity() {
        assertTrue(WikimediaInternalTypes.exclusivelyInternal(
                List.of("Q13406463")));
        assertTrue(WikimediaInternalTypes.exclusivelyInternal(
                List.of("Q13406463", "Q4167410")));
        assertFalse(WikimediaInternalTypes.exclusivelyInternal(
                List.of("Q13406463", "Q355567", "Q114962596")));
        assertFalse(WikimediaInternalTypes.exclusivelyInternal(List.of()));
    }

    @Test void previewFilterTestsTheTargetAndAllowsAnOrdinaryTypeToOverride() {
        String filter = WikimediaInternalTypes.excludeExclusivelyInternal("?target");
        assertTrue(filter.contains("?target wdt:P31 ?internalType"));
        assertTrue(filter.contains("?target wdt:P31 ?ordinaryType"));
        assertTrue(filter.contains("FILTER(?ordinaryType NOT IN"));
    }
}
