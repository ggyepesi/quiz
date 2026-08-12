package process.swing.workflow;

import objectview.Viewable;

import javax.swing.JComponent;
import java.util.List;
import java.util.function.Supplier;

/** Immutable result tabs plus lazy decision/decorator factories for each card. */
public record ProcessWorkflowResults<D>(String title, String summary, List<Tab<D>> tabs) {
    public ProcessWorkflowResults {
        title = title == null || title.isBlank() ? "Results" : title;
        summary = summary == null ? "" : summary;
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
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
