package process.swing.workflow;

import objectview.Viewable;

import javax.swing.JComponent;
import java.util.List;
import java.util.function.Supplier;

/** Immutable result tabs plus lazy decision/decorator factories for each card. */
public record ProcessWorkflowResults<D>(
        String title, String summary, String applyVerb, List<Tab<D>> tabs) {
    public ProcessWorkflowResults {
        title = title == null || title.isBlank() ? "Results" : title;
        summary = summary == null ? "" : summary;
        // What THIS action's apply actually does. The host cannot know: one action
        // stages an edit for a later save, another accepts a generated run outright,
        // and a button reading "Apply" for both leaves the reader unable to tell
        // whether anything was written.
        applyVerb = applyVerb == null || applyVerb.isBlank() ? "Apply" : applyVerb;
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
    }

    public ProcessWorkflowResults(String title, String summary, List<Tab<D>> tabs) {
        this(title, summary, "Apply", tabs);
    }

    public record Tab<D>(String title, List<Card<D>> cards) {
        public Tab {
            title = title == null ? "Results" : title;
            cards = cards == null ? List.of() : List.copyOf(cards);
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
