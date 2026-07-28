package quiz.enrichment;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.core.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikimediaImageEnrichmentProviderTest {

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
                provider.discover(request).execute(new QueryContext(null, null));

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
