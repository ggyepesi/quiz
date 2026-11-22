package domain.transform.ui;

import flag.State;
import org.junit.jupiter.api.Test;
import domain.workbench.execution.ui.SwingQueryRunner;
import work.QueryContext;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The actions DataSourceApp and TransformApp offer, checked in (#536).
 *
 * <p>Both applications open the same workbench and field pane; one {@link WorkbenchPurpose}
 * decides which explicit actions appear. A test of the enum alone restates the enum and
 * cannot see a button landing in the wrong application, so this renders both workbenches
 * and both field panes and compares the visible controls with
 * {@code docs/workbench-actions.txt}. Regenerate with {@code -Dworkbench.actions.write=true}
 * and read the diff.
 */
class WorkbenchActionsAreCheckedInTest {

    private static final Path GOLDEN = Path.of("../docs/workbench-actions.txt");

    @Test void eachApplicationShowsOnlyTheActionsItOwns() throws Exception {
        StringBuilder actual = new StringBuilder();
        for (WorkbenchPurpose purpose : WorkbenchPurpose.values()) {
            TransformWorkbenchPanel[] workbench = new TransformWorkbenchPanel[1];
            SwingUtilities.invokeAndWait(() -> workbench[0] = new TransformWorkbenchPanel(
                    domain(), null, "States", purpose));
            // The instance pane and its header actions arrive from a background render.
            boolean[] rendered = new boolean[1];
            for (int wait = 0; wait < 200 && !rendered[0]; wait++) {
                SwingUtilities.invokeAndWait(() ->
                        rendered[0] = named(workbench[0], "field.action") != null);
                if (!rendered[0]) Thread.sleep(25);
            }
            assertTrue(rendered[0], purpose + " workbench never rendered its instance pane");
            SwingUtilities.invokeAndWait(() -> {
                describe(actual, purpose + " workbench", workbench[0]);
                workbench[0].close();

                ValidationPanel field = new ValidationPanel(domain(),
                        List.of(new State("France")),
                        new SwingQueryRunner(new QueryContext(), null),
                        () -> { }, null, purpose);
                field.useFixedTarget("State", "name", "1 selected instance");
                describe(actual, purpose + " field pane - State.name", field);
            });
        }

        String rendered = actual.toString().stripTrailing() + "\n";
        if (Boolean.getBoolean("workbench.actions.write")) Files.writeString(GOLDEN, rendered);
        String checkedIn = Files.isRegularFile(GOLDEN) ? Files.readString(GOLDEN) : "";
        if (!checkedIn.equals(rendered)) {
            Path seen = GOLDEN.resolveSibling("workbench-actions.actual.txt");
            Files.writeString(seen, rendered);
            assertEquals(checkedIn, rendered, "the workbench actions no longer match "
                    + GOLDEN + "; diff it against " + seen
                    + ", then re-run with -Dworkbench.actions.write=true to accept");
        }
    }

    private static Component named(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                Component found = named(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ReflectionDomain domain() {
        return new ReflectionDomain(List.of(new State("France")));
    }

    private static void describe(StringBuilder out, String title, Container root) {
        out.append("== ").append(title).append(" ==\n");
        walk(out, root, 1);
        out.append('\n');
    }

    private static void walk(StringBuilder out, Container container, int depth) {
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) continue;
            String line = describe(child);
            int next = depth;
            if (line != null) {
                out.append("  ".repeat(depth)).append(line).append('\n');
                next = depth + 1;
            }
            if (child instanceof Container nested) walk(out, nested, next);
        }
    }

    /** Controls and their boxes only; counts and status text describe data, not actions. */
    private static String describe(Component component) {
        if (component instanceof javax.swing.JComponent widget
                && widget.getBorder() instanceof TitledBorder titled
                && !plain(titled.getTitle()).isBlank()) {
            return "box: " + plain(titled.getTitle());
        }
        if (component instanceof AbstractButton button) {
            String text = plain(button.getText());
            if (text.isBlank()) return null;
            return "button: " + text + (button.isEnabled() ? "" : "  (disabled)");
        }
        if (component instanceof JComboBox<?>) return "combo";
        if (component instanceof JLabel label && label.getParent() != null
                && label.getParent().getComponentCount() > 1) {
            String text = plain(label.getText());
            return text.isBlank() || text.matches(".*\\d.*") ? null : "label: " + text;
        }
        return null;
    }

    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
