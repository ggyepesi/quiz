package quiz.ui;

import quiz.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class QuizableCollectionPanel extends JPanel {

    public QuizableCollectionPanel(Set<Object> visited,
                                   Set<Object> ancestors,
                                   QuizableRenderContext renderContext,
                                   List<String> path,
                                   String fieldName,
                                   Collection<?> collection,
                                   QuizablePanelConfig config,
                                   boolean fill) {

        setLayout(new BorderLayout(6, 0));
        setOpaque(false);

        if (fieldName != null && !fieldName.isEmpty()) {
            JLabel label = new JLabel(fieldName + ": ");
            label.setVerticalAlignment(SwingConstants.TOP);
            add(label, BorderLayout.WEST);
        }

        JPanel values = new JPanel();
        values.setLayout(new BoxLayout(values, BoxLayout.Y_AXIS));
        values.setOpaque(false);

        boolean added = false;

        for (Object item : collection) {
            JComponent comp = QuizableValueRenderer.createFieldComponent(
                    visited,
                    ancestors,
                    renderContext,
                    fieldName,
                    path,
                    item,
                    config,
                    fill);

            if (comp == null) {
                continue;
            }

            values.add(comp);
            added = true;
        }

        if (!added) {
            values.add(new JLabel("(empty)"));
        }

        add(values, BorderLayout.CENTER);
    }
}