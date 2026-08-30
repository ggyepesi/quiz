package wikidata;

/**
 * Carries the language of a Wikidata monolingual-text value beside its text, and reads
 * it back.
 *
 * <p>A monolingual text states its language on the literal itself, unlike a claim value
 * qualified by {@code P407}. That language used to be dropped where the datavalue is
 * read, so every wording of one fact arrived indistinguishable from every other: the
 * Nobel award rationale (P6208) is stated in about thirteen languages, and a field
 * loading it received roughly two values per statement with no way to tell them apart.
 *
 * <p>This is the same arrangement {@link CalendarModelCodec} uses for a time's calendar:
 * the wire form carries the extra fact, and ONE codec reads it, so the reader and the
 * writer agree by construction rather than by both remembering the same convention.
 */
public final class MonolingualTextCodec {

    /** Separates the text from the language stated on it, as RDF spells it. */
    private static final String LANGUAGE_SEPARATOR = "@";

    /** A language tag: letters, digits and hyphens, as BCP 47 writes them. */
    private static final java.util.regex.Pattern TAG =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*");

    private MonolingualTextCodec() {
    }

    /**
     * The suffix stating {@code language}, or nothing when the source stated none.
     * Absence stays absence: an untagged value is not silently called English.
     */
    public static String mark(String language) {
        String tag = language == null ? "" : language.trim();
        return tag.isEmpty() || !TAG.matcher(tag).matches()
                ? "" : LANGUAGE_SEPARATOR + tag;
    }

    /** The text without its language, which is what a reader is shown. */
    public static String text(String wireValue) {
        int at = separator(wireValue);
        return at < 0 ? wireValue : wireValue.substring(0, at);
    }

    /** The language stated on the value, or {@code ""} when it states none. */
    public static String language(String wireValue) {
        int at = separator(wireValue);
        return at < 0 ? "" : wireValue.substring(at + 1);
    }

    /**
     * Whether this value may be read as {@code language}.
     *
     * <p>A value stating no language is admitted by any request: it does not contradict
     * what was asked for, and refusing it would drop the values a source left untagged
     * rather than the ones in another language.
     */
    public static boolean isIn(String wireValue, String language) {
        String wanted = language == null ? "" : language.trim();
        if (wanted.isEmpty()) return true;
        String stated = language(wireValue);
        return stated.isEmpty() || stated.equalsIgnoreCase(wanted);
    }

    /**
     * The index of the language separator, or -1 when the value carries no tag.
     * Read from the END, because the text may contain the separator itself, and only a
     * well-formed tag after the last one counts as a language.
     */
    private static int separator(String wireValue) {
        if (wireValue == null) return -1;
        int at = wireValue.lastIndexOf(LANGUAGE_SEPARATOR);
        if (at < 0 || at == wireValue.length() - 1) return -1;
        return TAG.matcher(wireValue.substring(at + 1)).matches() ? at : -1;
    }
}
