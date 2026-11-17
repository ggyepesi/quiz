package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.query.result.MembershipTypeCandidate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoverCommonMembershipTypesQueryTest {

    private static final List<MembershipTypeCandidate.Member> LABOURS = List.of(
            new MembershipTypeCandidate.Member(
                    "Q3088950", "Cleaning the Augean stables"),
            new MembershipTypeCandidate.Member(
                    "Q2937680", "Capture of Cerberus"),
            new MembershipTypeCandidate.Member(
                    "Q1140197", "Apples of the Hesperides"));

    @Test void explicitExamplesProduceCoveredDirectAndAncestorCandidates()
            throws Exception {
        try (FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of(
                        "type", "http://www.wikidata.org/entity/Q63143903",
                        "typeLabel", "episode in Greek mythology",
                        "coverage", "3", "directCoverage", "3",
                        "matchedExamples", "Q3088950 Q2937680 Q1140197"))
                .row(Map.of(
                        "type", "http://www.wikidata.org/entity/Q24336466",
                        "typeLabel", "mythical event",
                        "coverage", "3", "directCoverage", "0",
                        "matchedExamples", "Q3088950 Q2937680 Q1140197"))) {
            List<MembershipTypeCandidate> result =
                    new DiscoverCommonMembershipTypesQuery(LABOURS)
                            .execute(WikidataAccess.of(sparql, null).bind());

            assertEquals(List.of("Q63143903", "Q24336466"), result.stream()
                    .map(MembershipTypeCandidate::qid).toList());
            assertEquals(3, result.getFirst().coveredExamples());
            assertEquals(3, result.getFirst().directExamples());
            assertEquals(0, result.get(1).directExamples());
            assertEquals(LABOURS.stream().map(MembershipTypeCandidate.Member::qid).toList(),
                    result.getFirst().examples().stream()
                            .map(MembershipTypeCandidate.Member::qid).toList());
        }
    }

    @Test void queryWalksUpFromEveryExplicitExampleAndDoesNotSearchAllWikidata() {
        String query = DiscoverCommonMembershipTypesQuery.sparql(LABOURS, 200);

        assertTrue(query.contains("VALUES ?example { wd:Q3088950 wd:Q2937680 wd:Q1140197 }"));
        assertTrue(query.contains("?example wdt:P31 ?directType"));
        assertTrue(query.contains("?directType wdt:P279* ?type"));
        assertTrue(query.contains("LIMIT 200"));
    }

    @Test void duplicateExampleQidsCountOnceEvenIfTheirLabelsDiffer() {
        List<MembershipTypeCandidate.Member> duplicate = List.of(
                new MembershipTypeCandidate.Member("Q3088950", "Augean stables"),
                new MembershipTypeCandidate.Member("Q3088950", "Cleaning the stables"));

        DiscoverCommonMembershipTypesQuery discovery =
                new DiscoverCommonMembershipTypesQuery(duplicate);
        String query = DiscoverCommonMembershipTypesQuery.sparql(duplicate, 200);

        assertEquals("1", discovery.parameters().get("examples"));
        assertEquals(1, query.split("wd:Q3088950", -1).length - 1);
    }

    @Test void moreThanTwentyDistinctExamplesAreRefused() {
        List<MembershipTypeCandidate.Member> examples = java.util.stream.IntStream
                .rangeClosed(1, 21)
                .mapToObj(i -> new MembershipTypeCandidate.Member("Q" + i, "Example " + i))
                .toList();

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new DiscoverCommonMembershipTypesQuery(examples));

        assertTrue(failure.getMessage().contains("at most 20"));
    }

    @Test void previewCountsToACeilingThenFetchesOnlyFiftyMembers() throws Exception {
        try (WikidataSparqlClient sparql = new PreviewClient()) {
            var result = new PreviewMembershipTypeQuery("Q63143903", true)
                    .execute(WikidataAccess.of(sparql, null).bind());

            assertEquals(1000, result.boundedCount());
            assertTrue(result.countCapped());
            assertEquals(List.of("Q3088950"), result.members().stream()
                    .map(MembershipTypeCandidate.Member::qid).toList());
        }
    }

    private static final class PreviewClient extends WikidataSparqlClient {
        private PreviewClient() { super("MembershipPreviewTest/1.0"); }

        @Override public List<WikidataBinding> query(String query) {
            if (query.contains("COUNT(?member)")) {
                return List.of(new WikidataBinding(Map.of("count", "1001")));
            }
            assertTrue(query.contains("wdt:P31/wdt:P279* wd:Q63143903"));
            assertTrue(query.contains("LIMIT 50"));
            assertTrue(query.indexOf("LIMIT 50")
                    < query.indexOf("SERVICE wikibase:label"));
            assertTrue(!query.contains("ORDER BY"),
                    "the bounded member sample is sorted locally, not by WDQS");
            return List.of(new WikidataBinding(Map.of(
                    "member", "http://www.wikidata.org/entity/Q3088950",
                    "memberLabel", "Cleaning the Augean stables",
                    "memberDescription", "episode in Greek mythology")));
        }
    }
}
