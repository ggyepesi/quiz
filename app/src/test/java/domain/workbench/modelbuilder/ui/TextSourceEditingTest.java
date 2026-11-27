package domain.workbench.modelbuilder.ui;

import domain.config.ClassKind;
import domain.config.ConfigurationReferenceInventory;
import domain.config.FieldCardinality;
import domain.config.FieldSourceBindings;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.TextSource;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.schema.FieldType;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.workbench.ui.FieldDefinitionPanel;
import org.junit.jupiter.api.Test;
import wikidata.ui.WikidataLabels;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text sources are edited where the project's declarations are: a text source has its own
 * editor, and the story recipe chooses the project's text sources by name while storing
 * their declaration ids. Before this, the recipe's ids could only be typed, and the field
 * editor showed the recipe as "None".
 */
class TextSourceEditingTest {

    private static final String TEXT_PASSAGE_RECIPE =
            "One target instance per passage of the work's text sources";
    private static final String GYLFAGINNING =
            "The Prose Edda (1916 translation by Arthur Gilchrist Brodeur)/Gylfaginning";

    private static GeneratedProjectModel project(TextSource text) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel work = new GeneratedClassModel("Work");
        project.rootClass(work);
        project.addClass(new GeneratedClassModel("Character"));
        GeneratedClassModel story = new GeneratedClassModel("Story");
        story.classKind(ClassKind.SOURCE);
        project.addClass(story);
        story.addField("title", FieldType.TEXT, FieldCardinality.SINGLE);
        story.addField("text", FieldType.TEXT, FieldCardinality.SINGLE);
        story.addField("part", FieldType.TEXT, FieldCardinality.SINGLE);
        story.addField("work", FieldType.ENTITY, FieldCardinality.SINGLE).entityClassName("Work");
        story.addField("characters", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Character");
        work.addField("stories", FieldType.ENTITY, FieldCardinality.COLLECTION).entityClassName("Story");
        project.addTextSource(text);
        return project;
    }

    private static TextSource proseEdda() {
        TextSource text = new TextSource("Prose Edda (Brodeur, 1916)");
        text.workQid("Q205882");
        text.pages(List.of(GYLFAGINNING));
        return text;
    }

    @Test void aStoryRecipeChoosesTextSourcesByNameAndStoresTheirIds() {
        TextSource text = proseEdda();
        GeneratedProjectModel project = project(text);
        GeneratedFieldModel stories = project.findClass("Work").fields().getFirst();
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(stories);

        panel.addCandidateEvidenceRecipe(TEXT_PASSAGE_RECIPE);
        OrderedChoiceList<String> sources = panel.textPassageSources();
        JComboBox<?> available = find(sources, JComboBox.class);
        assertEquals("Prose Edda (Brodeur, 1916)", rendered(available, text.declarationId()),
                "the reader chooses a name, never an id");
        available.setSelectedItem(text.declarationId());
        button(sources, "Add").doClick();
        var boxes = panel.textPassageFieldBoxes();
        boxes.get(0).setSelectedItem("text");
        boxes.get(1).setSelectedItem("work");
        boxes.get(2).setSelectedItem("title");
        boxes.get(3).setSelectedItem("part");
        boxes.get(4).setSelectedItem("characters");
        button(panel, "Apply field source").doClick();

        var binding = FieldSourceBindings.bindings(stories, SourceBindingSlot.CANDIDATE_EVIDENCE).getFirst();
        var spec = WikipediaDatasourceProvider.textPassageInstances(binding);
        assertEquals(List.of(text.declarationId()), spec.textSources());
        assertEquals(List.of("text", "work", "title", "part", "characters"),
                List.of(spec.textField(), spec.sourceField(), spec.titleField(), spec.partField(),
                        spec.referencesField()));

        FieldSourcePanel reopened = new FieldSourcePanel();
        reopened.setProjectModel(project);
        reopened.edit(stories);
        assertEquals(List.of(text.declarationId()), reopened.textPassageSources().chosen());
        assertEquals("part", reopened.textPassageFieldBoxes().get(3).getSelectedItem());
        assertEquals("characters", reopened.textPassageFieldBoxes().get(4).getSelectedItem());
    }

    @Test void aTextSourceTheProjectLacksIsShownAsMissingRatherThanBlank() {
        TextSource text = proseEdda();
        GeneratedProjectModel project = project(text);
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(project.findClass("Work").fields().getFirst());
        panel.textPassageSources().show(List.of("decl:gone"), List.of());

        JList<?> chosen = find(panel.textPassageSources(), JList.class);
        assertEquals("decl:gone (not declared or imported here)", rendered(chosen, "decl:gone"));
    }

