package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constraints built from the wrong {@code GridBagUtils.gbc} overload compile and
 * construct happily — a gridwidth passed where the shorter overload expects an anchor
 * only fails when the panel is LAID OUT, as "illegal anchor value" on the EDT. So the
 * panel is laid out here, which is the only place that check can run.
 */
class OwnedComponentSourcePanelLayoutTest {

    @Test void theEditorLaysOutItsForm() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel site = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("Name");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        project.rootClass(person);
        project.addClass(new GeneratedClassModel("Name"));

        OwnedComponentSourcePanel panel = new OwnedComponentSourcePanel(project);
        panel.edit(project.findClass("Name"));

        JPanel host = new JPanel(new java.awt.BorderLayout());
        host.add(panel, java.awt.BorderLayout.CENTER);
        host.setSize(600, 400);
        host.doLayout();
        layOut(panel);

        assertTrue(panel.getComponentCount() > 0, "the editor built its form");
    }

    /** Lays out every container below {@code root}, which is what raises an illegal
     *  constraint — {@code doLayout} on the parent alone would not reach the form. */
    private static void layOut(java.awt.Container root) {
        root.setSize(root.getWidth() == 0 ? 600 : root.getWidth(),
                root.getHeight() == 0 ? 400 : root.getHeight());
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof java.awt.Container container) layOut(container);
        }
    }
}
