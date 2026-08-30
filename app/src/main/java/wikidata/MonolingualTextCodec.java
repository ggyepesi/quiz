package wikidata;

import java.util.ArrayList;
import java.util.List;

/** Losslessly carries a Wikidata monolingual-text value through a string boundary. */
public final class MonolingualTextCodec {
    private static final String VALUE = "\u001eMLT:";
    private static final String RAW = "\u001eRAW:";
    private static final java.util.regex.Pattern TAG =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*");

    private MonolingualTextCodec() { }

    /** A length-framed value; ordinary text ending in {@code @home} is unambiguous. */
    public static String encode(String text, String language) {
        if (text == null) return null;
        String tag = language == null ? "" : language.trim();
        if (tag.isEmpty() || !TAG.matcher(tag).matches()) return plain(text);
        return VALUE + tag.length() + ":" + tag + text;
    }

    /** Escapes an ordinary string if it begins with an internal frame marker. */
    public static String plain(String text) {
        if (text == null) return null;
        return text.startsWith(VALUE) || text.startsWith(RAW) ? RAW + text : text;
    }

    /** The text without transport metadata. */
    public static String text(String wireValue) {
        if (wireValue == null) return null;
        if (wireValue.startsWith(RAW)) return wireValue.substring(RAW.length());
        Frame frame = frame(wireValue);
        return frame == null ? wireValue : wireValue.substring(frame.textStart());
    }

    /** The stated language, or {@code ""} for an ordinary/untagged value. */
    public static String language(String wireValue) {
        Frame frame = frame(wireValue);
        return frame == null ? "" : frame.language();
    }

    /**
     * Exact tagged matches win. Untagged values are a fallback only when the requested
     * language has no answer; blank preserves every wording. Results are plain text.
     */
    public static List<String> select(List<String> wireValues, String language) {
        if (wireValues == null || wireValues.isEmpty()) return List.of();
        String wanted = language == null ? "" : language.trim();
        List<String> exact = new ArrayList<>();
        List<String> untagged = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (String value : wireValues) {
            if (value == null) continue;
            String decoded = text(value);
            if (decoded == null || decoded.isBlank()) continue;
            all.add(decoded);
            String stated = language(value);
            if (stated.isEmpty()) untagged.add(decoded);
            else if (stated.equalsIgnoreCase(wanted)) exact.add(decoded);
        }
        if (wanted.isEmpty()) return List.copyOf(all);
        return !exact.isEmpty() ? List.copyOf(exact) : List.copyOf(untagged);
    }

    private static Frame frame(String value) {
        if (value == null || !value.startsWith(VALUE)) return null;
        int lengthEnd = value.indexOf(':', VALUE.length());
        if (lengthEnd < 0) return null;
        int length;
        try {
            length = Integer.parseInt(value.substring(VALUE.length(), lengthEnd));
        } catch (NumberFormatException invalid) {
            return null;
        }
        int languageStart = lengthEnd + 1;
        int textStart = languageStart + length;
        if (length <= 0 || textStart > value.length()) return null;
        String tag = value.substring(languageStart, textStart);
        return TAG.matcher(tag).matches() ? new Frame(tag, textStart) : null;
    }

    private record Frame(String language, int textStart) { }
}
