package wikidata.explore.tree;

import quiz.Quizable;
import quiz.ui.QuizablePanelView;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Right panel: generated instances preview.
 *
 * Uses QuizablePanelView so all generated top-level objects share one
 * QuizableRenderContext. This is the rendering path that handles repeated
 * references/cycles correctly.
 */
public class GeneratedInstancesPanel extends JPanel {

    private final JPanel emptyPanel =
            new JPanel(new BorderLayout());

    public GeneratedInstancesPanel() {
        super(new BorderLayout());
        emptyPanel.add(new JLabel("No generated instances loaded."),
                BorderLayout.CENTER);
        add(emptyPanel, BorderLayout.CENTER);
    }

    public void setGeneratedObjects(
            List<? extends Quizable> objects,
            Class<?> generatedClass) {
        System.out.println(getClass().getName() + ".setGeneratedObjects...");
        removeAll();

        if (objects == null || objects.isEmpty()) {
            add(emptyPanel, BorderLayout.CENTER);
        } else {
            QuizablePanelView view =
                    new QuizablePanelView();

            for (Quizable q : objects) {
                view.addQuizable(q);
            }

            view.createCardsPanel(1);

            JComponent component =
                    view.getCardsScrollPane();

            if (component == null) {
                component = new JLabel("No generated cards created.");
            }

            add(component, BorderLayout.CENTER);
        }

        revalidate();
        repaint();
    }

    public void setObjects(List<WikidataDynamicObject> objects) {
        removeAll();

        JTextArea area =
                new JTextArea();

        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);

        if (objects == null || objects.isEmpty()) {
            area.setText("No generated instances loaded.");
        } else {
            StringBuilder sb =
                    new StringBuilder();

            for (WikidataDynamicObject obj : objects) {
                sb.append(obj).append("\n\n");
            }

            area.setText(sb.toString());
        }

        add(new JScrollPane(area), BorderLayout.CENTER);
        revalidate();
        repaint();
    }
}
