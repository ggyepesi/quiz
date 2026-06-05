package wikidata.explore.ui;

/**
 * Centralizes the "Apply edits before action" pattern.
 *
 * The main UX change:
 * - Preview SPARQL automatically applies current node edits.
 * - Load results automatically applies current node edits.
 * - Apply selected node can be removed, or kept as "Apply only".
 */
public final class RuleTreeActions {

    private RuleTreeActions() {
    }

    public static void previewSparql(
            Runnable applySelectedNodeEdits,
            Runnable previewSparql) {

        if (applySelectedNodeEdits != null) {
            applySelectedNodeEdits.run();
        }

        if (previewSparql != null) {
            previewSparql.run();
        }
    }

    public static void loadResults(
            Runnable applySelectedNodeEdits,
            Runnable loadResults) {

        if (applySelectedNodeEdits != null) {
            applySelectedNodeEdits.run();
        }

        if (loadResults != null) {
            loadResults.run();
        }
    }

    public static void applyOnly(Runnable applySelectedNodeEdits) {
        if (applySelectedNodeEdits != null) {
            applySelectedNodeEdits.run();
        }
    }
}
