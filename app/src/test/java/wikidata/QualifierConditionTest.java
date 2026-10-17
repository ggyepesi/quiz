package wikidata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which statements a field reads under a qualifier condition: the truthy rule applied
 * AFTER the condition (#395). Wikidata keeps one apparent-magnitude statement per band;
 * an unranked star answers {@code wdt:} with every band, and MIN then picks infrared.
 */
class QualifierConditionTest {

    private static final String V = "Q4892529";
    private static final String K = "Q2520419";
    private static final QualifierCondition V_BAND =
            new QualifierCondition("P1227", List.of(V));

    private record Statement(String value, String band, String rank) {
        Map<String, List<String>> qualifiers() {
            return band == null ? Map.of() : Map.of("P1227", List.of(band));
        }
    }

    private static List<String> read(QualifierCondition condition, Statement... statements) {
        return QualifierCondition.select(List.of(statements), condition,
                Statement::rank, Statement::qualifiers).stream()
                .map(Statement::value).toList();
    }

    @Test void anUnrankedStarReadsOnlyItsVisualBand() {
        assertEquals(List.of("4.1"), read(V_BAND,
                new Statement("2.93", K, "normal"),
                new Statement("4.1", V, "normal")));
    }

    @Test void aStarWithNoVisualBandHasNoValue() {
        // IRAS 20113-2452: K 2.93, nothing in V — it is not a naked-eye star.
        assertEquals(List.of(), read(V_BAND,
                new Statement("2.93", K, "normal"),
                new Statement("9.16", "Q66659648", "normal")));
    }

    @Test void aPreferredStatementInAnotherBandDoesNotHideTheVisualOne() {
        // Taking the truthy statements first would leave only the K band and then
        // nothing — the order is the point.
        assertEquals(List.of("3.58"), read(V_BAND,
                new Statement("1.45", K, "preferred"),
                new Statement("3.58", V, "normal")));
    }

    @Test void aPreferredVisualValueWinsOverANormalOne() {
        assertEquals(List.of("3.58"), read(V_BAND,
                new Statement("3.60", V, "normal"),
                new Statement("3.58", V, "preferred")));
    }

    @Test void deprecatedStatementsNeverCount() {
        assertEquals(List.of(), read(V_BAND, new Statement("3.0", V, "deprecated")));
    }

    @Test void withoutAConditionItIsTheTruthyRule() {
        assertEquals(List.of("1.45"), read(null,
                new Statement("1.45", K, "preferred"),
                new Statement("3.58", V, "normal"),
                new Statement("0.1", V, "deprecated")));
        assertEquals(List.of("2.93", "9.16"), read(null,
                new Statement("2.93", K, "normal"),
                new Statement("9.16", null, "normal")));
    }

    @Test void anAbsentConditionStaysAbsentAndAPartialOneIsIncomplete() {
        assertNull(QualifierCondition.of(" ", List.of()));
        QualifierCondition partial = QualifierCondition.of("P1227", List.of());
        assertFalse(partial.complete());
        assertFalse(QualifierCondition.of("P1227", List.of("V")).complete(),
                "a malformed value is kept so validation can name it");
        assertTrue(V_BAND.complete());
    }
}
