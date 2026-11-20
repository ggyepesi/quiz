package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.query.result.MembershipTypePreview;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A membership preview reports what the selected class would generate, not only what
 * the type admits (#519).
 *
 * <p>Mythology's Episode previewed Q63143903 as 60 direct members while the class,
 * which requires an English Wikipedia article, generated 16. The count of the
 * filtered population must come from generation's own root query with the candidate
 * as the membership type, so the preview cannot restate a filter differently.
 */
class PreviewMembershipTypeClassFiltersTest {

    @Test void episodePreviewReportsSixtyByTypeAndSixteenAfterTheSitelinkRequirement()
            throws Exception {
        RuleNode episode = RuleTreeCompiler.compileClass(episode(true));
        try (EpisodeClient sparql = new EpisodeClient()) {
            MembershipTypePreview result = new PreviewMembershipTypeQuery(
                    "Q63143903", "episode in Greek mythology", false, episode)
                    .execute(WikidataAccess.of(sparql, null).bind());

            assertEquals(60, result.boundedCount());
            MembershipTypePreview.ClassFilters filters = result.classFilters();
            assertTrue(filters.checked());
            assertEquals(16, filters.count());
            assertFalse(filters.capped());
            assertEquals("Episode", filters.className());
            assertTrue(filters.filters().contains("English Wikipedia article required"),
                    filters.filters().toString());
            assertTrue(filters.passes("Q1"));
            assertFalse(filters.passes("Q17"));

            String filtered = sparql.queries.getLast();
            assertEquals(filtered, wikidata.explore.query.template.rule.RuleNodeQueryBuilder.valuesQuery(
                    PreviewMembershipTypeQuery.candidatePopulationRule(
                            episode, "Q63143903", false)),
                    "the filtered count runs generation's own root query");
        }
    }

    @Test void theCandidateReplacesOnlyTheMembershipAndRankingNeverRemovesAMember() {
        GeneratedClassModel clazz = episode(true);
        clazz.membership(EntityBound.relation("P31",
                List.of("Q24336466", "Q1", "Q2"), true));
        RuleNode classRule = RuleTreeCompiler.compileClass(clazz);
        classRule.rankBySitelinks(true);
        classRule.addExcludedQid("Q99");

        RuleNode rule = PreviewMembershipTypeQuery.candidatePopulationRule(
                classRule, "Q63143903", false);

        assertEquals("Q63143903", rule.sourceQid());
        assertTrue(rule.additionalSourceQids().isEmpty());
        assertFalse(rule.membershipIncludesDescendants(),
                "the preview's subclass setting is passed in, never the old membership's");
        assertTrue(rule.requireSitelink());
        assertTrue(rule.excludedQids().contains("Q99"));
        assertFalse(rule.hasRank());
        assertEquals(PreviewMembershipTypeQuery.COUNT_BOUND + 1, rule.limit());
    }

    @Test void aClassWithoutFiltersBeyondItsTypeIsNotQueriedASecondTime()
            throws Exception {
        GeneratedClassModel clazz = episode(false);
        clazz.instanceMapping().requireLabel(false);
        RuleNode rule = RuleTreeCompiler.compileClass(clazz);
        try (EpisodeClient sparql = new EpisodeClient()) {
            MembershipTypePreview result = new PreviewMembershipTypeQuery(
                    "Q63143903", "episode in Greek mythology", false, rule)
                    .execute(WikidataAccess.of(sparql, null).bind());

            assertFalse(result.classFilters().checked());
            assertTrue(result.classFilters().passes("Q60"));
            assertEquals(2, sparql.queries.size());
        }
    }

    @Test void aCappedFilteredCountDoesNotClaimWhichMembersPass() {
        var filters = new MembershipTypePreview.ClassFilters("Episode",
                List.of("label required (en)"), 200, 1000, true, java.util.Set.of());
        assertNull(filters.passes("Q1"));
    }

    private static GeneratedClassModel episode(boolean requireSitelink) {
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        episode.membership(EntityBound.relation("P31", List.of("Q63143903"), false));
        episode.instanceMapping().requireSitelink(requireSitelink);
        return episode;
    }

    private static final class EpisodeClient extends WikidataSparqlClient {
        final List<String> queries = new ArrayList<>();

        private EpisodeClient() { super("EpisodeClassFilterTest/1.0"); }

        @Override public List<WikidataBinding> query(String query) {
            queries.add(query);
            if (query.contains("COUNT(?member)")) {
                return List.of(new WikidataBinding(Map.of("count", "60")));
            }
            if (query.contains("?member")) {
                return IntStream.rangeClosed(1, 60).mapToObj(i -> new WikidataBinding(Map.of(
                        "member", "http://www.wikidata.org/entity/Q" + i,
                        "memberLabel", "Episode " + i))).toList();
            }
            assertTrue(query.contains("schema:isPartOf <https://en.wikipedia.org/>"));
            assertTrue(query.contains("wd:Q63143903"));
            assertTrue(query.contains("LIMIT " + (PreviewMembershipTypeQuery.COUNT_BOUND + 1)));
            return IntStream.rangeClosed(1, 16).mapToObj(i -> new WikidataBinding(Map.of(
                    "value", "http://www.wikidata.org/entity/Q" + i,
                    "valueLabel", "Episode " + i))).toList();
        }
    }
}
