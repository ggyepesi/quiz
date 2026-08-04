package wikidata;

import java.util.regex.Pattern;

/**
 * The one place the Wikidata id formats are decided — an entity {@code QID}
 * ({@code Q} + digits) and a property {@code PID} ({@code P} + digits) — so no
 * caller re-spells the regex. Null-safe: a null or malformed string is simply
 * not an id.
 */
public final class WikidataIds {

    private static final Pattern QID = Pattern.compile("Q\\d+");
    private static final Pattern PID = Pattern.compile("P\\d+");

    private WikidataIds() { }

    /** Whether {@code s} is a Wikidata entity id (a QID). */
    public static boolean isQid(String s) {
        return s != null && QID.matcher(s).matches();
    }

    /** Whether {@code s} is a Wikidata property id (a PID). */
    public static boolean isPid(String s) {
        return s != null && PID.matcher(s).matches();
    }

    /** A Wikidata id of either kind — an entity (QID) or a property (PID). */
    public static boolean isId(String s) {
        return isQid(s) || isPid(s);
    }
}
