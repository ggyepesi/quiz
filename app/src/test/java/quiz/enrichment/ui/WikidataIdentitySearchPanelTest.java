package quiz.enrichment.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A pasted QID is extracted from a bare id, wd: form, or a Wikidata URL. */
class WikidataIdentitySearchPanelTest {

    @Test void parsesQidFromVariousPastes() {
        assertEquals("Q458", WikidataIdentitySearchPanel.parseQid("Q458"));
        assertEquals("Q458", WikidataIdentitySearchPanel.parseQid("q458"));
        assertEquals("Q458", WikidataIdentitySearchPanel.parseQid("  wd:Q458 "));
        assertEquals("Q458", WikidataIdentitySearchPanel.parseQid(
                "https://www.wikidata.org/wiki/Q458"));
        assertNull(WikidataIdentitySearchPanel.parseQid("European Union"));
        assertNull(WikidataIdentitySearchPanel.parseQid(""));
        assertNull(WikidataIdentitySearchPanel.parseQid(null));
    }
}
