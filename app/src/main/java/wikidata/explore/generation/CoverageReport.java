package wikidata.explore.generation;

import wikidata.explore.model.FieldExpectation;
import wikidata.explore.transform.FieldExpectations.FieldCoverage;

import java.util.ArrayList;
import java.util.List;

/**
 * What a run's declared field expectations found, said out loud (#96).
 *
 * <p>An EXPECTED field is one the modeller declared should be present and chose NOT to
 * drop rows for, so the whole value of declaring it is the number that comes back — and
 * the number only means anything AFTER a run, on the honest field. The count reached the
 * run log and stopped there: nothing read it when the run was accepted, so the agreed
 * flow (declare EXPECTED, look at the N missing, escalate to REQUIRED once they turn out
 * to be bad data) had no step two.
 *
 * <p>Only fields with something to say are reported. A field at full coverage is not
 * news; a REQUIRED field that dropped nothing is not either. Silence means the
 * expectations held.
 */
public final class CoverageReport {

    private CoverageReport() {}

    /**
     * One line per field whose expectation was not fully met, worst coverage first, or
     * an empty list when everything the model expects is present.
     */
    public static List<String> lines(List<FieldCoverage> coverage) {
        List<FieldCoverage> unmet = new ArrayList<>();
        for (FieldCoverage field : coverage == null ? List.<FieldCoverage>of() : coverage) {
            if (field != null && field.missing() > 0) {
                unmet.add(field);
            }
        }
        unmet.sort((a, b) -> Integer.compare(b.missing(), a.missing()));

        List<String> lines = new ArrayList<>();
        for (FieldCoverage field : unmet) {
            lines.add(field.className() + "." + field.fieldName() + ": "
                    + field.present() + "/" + field.total() + " present, "
                    + field.missing() + " missing"
                    + (field.level() == FieldExpectation.REQUIRED
                            ? " — dropped (Required)"
                            : " — kept (Expected)"));
        }
        return lines;
    }

    /**
     * The whole report as one message, or the empty string when there is nothing to
     * report — so a caller can log it unconditionally and stay quiet on a clean run.
     *
     * <p>An EXPECTED gap ends with the route to the records themselves. A number the
     * reader cannot act on is the state this was in before.
     */
    public static String message(List<FieldCoverage> coverage) {
        List<String> lines = lines(coverage);
        if (lines.isEmpty()) {
            return "";
        }
        boolean anyKept = coverage.stream()
                .anyMatch(field -> field != null && field.missing() > 0
                        && field.level() == FieldExpectation.EXPECTED);
        return "Field expectations — " + String.join("; ", lines)
                + (anyKept
                        ? ". The kept records are still in the pool: facet the field by "
                                + "present/missing in Transform to select the ones missing it."
                        : ".");
    }
}
