package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.transform.FieldExpectations.FieldCoverage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #96: the coverage an EXPECTED field reports is the entire reason for declaring it, and
 * for a while nothing read it. It reached the run log and stopped — so the agreed flow,
 * declare EXPECTED then look at the N missing then escalate to REQUIRED, had no step two.
 */
class CoverageReportTest {

    private static FieldCoverage coverage(String field, FieldExpectation level,
                                          int total, int present) {
        return new FieldCoverage("Nomination", field, level, total, present);
    }

    @Test void aRunThatMeetsItsExpectationsSaysNothing() {
        assertEquals("", CoverageReport.message(List.of(
                coverage("ceremony", FieldExpectation.EXPECTED, 15192, 15192))),
                "full coverage is not news; silence has to mean the expectations held");
    }

    @Test void noExpectationsAtAllIsAlsoSilence() {
        assertEquals("", CoverageReport.message(List.of()));
        assertEquals("", CoverageReport.message(null));
    }

    @Test void aGapIsReportedWithBothSidesOfTheRatio() {
        String message = CoverageReport.message(List.of(
                coverage("ceremony", FieldExpectation.EXPECTED, 15192, 15068)));

        assertTrue(message.contains("Nomination.ceremony: 15068/15192 present, 124 missing"),
                message);
        assertTrue(message.contains("kept (Expected)"),
                "EXPECTED keeps the rows, and the message must not read like a deletion: "
                        + message);
    }

    @Test void aKeptGapNamesHowToReachTheRecords() {
        // The half that was missing: a number the reader cannot act on is where this
        // started. EXPECTED rows are still in the pool, so the route exists.
        String message = CoverageReport.message(List.of(
                coverage("ceremony", FieldExpectation.EXPECTED, 15192, 15068)));

        assertTrue(message.contains("present/missing"), message);
    }

    @Test void aRequiredDropIsNotOfferedAsSomethingToGoAndLookAt() {
        String message = CoverageReport.message(List.of(
                coverage("ceremony", FieldExpectation.REQUIRED, 15192, 15068)));

        assertTrue(message.contains("dropped (Required)"), message);
        assertTrue(!message.contains("present/missing"),
                "those records are gone — pointing at a facet of them would be a lie: "
                        + message);
    }

    @Test void theWorstGapIsReportedFirst() {
        List<String> lines = CoverageReport.lines(List.of(
                coverage("ceremony", FieldExpectation.EXPECTED, 100, 98),
                coverage("nominee", FieldExpectation.EXPECTED, 100, 40),
                coverage("forWork", FieldExpectation.EXPECTED, 100, 90)));

        assertEquals(List.of("nominee", "forWork", "ceremony"),
                lines.stream().map(line -> line.substring(
                        "Nomination.".length(), line.indexOf(':'))).toList(),
                "a reader scanning one line should see the biggest hole");
    }
}
