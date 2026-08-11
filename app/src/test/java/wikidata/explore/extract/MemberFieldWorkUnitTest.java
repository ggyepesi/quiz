package wikidata.explore.extract;

import batch.WorkDescriptor;
import batch.WorkUnit;
import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A member-field batch computes without publishing, so the executor can retry it.
 *
 * <p>The loop this replaced merged each row into the shared registry as it arrived, so a
 * batch that failed halfway and was retried merged its early rows a second time. Nothing
 * detected that: {@code merge} deduplicates identical values, which hid it for
 * single-valued fields and produced silently wrong ordering elsewhere.
 */
class MemberFieldWorkUnitTest {

    private static RuleIncludedField locations() {
        RuleIncludedField field = new RuleIncludedField();
        field.fieldName("locations");
        field.propertyPid("P840");
        field.direction(RuleDirection.ROOT_TO_ITEM);
        field.collection(true);
        return field;
    }

    /** A client that yields rows for the QIDs in the query, failing the first N calls. */
    private static WikidataSparqlClient client(AtomicInteger calls, int failFirst) {
        return new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String sparql) {
                if (calls.incrementAndGet() <= failFirst) {
                    throw new IllegalStateException("boom");
                }
                return List.of(
                        new WikidataBinding(Map.of(
                                "value", "http://www.wikidata.org/entity/Q1",
                                "fieldValue", "http://www.wikidata.org/entity/Q100")),
                        new WikidataBinding(Map.of(
                                "value", "http://www.wikidata.org/entity/Q2",
                                "fieldValue", "http://www.wikidata.org/entity/Q200")));
            }
        };
    }

    @Test void executeReturnsDetachedPairsAndPublishesNothing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (WikidataSparqlClient sparql = client(calls, 0)) {
            Map<String, List<String>> values = new MemberFieldWorkUnit(
                    locations(), List.of("Q1", "Q2"), sparql).execute();

            assertEquals(Map.of("Q1", List.of("Q100"), "Q2", List.of("Q200")), values,
                         "the unit yields values, it does not apply them");
        }
    }

    @Test void splittingHalvesTheMemberListAndCoversItExactly() throws Exception {
        try (WikidataSparqlClient sparql = client(new AtomicInteger(), 0)) {
            MemberFieldWorkUnit unit = new MemberFieldWorkUnit(
                    locations(), List.of("Q1", "Q2", "Q3", "Q4", "Q5"), sparql);

            List<String> covered = unit.split().stream()
                    .map(part -> ((MemberFieldWorkUnit) part).descriptor()
                            .parameters().get("members"))
                    .flatMap(members -> List.of(members.split(",")).stream())
                    .toList();

            assertEquals(List.of("Q1", "Q2", "Q3", "Q4", "Q5"), covered,
                         "a split must cover the parent exactly — anything the parts miss "
                                 + "is silently dropped from the run");
        }
    }

    @Test void aSingleMemberCannotBeSplitFurther() throws Exception {
        try (WikidataSparqlClient sparql = client(new AtomicInteger(), 0)) {
            assertTrue(new MemberFieldWorkUnit(locations(), List.of("Q1"), sparql)
                               .split().isEmpty());
        }
    }

    @Test void aRestoredUnitIssuesTheSameWorkAsTheOriginal() throws Exception {
        try (WikidataSparqlClient sparql = client(new AtomicInteger(), 0)) {
            MemberFieldWorkUnit original =
                    new MemberFieldWorkUnit(locations(), List.of("Q1", "Q2"), sparql);
            WorkDescriptor descriptor = original.descriptor();

            MemberFieldWorkUnit restored =
                    MemberFieldWorkUnit.restore(descriptor, sparql);

            assertEquals(descriptor, restored.descriptor(),
                         "the executor rejects a restored unit whose descriptor differs");
            assertEquals(original.execute(), restored.execute());
        }
    }

    @Test void distinctBatchesOfTheSameFieldGetDistinctKeys() throws Exception {
        try (WikidataSparqlClient sparql = client(new AtomicInteger(), 0)) {
            WorkUnit<Map<String, List<String>>> first =
                    new MemberFieldWorkUnit(locations(), List.of("Q1", "Q2"), sparql);
            WorkUnit<Map<String, List<String>>> second =
                    new MemberFieldWorkUnit(locations(), List.of("Q3", "Q4"), sparql);

            // The executor rejects duplicate keys outright, so a colliding key would
            // fail the run rather than silently drop a batch.
            assertTrue(!first.key().equals(second.key()), "batch keys must be distinct");
        }
    }
}
