package flag;

import aux.FlexibleDate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bundled enrichment for the fifty US states. Kept separate from the ordering
 * quiz so admissionDate is an ordinary State field available to browsing,
 * ViewConfig, coverage, and other quiz modes.
 */
public final class StateAdmissionDates {
    private static final String RESOURCE = "/flag/txt/us-state-admissions.tsv";

    private StateAdmissionDates() {}

    public static int apply(Map<String, State> states) {
        int matched = 0;
        for (Map.Entry<String, FlexibleDate> entry : load().entrySet()) {
            State state = states.get(entry.getKey());
            if (state != null) {
                state.setAdmissionDate(entry.getValue());
                matched++;
            }
        }
        return matched;
    }

    public static Map<String, FlexibleDate> load() {
        InputStream stream = StateAdmissionDates.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Missing resource " + RESOURCE);
        }
        Map<String, FlexibleDate> dates = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("state\t")) {
                    continue;
                }
                String[] columns = line.split("\\t", -1);
                if (columns.length != 2) {
                    throw new IllegalStateException("Invalid admission row: " + line);
                }
                FlexibleDate parsed = FlexibleDate.parse(columns[1]);
                if (parsed == null) {
                    throw new IllegalStateException("Invalid admission date: " + line);
                }
                FlexibleDate previous = dates.put(columns[0], parsed);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate state admission: " + columns[0]);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
        return Map.copyOf(dates);
    }
}
