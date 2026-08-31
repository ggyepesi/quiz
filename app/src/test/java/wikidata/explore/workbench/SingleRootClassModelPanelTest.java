package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;
import wikidata.explore.model.StatementClassSource;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleRootClassModelPanelTest {

    @Test void onlyModelLeavesOpenConfigurationEditors() {
        assertTrue(SingleRootClassModelPanel.isConfigurable(new GeneratedProjectModel()));
        assertTrue(SingleRootClassModelPanel.isConfigurable(
                SingleRootClassModelPanel.ConfigurationSection.VOCABULARIES));
        assertTrue(SingleRootClassModelPanel.isConfigurable(
                new GeneratedClassModel("NobelPrize")));
        assertTrue(SingleRootClassModelPanel.isConfigurable(
                new VocabularySelection("Categories")));
    }

    @Test void configurationTreeNamesTheSharedValueDomainsConsistently() {
        GeneratedProjectModel project = modelWithEdition();
        project.addSelection(new VocabularySelection("Category"));

        SingleRootClassModelPanel panel = new SingleRootClassModelPanel(project);
        JTree tree = find(panel, JTree.class);
        DefaultMutableTreeNode root =
                (DefaultMutableTreeNode) tree.getModel().getRoot();

        assertEquals(SingleRootClassModelPanel.ConfigurationSection.VOCABULARIES,
                ((DefaultMutableTreeNode) root.getLastChild()).getUserObject());
    }

    @Test void aVocabularySelectionSurvivesTreeRefreshAndGetsContextualActions() {
        GeneratedProjectModel project = modelWithEdition();
        VocabularySelection categories = new VocabularySelection("Categories");
        project.addSelection(categories);
        SingleRootClassModelPanel panel = new SingleRootClassModelPanel(project);

        panel.selectSelection(categories);
        panel.refresh();

        assertSame(categories, panel.selectedUserObject());
        assertTrue(button(panel, "Rename vocabulary").isEnabled());
        assertTrue(button(panel, "Add vocabulary").isEnabled());
        assertFalse(button(panel, "Add field").isEnabled());
        assertTrue(button(panel, "Remove").isEnabled());

        panel.selectClass(project.rootClass());
        assertTrue(button(panel, "Rename class").isEnabled());
        assertTrue(button(panel, "Add class").isEnabled());
    }

    private static GeneratedProjectModel modelWithEdition() {
        GeneratedProjectModel p = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("edition", FieldType.ENTITY, FieldCardinality.SINGLE);
        p.addClass(nom);
        p.rootClass(nom);
        return p;
    }

    // Regression: after copyContentsFrom (a load/reload) swaps in FRESH model
    // objects, refresh() must re-bind the selection to the LIVE new field — not
    // silently fail on the old identity and leave the editor on an orphan (whose
    // edits are then lost on save). This is the persistence bug behind edition's
    // Subject-default/Required not sticking.
    @Test void refreshRebindsSelectionToLiveObjectAfterModelSwap() {
        GeneratedProjectModel project = modelWithEdition();
        SingleRootClassModelPanel panel = new SingleRootClassModelPanel(project);

        GeneratedFieldModel oldEdition = project.rootClass().fields().get(0);
        panel.selectField(oldEdition);
        assertSame(oldEdition, panel.selectedUserObject());

        // A fresh model with the SAME names but NEW object instances (what load does).
        GeneratedProjectModel reloaded = modelWithEdition();
        GeneratedFieldModel newEdition = reloaded.rootClass().fields().get(0);
        assertNotNull(newEdition);

        project.copyContentsFrom(reloaded);   // project now holds the NEW objects
        panel.refresh();

        Object nowSelected = panel.selectedUserObject();
        assertSame(newEdition, nowSelected,
                "editor must re-bind to the live (new) edition field, not the orphan");
    }

    @Test void aTreeRefreshCannotUndoAClassRenameThroughAStaleEditor() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel prize = new GeneratedClassModel("NobelPrizes");
        prize.statementSource(new StatementClassSource("P166"));
        project.rootClass(prize);

        SingleRootClassModelPanel tree = new SingleRootClassModelPanel(project);
        ModelSourceWorkbenchPanel editor = new ModelSourceWorkbenchPanel(project);
        editor.edit(prize); // editor still displays NobelPrizes
        tree.addTreeSelectionListener(event ->
                editor.changeSelection(tree.selectedUserObject()));

        project.renameClass("NobelPrizes", "NobelPrize");
        tree.refresh();
        editor.applyEdits();

        assertEquals("NobelPrize", prize.className(),
                "a transient empty tree selection must not reapply the old editor name");
    }

    private static <T extends java.awt.Component> T find(
            java.awt.Container root, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof java.awt.Container container) {
                T found = findOrNull(container, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No " + type.getSimpleName());
    }

    private static <T extends java.awt.Component> T findOrNull(
            java.awt.Container root, Class<T> type) {
        try { return find(root, type); }
        catch (AssertionError ignored) { return null; }
    }

    private static JButton button(java.awt.Container root, String text) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof java.awt.Container container) {
                try { return button(container, text); }
                catch (AssertionError ignored) { }
            }
        }
        throw new AssertionError("No button " + text);
    }

    /**
     * The tree's root row spelled "Domain:" whatever the project was, so a model created
     * through New… was labelled as the kind the dialog had just been used to reject. The
     * kind names itself; nothing else should spell it.
     */
    @Test void theTreeRootNamesTheProjectKindItActuallyIs() {
        for (GeneratedProjectModel.ProjectKind kind
                : GeneratedProjectModel.ProjectKind.values()) {
            GeneratedProjectModel project = new GeneratedProjectModel();
            project.name("People");
            project.projectKind(kind);
            project.rootClass(new GeneratedClassModel("Person"));

            SingleRootClassModelPanel panel = new SingleRootClassModelPanel(project);
            JTree tree = find(panel, JTree.class);
            DefaultMutableTreeNode root =
                    (DefaultMutableTreeNode) tree.getModel().getRoot();
            java.awt.Component rendered = tree.getCellRenderer()
                    .getTreeCellRendererComponent(tree, root, false, false, false, 0, false);

            assertEquals(kind + ": People", ((javax.swing.JLabel) rendered).getText());
        }
    }

    /**
     * An adopted class is owned by the model it came from, so this project cannot add
     * to or remove from its fields. The class itself stays removable — dropping an
     * adoption is the adopting project's decision, not the origin's.
     */
    @Test void theFieldActionsOfAnAdoptedClassAreLocked() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("Nobel");
        project.rootClass(new GeneratedClassModel("Prize"));

        GeneratedClassModel adopted = new GeneratedClassModel("Name");
        adopted.originModel("People");
        GeneratedFieldModel adoptedField = adopted.addField(
                "givenName", FieldType.STRING, FieldCardinality.SINGLE);
        project.addClass(adopted);

        GeneratedClassModel mine = new GeneratedClassModel("Ceremony");
        GeneratedFieldModel myField = mine.addField(
                "year", FieldType.STRING, FieldCardinality.SINGLE);
        project.addClass(mine);

        SingleRootClassModelPanel panel = new SingleRootClassModelPanel(project);
        JButton addField = button(panel, "Add field");
        JButton remove = button(panel, "Remove");

        panel.selectClass(adopted);
        assertFalse(addField.isEnabled(), "cannot add a field to an adopted class");
        assertTrue(remove.isEnabled(), "but the adoption itself can be dropped");

        panel.selectField(adoptedField);
        assertFalse(remove.isEnabled(), "cannot remove an adopted class's field");

        panel.selectClass(mine);
        assertTrue(addField.isEnabled(), "a class authored here is unaffected");
        panel.selectField(myField);
        assertTrue(remove.isEnabled());
    }

    /** The tree says where an adopted class came from; a local class shows no origin. */
    @Test void theTreeShowsWhereAnAdoptedClassCameFrom() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Prize"));
        GeneratedClassModel adopted = new GeneratedClassModel("Name");
        adopted.originModel("People");
        project.addClass(adopted);

        SingleRootClassModelPanel panel = new SingleRootClassModelPanel(project);
        assertTrue(renderedTextOf(panel, adopted).contains("People"),
                "the origin is visible on the class row");
        assertFalse(renderedTextOf(panel, project.rootClass()).contains("\u2190"),
                "a class authored here carries no origin marker");
    }

    private static String renderedTextOf(
            SingleRootClassModelPanel panel, Object userObject) {
        JTree tree = find(panel, JTree.class);
        DefaultMutableTreeNode node = nodeFor(
                (DefaultMutableTreeNode) tree.getModel().getRoot(), userObject);
        assertNotNull(node, "node for " + userObject);
        return ((JLabel) tree.getCellRenderer().getTreeCellRendererComponent(
                tree, node, false, true, false, 0, false)).getText();
    }

    private static DefaultMutableTreeNode nodeFor(
            DefaultMutableTreeNode from, Object userObject) {
        if (from.getUserObject() == userObject) return from;
        for (int i = 0; i < from.getChildCount(); i++) {
            DefaultMutableTreeNode hit = nodeFor(
                    (DefaultMutableTreeNode) from.getChildAt(i), userObject);
            if (hit != null) return hit;
        }
        return null;
    }
}
