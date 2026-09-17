package process.swing.workflow;

import objectview.Viewable;

import javax.swing.JComponent;
import java.util.List;
import java.util.function.Supplier;

/** Immutable result tabs plus lazy decision/decorator factories for each card. */
public record ProcessWorkflowResults<D>(
        String title, String summary, String applyVerb, List<Tab<D>> tabs,
        Supplier<D> resultDecision, String closeVerb, String resultConfirmation) {
    public ProcessWorkflowResults {
        title = title == null || title.isBlank() ? "Results" : title;
        summary = summary == null ? "" : summary;
        // What THIS action's apply actually does. The host cannot know: one action
        // stages an edit for a later save, another accepts a generated run outright,
        // and a button reading "Apply" for both leaves the reader unable to tell
        // whether anything was written.
        applyVerb = applyVerb == null || applyVerb.isBlank() ? "Apply" : applyVerb;
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        closeVerb = closeVerb == null || closeVerb.isBlank()
                ? "Close without applying" : closeVerb;
        resultConfirmation = resultConfirmation == null ? "" : resultConfirmation;
    }

    public ProcessWorkflowResults(String title, String summary, String applyVerb,
                                  List<Tab<D>> tabs, Supplier<D> resultDecision,
                                  String closeVerb) {
        this(title, summary, applyVerb, tabs, resultDecision, closeVerb, "");
    }

    public ProcessWorkflowResults(String title, String summary, String applyVerb,
                                  List<Tab<D>> tabs) {
        this(title, summary, applyVerb, tabs, null, "Close without applying", "");
    }

    public ProcessWorkflowResults(String title, String summary, List<Tab<D>> tabs) {
        this(title, summary, "Apply", tabs, null, "Close without applying", "");
    }

    public record Tab<D>(String title, List<Card<D>> cards, Viewable shapeSample,
                         List<SelectionAction> selectionActions) {
        public Tab {
            title = title == null ? "Results" : title;
            cards = cards == null ? List.of() : List.copyOf(cards);
            selectionActions = selectionActions == null
                    ? List.of() : List.copyOf(selectionActions);
        }
        public Tab(String title, List<Card<D>> cards) {
            this(title, cards, null, List.of());
        }
        public Tab(String title, List<Card<D>> cards, Viewable shapeSample) {
            this(title, cards, shapeSample, List.of());
        }
    }

    /** A result-tab edit that changes only the selected displayed values. The host
     * refreshes those cards in place; it does not close the workflow or rebuild the
     * complete virtualized panel. */
    public record SelectionAction(
            String label, java.util.function.Consumer<List<Viewable>> apply) {
        public SelectionAction {
            label = label == null ? "Apply to selection" : label;
            java.util.Objects.requireNonNull(apply, "apply");
        }
    }

    /** Decision is read at Apply time, allowing a visible card control to update it. */
    public record Card<D>(Viewable view, Supplier<D> decision,
                          boolean includeInApplyAll, Supplier<JComponent> decoration) {
        public Card {
            java.util.Objects.requireNonNull(view, "view");
            decision = decision == null ? () -> null : decision;
            decoration = decoration == null ? () -> null : decoration;
        }
        public Card(Viewable view, Supplier<D> decision, boolean includeInApplyAll) {
            this(view, decision, includeInApplyAll, null);
        }
    }
}
