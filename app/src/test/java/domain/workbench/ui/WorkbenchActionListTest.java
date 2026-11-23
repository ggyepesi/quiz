package domain.workbench.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorkbenchActionListTest {

    /** The list presents the owner's action; it never creates a second execution path. */
    @Test void presentsAndInvokesTheSuppliedAction() {
        AtomicInteger executions = new AtomicInteger();
        JButton existing = new JButton("Find table-row instances…");
        existing.addActionListener(event -> executions.incrementAndGet());

        WorkbenchActionList list = new WorkbenchActionList(List.of(
                new WorkbenchActionList.Item(existing,
                        "Read the configured table and review proposed rows.")));

        javax.swing.JPanel row = (javax.swing.JPanel) list.getComponent(0);
        assertSame(existing, row.getComponent(0));
        existing.doClick();
        assertEquals(1, executions.get());
    }
}
