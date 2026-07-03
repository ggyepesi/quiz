package quiz.transform.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import quiz.transform.ViewSpec;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Saves/loads a {@link ViewSpec} as JSON (the declarative, editable view). */
public final class ViewSpecJsonIO {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ViewSpecJsonIO() {}

    public static void save(File file, ViewSpec spec) {
        try {
            MAPPER.writeValue(file, spec);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ViewSpec load(File file) {
        try {
            return MAPPER.readValue(file, ViewSpec.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parse a filter value typed in the UI: true/false → Boolean, integer →
     *  Integer, decimal → Double, else the trimmed String. */
    public static Object parseValue(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.equalsIgnoreCase("true"))  return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        try { return Integer.valueOf(s); } catch (NumberFormatException ignored) { }
        try { return Double.valueOf(s); } catch (NumberFormatException ignored) { }
        return s;
    }
}
