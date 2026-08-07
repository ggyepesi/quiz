package language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The speakers value drops the leading language-use label (L1/L2), a resolved Wikipedia
 *  wikilink, so the count leads — readable and (leading number) sortable. */
class WikipediaLanguageReaderTest {

    @Test void stripsLeadingL1Label() {
        assertEquals("74.170080 million",
                WikipediaLanguageReader.cleanSpeakers("L1: 74.170080 million"));
    }

    @Test void stripsL2AndCombinedLabels() {
        assertEquals("5 million", WikipediaLanguageReader.cleanSpeakers("L2: 5 million"));
        assertEquals("80% of China",
                WikipediaLanguageReader.cleanSpeakers("L1 and L2: 80% of China"));
    }

    @Test void leavesAnUnlabelledCountUntouched() {
        assertEquals("22.209690 million",
                WikipediaLanguageReader.cleanSpeakers("22.209690 million"));
    }

    @Test void leavesFreeProseWithoutAColonUntouched() {
        // No colon -> not a use-label prefix, so descriptive text is preserved.
        assertEquals("L1 and L2 speakers in Scotland",
                WikipediaLanguageReader.cleanSpeakers("L1 and L2 speakers in Scotland"));
    }

    @Test void nullStaysNull() {
        assertNull(WikipediaLanguageReader.cleanSpeakers(null));
    }
}
