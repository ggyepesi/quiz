package quiz.ui;

import quiz.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QuizableMapPanel extends JPanel {

    public QuizableMapPanel(Set<Object> visited,
                            Set<Object> ancestors,
                            QuizableRenderContext renderContext,
                            List<String> path,
                            String fieldName,
                            Map<?, ?> map,
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

        for (Map.Entry<?, ?> entry : map.entrySet()) {

            JComponent key =
                    AnswerPanelFactory.TextUiUtils.createWrappedText(
                            String.valueOf(entry.getKey()));

            JComponent value =
                    QuizableValueRenderer.createFieldComponent(
                            visited,
                            ancestors,
                            renderContext,
                            fieldName,
                            path,
                            entry.getValue(),
                            config,
                            fill);

            if (value == null) {
                continue;
            }

            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.setOpaque(false);

            row.add(key, BorderLayout.WEST);
            row.add(value, BorderLayout.CENTER);

            values.add(row);

            added = true;
        }

        if (!added) {
            values.add(new JLabel("(empty)"));
        }

        add(values, BorderLayout.CENTER);
    }
}