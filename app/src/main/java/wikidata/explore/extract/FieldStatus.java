package wikidata.explore.extract;

/**
 * Why a field has no value, when the source said so explicitly.
 *
 * <p>Wikidata distinguishes three things an empty field can mean, and collapsing them
 * into blankness loses the two that are answers:
 *
 * <ul>
 *   <li>no statement at all — the source does not know. A real gap; another source
 *       might fill it, so curation should keep offering it.</li>
 *   <li>{@code somevalue} — the source asserts a value EXISTS and is unknown. Seven's
 *       narrative location is a deliberately unnamed city. Nothing will ever fetch it,
 *       so offering it forever is a worklist item that can never be cleared.</li>
 *   <li>{@code novalue} — the source asserts there is NO such relation. Correctly
 *       empty, and equally not a gap.</li>
 * </ul>
 *
 * <p>Only the last two are recorded. Absence of a status IS the first case, which keeps
 * the common path free of bookkeeping.
 */
public enum FieldStatus {

    /** The source says a value exists but is unknown ({@code somevalue}). */
    ASSERTED_UNKNOWN("unknown"),

    /** The source says there is no such value ({@code novalue}). */
    ASSERTED_NONE("none");

    private final String stored;

    FieldStatus(String stored) {
        this.stored = stored;
    }

    /** The token persisted in a snapshot — short and stable, so the enum can be renamed
     *  without rewriting saved data. */
    public String stored() {
        return stored;
    }

    public static FieldStatus fromStored(String token) {
        if (token == null) return null;
        for (FieldStatus status : values()) {
            if (status.stored.equals(token)) return status;
        }
        return null;
    }
}
