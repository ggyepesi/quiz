package process.swing.workflow;

import objectview.Viewable;

import javax.swing.JComponent;
import java.util.List;
import java.util.function.Function;

/** Immutable pre-execution presentation supplied by a workflow action. */
public record ProcessWorkflowPlan(
        String title, String description, List<Tab> tabs,
        boolean executable, String noWorkMessage) {
    public ProcessWorkflowPlan {
        title = title == null || title.isBlank() ? "Curation" : title;
        description = description == null ? "" : description;
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        noWorkMessage = noWorkMessage == null || noWorkMessage.isBlank()
                ? "Nothing to execute." : noWorkMessage;
    }

    public ProcessWorkflowPlan(String title, String description, List<Tab> tabs) {
        this(title, description, tabs, true, "");
    }

    public record Tab(String title, List<? extends Viewable> cards,
                      Function<Viewable, JComponent> decoration) {
        public Tab {
            title = title == null ? "Items" : title;
            cards = cards == null ? List.of() : List.copyOf(cards);
            decoration = decoration == null ? ignored -> null : decoration;
        }
        public Tab(String title, List<? extends Viewable> cards) {
            this(title, cards, null);
        }
    }
}
