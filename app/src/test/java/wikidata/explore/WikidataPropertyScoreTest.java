package wikidata.explore;

import org.junit.jupiter.api.Test;
import domain.config.FieldCardinality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikidataPropertyScoreTest {

    @Test void catalogueAutoMeansNoSuggestionRatherThanAModelCardinality() {
        WikidataProperty unknown = new WikidataProperty(
                "P39", "position held", "", "WikibaseItem", "AUTO");
        assertTrue(WikidataPropertyScore.fieldCardinality(unknown).isEmpty());

        WikidataProperty time = new WikidataProperty(
                "P571", "inception", "", "Time", "AUTO");
        assertEquals(FieldCardinality.SINGLE,
                WikidataPropertyScore.fieldCardinality(time).orElseThrow());
    }
}