    @Test void theTextSourceEditorWritesItsDraftOnlyWhenItDiffers() {
        TextSource text = proseEdda();
        TextSourceEditorPanel editor = new TextSourceEditorPanel();
        editor.edit(project(text), text);
        assertFalse(editor.differs(), "showing a text source changes nothing");
        assertFalse(editor.applyEdits());

        find(editor, JTextArea.class).setText(GYLFAGINNING + "\n\n  Second page  \n");
        assertTrue(editor.differs());
        assertTrue(editor.applyEdits());
        assertEquals(List.of(GYLFAGINNING, "Second page"), text.pages());
    }

    @Test void anImportedTextSourceIsNeverWritten() {
        TextSource text = proseEdda();
        text.importedFrom("NorseTexts");
        TextSourceEditorPanel editor = new TextSourceEditorPanel();
        editor.edit(project(text), text);
        find(editor, JTextArea.class).setText("Other page");

        assertFalse(editor.applyEdits());
        assertEquals(List.of(GYLFAGINNING), text.pages());
    }

    @Test void renamingTheSelectedTextKeepsItsPageDraftUntilItIsApplied() {
        TextSource text = proseEdda();
        GeneratedProjectModel project = project(text);
        try (var workbench = new ModelSourceWorkbenchPanel(project)) {
            var tree = connectTree(project, workbench);
            tree.selectTextSource(text);
            var editor = find(workbench, TextSourceEditorPanel.class);
            var pages = find(editor, JTextArea.class);
            pages.setText("Draft page");

            assertTrue(project.renameTextSource(text.name(), "Brodeur's edition"));
            tree.refresh();
            tree.selectTextSource(text);

            assertSame(text, tree.selectedUserObject());
            assertEquals("Draft page", pages.getText());
            assertEquals(List.of(GYLFAGINNING), text.pages());
            assertTrue(workbench.hasUnappliedEdits());
            assertTrue(workbench.applyUnappliedEdits());
            assertEquals(List.of("Draft page"), text.pages());
        }
    }

    @Test void changingTheDraftTargetRefreshesDestinationsAndKeepsCompatibleEdits() {
        TextSource text = proseEdda();
        GeneratedProjectModel project = project(text);
        GeneratedClassModel otherStory = new GeneratedClassModel("OtherStory");
        otherStory.addField("body", FieldType.TEXT, FieldCardinality.SINGLE);
        otherStory.addField("work", FieldType.ENTITY, FieldCardinality.SINGLE).entityClassName("Work");
        project.addClass(otherStory);
        GeneratedFieldModel stories = project.findClass("Work").fields().getFirst();
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.targetClassCandidates(() -> List.of("Story", "OtherStory"));
        panel.edit(stories);
        panel.addCandidateEvidenceRecipe(TEXT_PASSAGE_RECIPE);
        chooseTextSource(panel, text);
        var destinations = panel.textPassageFieldBoxes();
        destinations.get(0).setSelectedItem("text");
        destinations.get(1).setSelectedItem("work");

        var target = find(panel, FieldDefinitionPanel.class).targetTypeBox();
        ((JTextField) target.getEditor().getEditorComponent()).setText("OtherStory");

        assertEquals("Story", stories.entityClassName(), "the target is still a draft");
        assertEquals(List.of(text.declarationId()), panel.textPassageSources().chosen());
        assertEquals("work", destinations.get(1).getSelectedItem());
        assertEquals("text (unavailable for this target)", rendered(destinations.getFirst(), "text"));
        assertEquals("body", destinations.getFirst().getItemAt(1));
        destinations.getFirst().setSelectedItem("body");
        button(panel, "Apply field source").doClick();

        assertEquals("OtherStory", stories.entityClassName());
        var binding = FieldSourceBindings.bindings(stories, SourceBindingSlot.CANDIDATE_EVIDENCE).getFirst();
        assertEquals("body", WikipediaDatasourceProvider.textPassageInstances(binding).textField());
    }

    @Test void aSourceUsedByLinkOpensTheSpecificRecipeOnItsField() {
        TextSource text = proseEdda();
        TextSource other = new TextSource("Another edition");
        GeneratedProjectModel project = project(text);
        project.addTextSource(other);
        var stories = project.findClass("Work").fields().getFirst();
        try (var workbench = new ModelSourceWorkbenchPanel(project)) {
            var tree = connectTree(project, workbench);
            tree.selectField(stories);
            var fieldPanel = find(workbench, FieldSourcePanel.class);
            for (TextSource source : List.of(other, text)) {
                fieldPanel.addCandidateEvidenceRecipe(TEXT_PASSAGE_RECIPE);
                chooseTextSource(fieldPanel, source);
                fieldPanel.textPassageFieldBoxes().get(0).setSelectedItem("text");
                fieldPanel.textPassageFieldBoxes().get(1).setSelectedItem("work");
            }
            button(fieldPanel, "Apply field source").doClick();
            tree.selectTextSource(text);
            var configuration = find(workbench, TextSourceEditorPanel.class).configurationTree();
            var usedBy = configuration.nodes().stream()
                    .filter(node -> node.caption().equals("Used by")).findFirst().orElseThrow();
            assertEquals(1, usedBy.children().size());
            var reference = usedBy.children().getFirst();
            assertTrue(reference.caption().contains("Work.stories"));
            configuration.select(reference.id());
            assertSame(text, workbench.selected(), "selecting a reference only inspects it");
            assertTrue(configuration.openReferenceButton().isEnabled());
            configuration.openReferenceButton().doClick();

            assertSame(stories, workbench.selected());
            assertEquals(reference.referenceTarget(),
                    fieldPanel.configurationTree().selectedNode().bindingSite().bindingSite());
            assertEquals(List.of(text.declarationId()), fieldPanel.textPassageSources().chosen());
            assertTrue(ConfigurationReferenceInventory.of(project)
                    .referencesToTextSource(text).stream()
                    .anyMatch(use -> use.source().equals(reference.referenceTarget())));
        }
    }

