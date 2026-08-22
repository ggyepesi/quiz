package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import datasource.SourceRef;

import org.junit.jupiter.api.Test;
import work.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourcePageImageEnrichmentProviderTest {

    @Test
    void extractsMetadataAndRelevantPortraitWithoutSiteSpecificRules() throws Exception {
        String html = """
                <html><head>
                  <meta property="og:image" content="/images/snell-landscape.jpg">
                </head><body>
                  <img class="site-logo" src="/images/logo.svg">
                  <img class="laureate portrait" alt="George D. Snell"
                       src="/images/snell-portrait.jpg">
                </body></html>
                """;
        SourcePageImageEnrichmentProvider provider =
                new SourcePageImageEnrichmentProvider(uri -> html);
        SourceRef source = new SourceRef(
                "NobelPrize.org", "421",
                "https://www.nobelprize.org/prizes/medicine/1980/snell/facts/");
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Person", "421", "George D. Snell"),
                "image", false, List.of(source));

        EnrichmentProposal result =
                provider.discover(request).execute(new QueryContext());

        assertEquals(1, result.identities().size());
        assertEquals(source, result.identities().get(0).source());
        assertEquals(2, result.media().size());
        assertTrue(result.media().stream()
                .anyMatch(m -> m.imageUrl().endsWith("snell-portrait.jpg")));
        assertFalse(result.media().stream()
                .anyMatch(m -> m.imageUrl().endsWith("logo.svg")));
    }
}
