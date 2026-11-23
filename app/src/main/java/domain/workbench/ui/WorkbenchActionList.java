package domain.workbench.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;

/**
 * One rendering of the actions a workbench offers for its current selection.
 *
 * <p>The owner decides which actions are valid; this component gives every application
 * the same vertical, readable presentation instead of hiding operations in unrelated
 * source-specific button rows.</p>
 */
public final class WorkbenchActionList extends JPanel {

    /** One explicit operation and the effect the reader should expect before running it. */
    public record Item(AbstractButton action, String description) {
        public Item {
            if (action == null) throw new IllegalArgumentException("Action is required");
            description = description == null ? "" : description.trim();
        }
    }

    public WorkbenchActionList(List<Item> items) {
        super();
        setName("workbench.available.actions");
        setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Available actions"));
        for (Item item : items == null ? List.<Item>of() : items) {
            JPanel row = new JPanel(new BorderLayout(10, 2));
            row.setName("workbench.available.action");
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.add(item.action(), BorderLayout.WEST);
            if (!item.description().isBlank()) {
                JLabel description = new JLabel("<html>" + item.description() + "</html>");
                description.setEnabled(false);
                row.add(description, BorderLayout.CENTER);
            }
            add(row);
        }
    }
}
