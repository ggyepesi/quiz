package quiz.transform;

import objectview.Viewable;
import objectview.group.ViewableGroup;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #96: EXPECTED is meant to make a coverage gap CURATABLE — "keep every row, count and
 * collect the ones missing the field" — and the agreed flow is set EXPECTED, inspect the
 * N missing, escalate to REQUIRED only if they are all bad data. Inspecting them was the
 * part that did not exist: a value facet produces no bucket at all for a member whose
 * field is empty, so the missing records were a number in the run log and nothing else.
 */
class PresenceFacetTest {

    private static WikidataDynamicObject nomination(String id, Object edition) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, id);
        o.type("Nomination");
        if (edition != null) {
            o.put("edition", edition);
        }
        return o;
    }

    private static List<Viewable> pool() {
        WikidataDynamicObject ceremony = new WikidataDynamicObject("Q66707607", "95th");
        ceremony.type("Edition");
        return List.of(
                nomination("n1", ceremony),
                nomination("n2", ceremony),
                nomination("n3", null),     // no P805 — the incomplete nomination
                nomination("n4", null));
    }

    private static ViewableGroup<?> dimensionOf(FacetGroup group) {
        return group.getChildren().iterator().next();
    }

    @Test void presenceBucketingSeparatesTheMissingFromThePresent() {
        FacetGroup group = new FacetGroup(
                "Has edition", "Nomination", "edition", FacetGroup.Bucketing.PRESENCE);

        group.reproduce(pool());

        List<String> buckets = dimensionOf(group).getChildren().stream()
                .map(ViewableGroup::getDisplayName).sorted().toList();
        assertEquals(List.of("missing", "present"), buckets);
    }

    @Test void everyMemberLandsInExactlyOneBucket() {
        FacetGroup group = new FacetGroup(
                "Has edition", "Nomination", "edition", FacetGroup.Bucketing.PRESENCE);

        group.reproduce(pool());

        int total = dimensionOf(group).getChildren().stream()
                .mapToInt(b -> b.getMembers().size()).sum();
        assertEquals(4, total, "presence is total — nothing falls outside both buckets");
    }

    @Test void theMistake_aValueFacetLosesTheMissingRecordsEntirely() {
        // Which is why the coverage gap was invisible: the two nominations with no
        // edition belong to no bucket, so a facet view simply does not show them.
        FacetGroup group = new FacetGroup("By edition", "Nomination", "edition");

        group.reproduce(pool());

        int total = dimensionOf(group).getChildren().stream()
                .mapToInt(b -> b.getMembers().size()).sum();
        assertEquals(2, total, "only the records that HAVE an edition are bucketed");
    }

    @Test void theBucketingSurvivesTheRoundTripThroughThePersistedRule() {
        FacetGroup original = new FacetGroup(
                "Has edition", "Nomination", "edition", FacetGroup.Bucketing.PRESENCE);
        original.reproduce(pool());

        EditableGroup restored = EditableGroup.copyOf(original);

        assertTrue(restored instanceof FacetGroup, "still a facet group");
        assertEquals(FacetGroup.Bucketing.PRESENCE, ((FacetGroup) restored).bucketing(),
                "a reloaded present/missing facet must not silently become a value facet");
    }

    @Test void anOlderSavedFacetWithNoRecordedBucketingStaysAValueFacet() {
        FacetGroup original = new FacetGroup("By edition", "Nomination", "edition");

        EditableGroup restored = EditableGroup.copyOf(original);

        assertEquals(FacetGroup.Bucketing.VALUE, ((FacetGroup) restored).bucketing());
    }
}
