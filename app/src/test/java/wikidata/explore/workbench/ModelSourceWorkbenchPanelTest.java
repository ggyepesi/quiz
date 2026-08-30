package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.VocabularySelection;

import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelSourceWorkbenchPanelTest {

    @Test void theDomainNodeHasAnOverviewRatherThanAStaleClassEditor() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("NobelPrizes");
        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(model);

        panel.edit(model);

        assertSame(model, panel.selected());
        DomainOverviewPanel overview = component(panel, DomainOverviewPanel.class);
        assertTrue(overview.isVisible());
    }

    @Test void aVocabularyUsesTheConfigurationAreaRatherThanAnExternalWindow() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        VocabularySelection categories = new VocabularySelection("Categories");
        model.addSelection(categories);
        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(model);
        SelectionViewerPanel editor = new SelectionViewerPanel(model, null, null);
        panel.selectionEditor(editor);

        panel.edit(categories);

        assertSame(categories, panel.selected());
        assertTrue(editor.isVisible(), "the vocabulary editor is the visible config card");
        assertEquals(0, buttonsNamed(editor, "New", "Rename", "Delete"),
                "structural vocabulary actions belong to the model tree");
    }

    private static int buttonsNamed(Container root, String... names) {
        java.util.Set<String> wanted = java.util.Set.of(names);
        int count = 0;
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JButton button
                    && wanted.contains(button.getText())) count++;
            if (child instanceof Container container) count += buttonsNamed(container, names);
        }
        return count;
    }

    @Test void explorerToolsAreGroupedByDatasourceAndProgrammaticNavigationStillWorks() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel root = new GeneratedClassModel("Root");
        model.addClass(root);
        model.rootClass(root);
        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(model);

        JTabbedPane sources = (JTabbedPane) panel.helperTools();
        assertEquals(2, sources.getTabCount());
        assertEquals("Wikidata", sources.getTitleAt(0));
        assertEquals("Wikipedia", sources.getTitleAt(1));

        panel.showProperties();
        assertEquals("Wikidata", sources.getTitleAt(sources.getSelectedIndex()));
        JTabbedPane wikidata = (JTabbedPane) sources.getSelectedComponent();
        assertEquals("Properties", wikidata.getTitleAt(wikidata.getSelectedIndex()));
        int entityRelations = wikidata.indexOfTab("Entity relations");
        assertTrue(entityRelations >= 0, "Entity relations tab is present");
        assertInstanceOf(EntityRelationDiscoveryPanel.class,
                wikidata.getComponentAt(entityRelations));
    }

    @Test void editsSurviveNavigatingToAnotherModelNode() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        holding.addField("position", FieldType.ENTITY, FieldCardinality.SINGLE);
        model.addClass(holding);
        model.rootClass(holding);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        model.addClass(person);

        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(model);
        panel.edit(holding);
        comboContaining(panel, "position").setSelectedItem("position");
        panel.changeSelection(person);

        assertEquals(CanonicalSpec.DisplayNameMode.FIELD,
                holding.canonical().displayNameMode());
        assertEquals("position", holding.canonical().displayNameField());
    }

    @Test void modelRefreshDuringCommitDoesNotReenterSelectionChange() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        holding.addField("position", FieldType.ENTITY, FieldCardinality.SINGLE);
        model.addClass(holding);
        model.rootClass(holding);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        model.addClass(person);

        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(model);
        panel.edit(holding);
        AtomicInteger refreshes = new AtomicInteger();
        panel.afterChange(ignored -> {
            refreshes.incrementAndGet();
            // Mirrors ModelBuilderFrame.modelChanged(): refreshing the tree fires its
            // selection listener while the previous editor is still being committed.
            panel.changeSelection(person);
        });

        panel.changeSelection(person);

        assertEquals(1, refreshes.get());
    }

    @Test void confirmedDomainReplacementDoesNotCommitTheOldEditor() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel oldClass = new GeneratedClassModel("OldDomainClass");
        model.rootClass(oldClass);
        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(model);
        panel.edit(oldClass);
        AtomicInteger commits = new AtomicInteger();
        panel.afterChange(ignored -> commits.incrementAndGet());

        panel.abandonEdits();
        GeneratedProjectModel replacement = new GeneratedProjectModel();
        GeneratedClassModel mythology = new GeneratedClassModel("Mythology");
        replacement.rootClass(mythology);
        replacement.addSelection(new VocabularySelection("Categories"));
        model.copyContentsFrom(replacement);
        panel.changeSelection(model.rootClass());

        assertEquals(0, commits.get(),
                "a tree event after replacement must not flush the old domain editor");
        assertSame(mythology, panel.selected());
    }

    @Test void removingTheSelectedClassDoesNotCommitItsOrphanedEditor() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel root = new GeneratedClassModel("Root");
        GeneratedClassModel laureate = new GeneratedClassModel("Laureate");
        model.rootClass(root);
        model.addClass(laureate);
        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(model);
        panel.edit(laureate);
        AtomicInteger commits = new AtomicInteger();
        panel.afterChange(ignored -> commits.incrementAndGet());

        model.removeClass(laureate);
        panel.changeSelection(root);

        assertEquals(0, commits.get(),
                "tree navigation after remove must not apply the removed class");
        assertSame(root, panel.selected());
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> comboContaining(Container root, String item) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComboBox<?> combo) {
                for (int i = 0; i < combo.getItemCount(); i++) {
                    if (item.equals(combo.getItemAt(i))) {
                        return (JComboBox<String>) combo;
                    }
                }
            }
            if (component instanceof Container child) {
                try {
                    return comboContaining(child, item);
                } catch (AssertionError ignored) {
                    // Continue with the next branch of the component tree.
                }
            }
        }
        throw new AssertionError("No combo contains " + item);
    }

    private static <T extends Component> T component(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                try { return component(container, type); }
                catch (AssertionError ignored) { }
            }
        }
        throw new AssertionError("No " + type.getSimpleName());
    }

    @Test void aFieldSampleUsesItsDeclaringClassRatherThanTheProjectRoot() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel history = new GeneratedClassModel("History");
        model.addClass(history);
        model.rootClass(history);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().sourceQid("Q5");
        var spouse = person.addField(
                "spouse", FieldType.ENTITY, FieldCardinality.AUTO);
        spouse.mapping().propertyPid("P26");
        model.addClass(person);

        assertEquals(person,
                ModelSourceWorkbenchPanel.fieldSampleContext(model, spouse).ownerClass());
        assertEquals("Q5",
                ModelSourceWorkbenchPanel.fieldSampleContext(model, spouse).ownerTypeQid());
    }

    @Test void aFieldSampleUsesAnEvidenceDerivedKindAsItsPopulation() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        var type = person.addField("type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        type.mapping().propertyPid("P31");
        model.addClass(person);
        model.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));

        assertEquals("Q5",
                ModelSourceWorkbenchPanel.fieldSampleContext(model, type).ownerTypeQid());
    }
}