    @Test void theTextSourcesSectionShowsOnlyTheSelectionPrompt() {
        TextSource text = proseEdda();
        GeneratedProjectModel project = project(text);
        try (var workbench = new ModelSourceWorkbenchPanel(project)) {
            workbench.edit(text);
            var editor = find(workbench, TextSourceEditorPanel.class);
            assertTrue(editor.configurationTree().isVisible());

            workbench.changeSelection(SingleRootClassModelPanel.ConfigurationSection.TEXT_SOURCES);
            assertFalse(editor.configurationTree().isVisible());
            assertEquals("Select a text source.", find(editor, javax.swing.JLabel.class).getText());
            assertFalse(workbench.hasUnappliedEdits());
        }
    }

    @Test void theWorkUsesTheLabelledWikidataLinkAndTheSharedChangeAndSetActions() {
        TextSource text = proseEdda();
        text.workQid("Q999999991");
        GeneratedProjectModel project = project(text);
        WikidataLabels.seed(Map.of("Q999999991", "Prose Edda"));
        TextSourceEditorPanel editor = new TextSourceEditorPanel();
        editor.edit(project, text);
        var work = find(editor, WikidataSingleIdEditor.class);

        assertEquals("Prose Edda (Q999999991)", work.valueText().shownText());
        assertTrue(work.valueText().getText().contains("https://www.wikidata.org/wiki/Q999999991"));
        work.changeButton().doClick();
        work.draftField().setText("q205874");
        assertFalse(editor.differs(), "Change alone does not set a new QID");
        work.setButton().doClick();
        assertTrue(editor.differs());
        assertEquals("Q999999991", text.workQid());
        assertTrue(editor.applyEdits());
        assertEquals("Q205874", text.workQid());
        work.changeButton().doClick();
        work.draftField().setText("Q5");
        editor.edit(project, text);
        assertFalse(work.draftField().getParent().isVisible(), "loading a declaration closes the inline draft");
        assertEquals("Q205874", work.valueText().plainText());
    }

    private static void chooseTextSource(FieldSourcePanel panel, TextSource text) {
        var sources = panel.textPassageSources();
        find(sources, JComboBox.class).setSelectedItem(text.declarationId());
        button(sources, "Add").doClick();
    }

    private static SingleRootClassModelPanel connectTree(
            GeneratedProjectModel project, ModelSourceWorkbenchPanel workbench) {
        var tree = new SingleRootClassModelPanel(project, true);
        tree.addTreeSelectionListener(event -> {
            Object selection = tree.selectedUserObject();
            if (SingleRootClassModelPanel.isConfigurable(selection)) workbench.changeSelection(selection);
        });
        workbench.onConfigurationNavigation(tree::selectConstruct);
        workbench.afterChange(ignored -> tree.refresh());
        return tree;
    }

    private static String rendered(JComboBox<?> box, Object value) {
        @SuppressWarnings("unchecked") var renderer = (javax.swing.ListCellRenderer<Object>) box.getRenderer();
        return ((javax.swing.JLabel) renderer.getListCellRendererComponent(
                new JList<>(), value, 0, false, false)).getText();
    }

    private static String rendered(JList<?> list, Object value) {
        @SuppressWarnings("unchecked") var renderer = (javax.swing.ListCellRenderer<Object>) list.getCellRenderer();
        return ((javax.swing.JLabel) renderer.getListCellRendererComponent(
                new JList<>(), value, 0, false, false)).getText();
    }

    private static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button && text.equals(button.getText())) return button;
            if (child instanceof Container container) {
                try {
                    return button(container, text);
                } catch (AssertionError notHere) {
                    // keep looking
                }
            }
        }
        throw new AssertionError("no button " + text);
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                try {
                    return find(container, type);
                } catch (AssertionError notHere) {
                    // keep looking
                }
            }
        }
        throw new AssertionError("no " + type.getSimpleName());
    }
}
