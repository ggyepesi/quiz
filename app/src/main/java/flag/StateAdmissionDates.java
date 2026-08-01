package flag;

import aux.FlexibleDate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bundled enrichment for the fifty US states. {@code admissionDate} lives on the
 * {@link USState} subclass (it is null for every non-US State), so this SEEDS each US
 * state as a {@link USState} up front — before the flag/shape/capital readers get-or-create
 * states by name — and every later {@code computeIfAbsent(name, State::new)} then augments
 * the same USState object rather than replacing it.
 */
public final class StateAdmissionDates {
    private static final String RESOURCE = "/flag/txt/us-state-admissions.tsv";

    private StateAdmissionDates() {}

    /** Pre-register the fifty US states as {@link USState}s carrying their admission date.
     *  Run BEFORE any reader so the get-or-create readers find and augment these objects. */
    public static int seed(Map<String, State> states) {
        int seeded = 0;
        for (Map.Entry<String, FlexibleDate> entry : load().entrySet()) {
            USState state = new USState(entry.getKey());
            state.setAdmissionDate(entry.getValue());
            states.put(entry.getKey(), state);
            seeded++;
        }
        return seeded;
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
