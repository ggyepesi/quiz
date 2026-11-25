package domain.workbench.modelbuilder.ui;

import domain.config.ConfigurationNode;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.workbench.ui.TreeAndDetailPanel;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped forcing field uses the same projection and editor in ModelBuilder. */
class FieldConfigurationTreeTest {

    @Test void deityGroupMembersCanInspectItsTableRecipeFromTheLocalTree()
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/egyptiangods/egyptiangods.model.json"));
        GeneratedClassModel owner = project.findClass("DeityGroup");
        GeneratedFieldModel field = owner.fields().stream()
                .filter(value -> value.name().equals("members")).findFirst().orElseThrow();
        FieldSourcePanel[] shown = new FieldSourcePanel[1];

        SwingUtilities.invokeAndWait(() -> {
            FieldSourcePanel panel = new FieldSourcePanel();
            panel.setProjectModel(project);
            panel.edit(field);
            shown[0] = panel;
        });

        List<TreeAndDetailPanel.Item<ConfigurationNode.Id>> items =
                items(shown[0].configurationTree().tree());
        assertTrue(items.stream().anyMatch(item -> item.caption().startsWith(
                "Primary: Wikidata")));
        assertTrue(items.stream().anyMatch(item -> item.caption().equals(
                "Route: Listed members")));
        var table = items.stream().filter(item -> item.caption().contains(
                "Table rows")).findFirst().orElseThrow();
        var infobox = items.stream().filter(item -> item.caption().contains(
                "Infobox row links")).findFirst().orElseThrow();
        var section = items.stream().filter(item -> item.caption().startsWith(
                "Section: Description")).findFirst().orElseThrow();

        SwingUtilities.invokeAndWait(() -> {
            shown[0].configurationTree().select(infobox.id());
            shown[0].configurationTree().select(table.id());
            shown[0].configurationTree().select(section.id());
        });
        assertEquals("Description > List of names, provenances and tasks (Wilkinson et al)",
                shown[0].tableEvidenceSectionField().getText());
        assertEquals("Name of the deity", shown[0].tableEvidenceColumnField().getText());
    }

    /** Selecting "Produced by" reset an unapplied P9999 back to the stored P18: the
     *  node re-set the production-site combo, whose listener reloads every source
     *  control. One draft spans all of a field's nodes until Apply. */
    @Test void anUnappliedEditSurvivesSelectingAnyNode() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/egyptiangods/egyptiangods.model.json"));
        GeneratedFieldModel image = project.findClass("EgyptianDeity").fields().stream()
                .filter(value -> value.name().equals("image")).findFirst().orElseThrow();
        String stored = image.mapping().propertyPid();

        SwingUtilities.invokeAndWait(() -> {
            FieldSourcePanel panel = new FieldSourcePanel();
            panel.setProjectModel(project);
            panel.edit(image);
            panel.propertyPidField().setText("P9999");
            for (var item : items(panel.configurationTree().tree())) {
                panel.configurationTree().select(item.id());
                assertEquals("P9999", panel.propertyPidField().getText(),
                        "selecting " + item.caption() + " discarded the edit");
            }
            assertEquals(stored, image.mapping().propertyPid(), "selection never applies");
        });
    }

    /** Arrowing through "Uses: …" used to leave the field for that class. Selecting a
     *  reference only names it; Open (or Enter, or a double click) navigates. */
    @Test void aReferenceOpensOnlyOnAnExplicitOpen() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/egyptiangods/egyptiangods.model.json"));
        GeneratedClassModel owner = project.findClass("DeityGroup");
        GeneratedFieldModel field = owner.fields().stream()
                .filter(value -> value.name().equals("members")).findFirst().orElseThrow();
        List<Object> opened = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            FieldSourcePanel panel = new FieldSourcePanel();
            panel.setProjectModel(project);
            panel.onNavigateConfiguration(opened::add);
            panel.edit(field);
            var reference = items(panel.configurationTree().tree()).stream()
                    .filter(item -> item.caption().contains(": ")
                            && panel.configurationTree().contains(item.id()))
                    .filter(item -> item.id().key().startsWith("needs:"))
                    .findFirst().orElseThrow();
            panel.configurationTree().select(reference.id());
            assertTrue(opened.isEmpty(), "selecting a reference must not navigate");

            panel.openReferenceButton().doClick();
            assertEquals(1, opened.size());
        });
    }

    private static List<TreeAndDetailPanel.Item<ConfigurationNode.Id>> items(JTree tree) {
        List<TreeAndDetailPanel.Item<ConfigurationNode.Id>> result = new ArrayList<>();
        DefaultMutableTreeNode root =
                (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int index = 0; index < root.getChildCount(); index++) {
            collect((DefaultMutableTreeNode) root.getChildAt(index), result);
        }
        return result;
    }

    private static void collect(DefaultMutableTreeNode node,
            List<TreeAndDetailPanel.Item<ConfigurationNode.Id>> result) {
        @SuppressWarnings("unchecked")
        TreeAndDetailPanel.Item<ConfigurationNode.Id> item =
                (TreeAndDetailPanel.Item<ConfigurationNode.Id>) node.getUserObject();
        result.add(item);
        for (int index = 0; index < node.getChildCount(); index++) {
            collect((DefaultMutableTreeNode) node.getChildAt(index), result);
        }
    }
}
