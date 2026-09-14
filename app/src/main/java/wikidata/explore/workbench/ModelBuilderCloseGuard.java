package wikidata.explore.workbench;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Component;

/** The one close decision for running work and work that exists only in memory. */
final class ModelBuilderCloseGuard {
    static final String TITLE = "Close ModelBuilder";
    static final String KEEP_WORKING = "Keep working";
    static final String SAVE_AND_CLOSE = "Save and close";
    static final String CLOSE_WITHOUT_SAVING = "Close without saving";
    static final String CANCEL_AND_CLOSE = "Cancel work and close";

    enum Decision {
        KEEP_OPEN,
        SAVE_AND_CLOSE,
        CLOSE_WITHOUT_SAVING,
        CANCEL_AND_CLOSE
    }

    record State(boolean unsavedConfiguration, int unsavedInstances, boolean running) {
        State {
            unsavedInstances = Math.max(0, unsavedInstances);
        }

        boolean needsAttention() {
            return running || unsavedConfiguration || unsavedInstances > 0;
        }
    }

    private ModelBuilderCloseGuard() { }

    static Decision ask(Component owner, State state) {
        if (state == null || !state.needsAttention()) {
            return Decision.CLOSE_WITHOUT_SAVING;
        }
        Object[] choices = choices(state);
        int selected = JOptionPane.showOptionDialog(
                owner, content(state), TITLE, JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, choices, choices[0]);
        return decision(state, selected);
    }

    static Object[] choices(State state) {
        return state.running()
                ? new Object[] {KEEP_WORKING, CANCEL_AND_CLOSE}
                : new Object[] {KEEP_WORKING, SAVE_AND_CLOSE, CLOSE_WITHOUT_SAVING};
    }

    static Decision decision(State state, int selected) {
        if (selected <= 0) return Decision.KEEP_OPEN;
        if (state.running()) {
            return selected == 1 ? Decision.CANCEL_AND_CLOSE : Decision.KEEP_OPEN;
        }
        return switch (selected) {
            case 1 -> Decision.SAVE_AND_CLOSE;
            case 2 -> Decision.CLOSE_WITHOUT_SAVING;
            default -> Decision.KEEP_OPEN;
        };
    }

    /** The actual dialog body is a component so the visible contract can be rendered in tests. */
    static JComponent content(State state) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        if (state.running()) {
            panel.add(new JLabel("A process is still running."));
            panel.add(new JLabel("Closing will cancel it and wait for the current operation to stop."));
        } else {
            panel.add(new JLabel("The following work has not been saved:"));
        }
        if (state.unsavedConfiguration()) {
            panel.add(Box.createVerticalStrut(6));
            panel.add(new JLabel("\u2022 Configuration changes"));
        }
        if (state.unsavedInstances() > 0) {
            panel.add(Box.createVerticalStrut(3));
            panel.add(new JLabel("\u2022 " + state.unsavedInstances()
                    + " generated instance" + (state.unsavedInstances() == 1 ? "" : "s")));
        }
        if (state.running()) {
            panel.add(Box.createVerticalStrut(8));
            panel.add(new JLabel("Running work cannot be saved until it finishes."));
            if (state.unsavedConfiguration() || state.unsavedInstances() > 0) {
                panel.add(new JLabel("Cancel work and close also discards the unsaved items above."));
            }
        } else {
            panel.add(Box.createVerticalStrut(8));
            panel.add(new JLabel("Save and close uses the same Save domain action."));
        }
        return panel;
    }
}
