package wikidata;

/**
 * How a Wikidata id reads once its name is known: {@code "position held (P39)"}.
 *
 * <p>This rule was written out ten times — in the model, in the compiled model, in rule
 * rendering, in the advisor and in a panel — once for each place that had a label and an
 * id in hand. Every copy agreed, which is exactly why it was worth collapsing: ten
 * agreeing copies are not a bug yet, they are the shape that becomes one, and carrying
 * labels through compilation had just added two more.
 *
 * <p>What differs between callers is only what to say when there is no id at all —
 * blank, "(not selected)", "(not configured)". That is each caller's wording about its
 * own emptiness, not part of this rule, so it stays with the caller.
 */
public final class LabelledId {
    private LabelledId() { }

    /**
     * {@code "label (id)"}, or the id alone when no name is known.
     *
     * @return {@code ""} when there is no id — callers wanting different wording for
     *         that case check for it themselves rather than passing it in here
     */
    public static String display(String label, String id) {
        String cleanId = clean(id);
        if (cleanId.isEmpty()) return "";
        String cleanLabel = clean(label);
        return cleanLabel.isEmpty() ? cleanId : cleanLabel + " (" + cleanId + ")";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
