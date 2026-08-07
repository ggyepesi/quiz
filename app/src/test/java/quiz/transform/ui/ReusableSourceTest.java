package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.curation.Correction;
import quiz.curation.CorrectionPolicy;
import quiz.curation.ValueSource;
import wikidata.explore.model.FieldSourceMapping;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A field curated before is re-sourced from the provenance on its filled values, so the
 *  property (e.g. population -> P1082) is reused instead of re-discovered. */
class ReusableSourceTest {

    private static Correction filled(String type, String field, Object value, String pid) {
        ValueSource source = new ValueSource("Wikidata", "Q1", pid, field, "ROOT_TO_ITEM", null);
        return new Correction(type, "Q1", field, value, "wikidata", null,
                CorrectionPolicy.FILL_IF_EMPTY, source);
    }

    @Test void reusesThePropertyRecordedOnFilledValues() {
        List<Correction> corrections = List.of(
                filled("State", "population", 100, "P1082"),
                filled("State", "population", 200, "P1082"));

        FieldSourceMapping reused =
                ValidationPanel.reusableSource(corrections, "State", "population");

        assertEquals("P1082", reused.propertyPid());
        assertEquals("population", reused.propertyLabel());
    }

    @Test void prefersTheMostUsedPropertyWhenSeveralAppear() {
        List<Correction> corrections = List.of(
                filled("State", "population", 1, "P1082"),
                filled("State", "population", 2, "P1082"),
                filled("State", "population", 3, "P1120"));   // outlier, used once

        assertEquals("P1082",
                ValidationPanel.reusableSource(corrections, "State", "population").propertyPid());
    }

    @Test void nullWhenTheFieldWasNeverSourced() {
        List<Correction> corrections = List.of(
                filled("State", "capital", 1, "P36"));   // a different field
        assertNull(ValidationPanel.reusableSource(corrections, "State", "population"));
        assertNull(ValidationPanel.reusableSource(List.of(), "State", "population"));
    }

    @Test void ignoresNonWikidataProvenance() {
        ValueSource dbpedia = new ValueSource("DBpedia", "Q1", "populationTotal",
                "populationTotal", "ROOT_TO_ITEM", null);
        List<Correction> corrections = List.of(new Correction(
                "State", "Q1", "population", 5, "dbpedia", null,
                CorrectionPolicy.FILL_IF_EMPTY, dbpedia));
        // Only the primary (Wikidata) source is reused here; the DBpedia fallback is separate.
        assertNull(ValidationPanel.reusableSource(corrections, "State", "population"));
    }
}
