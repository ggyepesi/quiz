package process.swing.workflow;

import objectview.Viewable;

import javax.swing.JComponent;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Immutable pre-execution presentation supplied by a workflow action. */
public record ProcessWorkflowPlan(
        String title, String description, List<Tab> tabs,
        boolean executable, String noWorkMessage, boolean pipelineVisible) {
    public ProcessWorkflowPlan {
        title = title == null || title.isBlank() ? "Curation" : title;
        description = description == null ? "" : description;
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        noWorkMessage = noWorkMessage == null || noWorkMessage.isBlank()
                ? "Nothing to execute." : noWorkMessage;
    }

    public ProcessWorkflowPlan(String title, String description, List<Tab> tabs) {
        this(title, description, tabs, true, "", true);
    }

    public ProcessWorkflowPlan(String title, String description, List<Tab> tabs,
                               boolean executable, String noWorkMessage) {
        this(title, description, tabs, executable, noWorkMessage, true);
    }

    /** Keep pipeline progress at run time without presenting it as a second plan. */
    public ProcessWorkflowPlan withoutPipelineTab() {
        return new ProcessWorkflowPlan(title, description, tabs,
                executable, noWorkMessage, false);
    }

    public record Tab(String title, List<? extends Viewable> cards,
                      Function<Viewable, JComponent> decoration,
                      Supplier<? extends JComponent> content,
                      boolean initiallySelected) {
        public Tab {
            title = title == null ? "Items" : title;
            cards = cards == null ? List.of() : List.copyOf(cards);
            decoration = decoration == null ? ignored -> null : decoration;
        }
        public Tab(String title, List<? extends Viewable> cards) {
            this(title, cards, null, null, false);
        }
        public Tab(String title, List<? extends Viewable> cards,
                   Function<Viewable, JComponent> decoration) {
            this(title, cards, decoration, null, false);
        }
        public static Tab component(
                String title, Supplier<? extends JComponent> content) {
            return component(title, content, false);
        }
        public static Tab component(String title,
                                    Supplier<? extends JComponent> content,
                                    boolean initiallySelected) {
            return new Tab(title, List.of(), null,
                    java.util.Objects.requireNonNull(content, "content"),
                    initiallySelected);
        }
    }
}
