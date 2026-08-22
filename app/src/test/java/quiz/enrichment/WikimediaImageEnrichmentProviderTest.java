package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import org.junit.jupiter.api.Test;
import work.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikimediaImageEnrichmentProviderTest {

    @Test
    void fieldNameSelectsTheImageProperty() {
        assertEquals("P41", WikimediaImageEnrichmentProvider.imageQueryFor("flagVersions")
                .properties().get(0).property());
        assertEquals("P94", WikimediaImageEnrichmentProvider.imageQueryFor("armsVersions")
                .properties().get(0).property());
        assertEquals("P154", WikimediaImageEnrichmentProvider.imageQueryFor("logo")
                .properties().get(0).property());
        assertEquals("P18", WikimediaImageEnrichmentProvider.imageQueryFor("portrait")
                .properties().get(0).property());
        // a general photo gets the article lead image; a specific emblem does not
        assertTrue(WikimediaImageEnrichmentProvider.imageQueryFor("portrait")
                .includeWikipediaImage());
        assertFalse(WikimediaImageEnrichmentProvider.imageQueryFor("flagVersions")
                .includeWikipediaImage());
    }

    @Test
    void aFlagFieldPullsP41AndSkipsThePageImage() throws Exception {
        String entity = """
                {"entities": {"Q924": {
                  "claims": {"P41": [{"rank": "normal",
                    "mainsnak": {"datavalue": {"value": "Flag of Tanzania.svg"}}}]},
                  "sitelinks": {"enwiki": {"title": "Tanzania"}}
                }}}
                """;
        WikimediaImageEnrichmentProvider provider =
                new WikimediaImageEnrichmentProvider(uri -> entity);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("State", "Q924", "Tanzania"),
                "flagVersions", true, List.of());

        EnrichmentProposal result =
                provider.discover(request).execute(new QueryContext());

        assertEquals(1, result.media().size());   // only the flag; no article page image
        EnrichmentProposal.MediaCandidate flag = result.media().get(0);
        assertTrue(flag.discoveryMethod().contains("P41"));
        assertTrue(flag.imageUrl().contains("Flag%20of%20Tanzania.svg"));
        assertTrue(flag.collection());
    }

    @Test
    void keepsWikidataLogoAndWikipediaPageImageAsDistinctCandidates()
            throws Exception {
        String entity = """
                {
                  "entities": {
                    "Q42970": {
                      "claims": {
                        "P154": [{
                          "rank": "normal",
                          "mainsnak": {"datavalue": {
                            "value": "Amnesty international Logo.svg"
                          }}
                        }]
                      },
                      "sitelinks": {
                        "enwiki": {"title": "Amnesty International"}
                      }
                    }
                  }
                }
                """;
        String pageImage = """
                {
                  "query": {"pages": {"123": {
                    "original": {
                      "source": "https://upload.wikimedia.org/wikipedia/en/e/ee/Amnesty_International_logo.svg"
                    }
                  }}}
                }
                """;
        WikimediaImageEnrichmentProvider provider =
                new WikimediaImageEnrichmentProvider(uri ->
                        uri.getHost().startsWith("www.wikidata")
                                ? entity : pageImage);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Organization", "Q42970", "Amnesty International"),
                "image", false, List.of());

        EnrichmentProposal result =
                provider.discover(request).execute(new QueryContext());

        assertEquals(2, result.media().size());
        assertTrue(result.media().stream().anyMatch(candidate ->
                candidate.discoveryMethod().contains("P154")
                        && candidate.imageUrl().contains(
                        "Amnesty%20international%20Logo.svg")));
        assertTrue(result.media().stream().anyMatch(candidate ->
                candidate.discoveryMethod().contains("Wikipedia")
                        && candidate.imageUrl().contains(
                        "Amnesty_International_logo.svg")));
    }
}
