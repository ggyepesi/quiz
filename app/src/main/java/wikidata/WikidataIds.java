package wikidata;

import java.util.regex.Pattern;

/**
 * The one place the Wikidata id formats are decided — an entity {@code QID}
 * ({@code Q} + digits), a property {@code PID} ({@code P} + digits) and a STATEMENT id
 * ({@code Q42$} + a GUID) — so no caller re-spells the regex. Null-safe: a null or
 * malformed string is simply not an id.
 */
public final class WikidataIds {

    private static final Pattern QID = Pattern.compile("Q\\d+");
    private static final Pattern PID = Pattern.compile("P\\d+");
    // A statement id names one claim ON an entity: "Q72717$67ADCA97-2FF9-43AD-...".
    // Wikidata writes the GUID in either case, and the ENTITY PREFIX in either case
    // too — its own statement ids are lower-cased there ("q76555$82129A1D-…"), which
    // this required to be upper. Half the shipped data is written the lower way:
    // Oscars' nominations are "Q72717$…" and History's holdings "q76555$…", so a
    // pattern accepting only one silently answers "not a statement" for the other.
    private static final Pattern STATEMENT =
            Pattern.compile("([Qq]\\d+)\\$[0-9a-fA-F-]+");

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

    /**
     * Whether {@code s} identifies a STATEMENT rather than an entity.
     *
     * <p>The distinction is not cosmetic: a statement id is already a Wikidata anchor, but
     * it names a claim about an entity, not a thing with a label. No label search can find
     * one, so an instance carrying such an id — a reified nomination, a held-position — has
     * no entity identity to resolve, and searching by its display name (borrowed from the
     * entity the statement is about) would confidently link the statement to that entity.</p>
     */
    public static boolean isStatementId(String s) {
        return s != null && STATEMENT.matcher(s).matches();
    }

    /**
     * The entity a statement is about ({@code Q72717$…} → {@code Q72717}), else null.
     *
     * <p>Always the canonical upper-case QID, whichever case the statement id was
     * written in — a lower-cased prefix is still that entity, and returning it verbatim
     * would hand back something {@link #isQid} rejects and no pool lookup would match.
     */
    public static String statementSubject(String s) {
        if (s == null) {
            return null;
        }
        var matcher = STATEMENT.matcher(s);
        return matcher.matches() ? "Q" + matcher.group(1).substring(1) : null;
    }
}
