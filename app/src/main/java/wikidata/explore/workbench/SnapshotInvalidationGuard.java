package wikidata.explore.workbench;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.io.File;
import java.util.List;

/**
 * The one decision for a configuration change that the saved instances cannot survive.
 *
 * <p>A snapshot is what one model version generated: its entities carry class NAMES, its
 * values are keyed by field names, and which entities are in it was decided by the
 * membership and evidence tests in force when it ran. Change any of that and the snapshot
 * describes a model that no longer exists — silently, because nothing compares the two.
 * Renaming a class left its instances stamped with the old name and the renamed class
 * with none; changing an evidence test left a population selected by a rule that had been
 * replaced.
 *
 * <p>So the rule is deliberately blunt: a configuration change that would alter what
 * generation produces deletes the instances first, and the modeller regenerates. No
 * migration per kind of edit, no staleness warning that leaves a half-valid snapshot in
 * place. It follows the project's "regenerate, never migrate" directive, and it is the
 * reason this is a stern prompt rather than a quiet cleanup: the instances are expensive
 * and the deletion cannot be undone.
 *
 * <p>Shaped like {@link ModelBuilderCloseGuard}: a plain state record and a pure decision
 * function, with the Swing call sitting on top, so the rule can be tested without a frame.
 */
final class SnapshotInvalidationGuard {
    static final String TITLE = "Configuration change discards generated instances";
    static final String KEEP_INSTANCES = "Keep instances, abandon the change";
    static final String DISCARD_INSTANCES = "Discard instances and change";

    enum Decision {
        /** The edit is abandoned and the instances stay. */
        ABANDON_CHANGE,
        /** The snapshots are deleted and the edit lands. */
        DISCARD_SNAPSHOTS
    }

    /**
     * @param projectName    the project whose instances are at stake
     * @param snapshots      the files that would be deleted
     * @param generationDiffers whether the change alters what generation would produce
     */
    record State(String projectName, List<File> snapshots, boolean generationDiffers) {
        State {
            projectName = projectName == null ? "" : projectName.trim();
            snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
        }

        /**
         * Only a real change to what generation produces, and only when something would
         * actually be lost. The editors call their apply paths on every Apply with
         * unchanged values — that is why this asks whether the model MOVED, not whether
         * Apply was pressed — and a project that has never generated has nothing to warn
         * about.
         */
        boolean needsAttention() {
            return generationDiffers && !snapshots.isEmpty();
        }
    }

    private SnapshotInvalidationGuard() { }

    static Decision ask(Component owner, State state) {
        if (state == null || !state.needsAttention()) return Decision.DISCARD_SNAPSHOTS;
        Object[] choices = {KEEP_INSTANCES, DISCARD_INSTANCES};
        int selected = JOptionPane.showOptionDialog(
                owner, message(state), TITLE, JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, choices, choices[0]);
        return decision(state, selected);
    }

    /**
     * Anything but an explicit choice to discard keeps the instances — closing the dialog
     * with the window button reports -1, and that must not be read as consent to delete.
     */
    static Decision decision(State state, int selected) {
        if (state == null || !state.needsAttention()) return Decision.DISCARD_SNAPSHOTS;
        return selected == 1 ? Decision.DISCARD_SNAPSHOTS : Decision.ABANDON_CHANGE;
    }

    static String message(State state) {
        StringBuilder text = new StringBuilder();
        text.append("This change alters what generation produces, so the instances\n")
                .append("already generated for \"").append(state.projectName())
                .append("\" no longer match the model.\n\n")
                .append("Applying it DELETES them. This cannot be undone, and they can\n")
                .append("only be recovered by generating again:\n\n");
        for (File file : state.snapshots()) {
            text.append("    ").append(file.getName()).append('\n');
        }
        text.append("\nThe configuration itself is not touched.");
        return text.toString();
    }
}
