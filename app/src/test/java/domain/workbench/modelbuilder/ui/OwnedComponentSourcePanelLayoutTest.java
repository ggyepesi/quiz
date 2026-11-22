package domain.workbench.modelbuilder.ui;

import org.junit.jupiter.api.Test;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;

import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedComponentSourcePanelLayoutTest {

    @Test void theMinimalOwnedClassEditorLaysOut() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.addClass(name);
        OwnedClassPanel panel = new OwnedClassPanel(project);
        panel.edit(name);

        JPanel host = new JPanel(new java.awt.BorderLayout());
        host.add(panel, java.awt.BorderLayout.CENTER);
        host.setSize(600, 400);
        layOut(panel);

        assertTrue(panel.getComponentCount() > 0);
    }

    private static void layOut(java.awt.Container root) {
        root.setSize(root.getWidth() == 0 ? 600 : root.getWidth(),
                root.getHeight() == 0 ? 400 : root.getHeight());
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof java.awt.Container container) layOut(container);
        }
    }
}
