package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.MonolingualTextCodec;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Nobel award rationale (P6208) is stated in about thirteen languages — 2041 values
 * for 1033 award statements. A field loading it must be able to say which wording it
 * wants, and must store the text alone: the language was the means of choosing, not
 * part of the answer.
 */
class MonolingualQualifierLanguageTest {

    private static final String EN = "for the optical tweezers";
    private static final String SV = MonolingualTextCodec.encode(
            "for den optiska pincetten", "sv");
    private static final String UNTAGGED = "rationale of unstated language";

    private static final class StubApi extends WikidataApiClient {
        private final Map<String, List<ApiStatement>> statements;
        StubApi(Map<String, List<ApiStatement>> statements) {
            super("test");
            this.statements = statements;
        }
        @Override public Map<String, List<ApiStatement>> getStatements(
                List<String> entityQids, String statementPid,
                List<String> qualifierPids, BatchLog log) {
            Map<String, List<ApiStatement>> out = new LinkedHashMap<>();
            for (String q : entityQids) {
                if (statements.containsKey(q)) out.put(q, statements.get(q));
            }
            return out;
        }
        @Override public Map<String, ApiEntity> getEntities(
                List<String> qids, List<String> claimPids, BatchLog log) {
            Map<String, ApiEntity> out = new LinkedHashMap<>();
            for (String q : qids) out.put(q, new ApiEntity(q, "", Map.of()));
            return out;
        }
    }

    private static Object motivationOf(String language, boolean multi) {
        WikidataDynamicObject ashkin = new WikidataDynamicObject("Q1000000", "Arthur Ashkin");
        ashkin.type("Laureate");
        WikidataDynamicObject physics = new WikidataDynamicObject("Q38104", "Physics");
        physics.type("Categories");
        StubApi api = new StubApi(Map.of("Q1000000", List.of(
                new WikidataApiClient.ApiStatement("Q1000000$a", "Q38104",
                        Map.of("P6208", List.of(SV,
                                MonolingualTextCodec.encode(EN, "en"),
                                UNTAGGED))))));

        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "Laureate", "P166", "__Award", "NobelPrize", "category", "",
                List.of(new QualifierLoadConfig.Qualifier(
                        "P6208", "motivation",
                        QualifierLoadConfig.Kind.STRING, multi, language)),
                List.of("Q38104"));

        return new QualifierLoader().api(api)
                .enrich(List.of(ashkin, physics), cfg, null, null).get(0)
                .get("motivation");
    }

    @Test void aFieldTakesTheWordingInTheLanguageItAsksFor() {
        assertEquals("for the optical tweezers", motivationOf("en", false),
                "the Swedish wording is listed first and must not win by arriving first");
    }

    @Test void theStoredValueIsTheTextWithoutItsLanguage() {
        Object stored = motivationOf("en", false);
        assertEquals("for the optical tweezers", stored);
        assertEquals("", MonolingualTextCodec.language(String.valueOf(stored)),
                "the tag was how the value was chosen, not something to serve");
    }

    @Test void askingForNothingKeepsEveryWording() {
        assertEquals(
                List.of("for den optiska pincetten", "for the optical tweezers",
                        "rationale of unstated language"),
                motivationOf("", true),
                "with no language asked for, every wording is kept and none is invented");
    }

    @Test void anUntaggedWordingDoesNotDisplaceARequestedOne() {
        assertEquals("for the optical tweezers", motivationOf("en", true),
                "an exact language answer wins over the untagged fallback");
    }
}
