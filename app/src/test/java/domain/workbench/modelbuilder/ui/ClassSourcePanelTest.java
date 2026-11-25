package domain.workbench.modelbuilder.ui;

import domain.config.ClassConfigurationProjection;

import domain.datasource.graph.GraphExpansionPolicy;
import org.junit.jupiter.api.Test;
import domain.config.GeneratedClassModel;
import domain.config.ClassSourceBindings;
import domain.config.StatementClassSource;
import domain.config.GeneratedProjectModel;
import domain.build.DomainSave;
import domain.datasource.api.SourceBindingSlot;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The Class editor shows the "Reify from" source class and nothing else about a
// statement source. Everything it cannot see must survive its apply, because the
// Statement editor is the single editor of those declarations.
class ClassSourcePanelTest {

    /** Where a class's instances come from is one choice. It used to be the Wikidata
     * triple plus a separate "Alternative population source" row: both looked configured,
     * and a membership written later by another tool silently won over the category
     * the editor still showed. */
    @Test void aWikipediaCategoryIsChosenAsThePopulationSourceInsteadOfTheTriple() {
        GeneratedClassModel group = new GeneratedClassModel("DeityGroup");
        ClassSourcePanel panel = panelFor(group);
        panel.edit(group);
        JComboBox<?> source = named(panel, "class.populationSource", JComboBox.class);
        TripleEditor triple = find(panel, TripleEditor.class);
        domain.workbench.ui.DatasourceOperationForm form = named(panel,
                "class.alternativePopulationSource",
                domain.workbench.ui.DatasourceOperationForm.class);

        assertEquals(0, source.getSelectedIndex(), "a new class is populated from Wikidata");
        assertTrue(triple.isVisible());
        assertFalse(form.isVisible(), "no second population control is shown beside it");

        selectCategoryMembers(source);
        assertFalse(triple.isVisible(), "the triple is not shown for a category population");
        assertTrue(form.isVisible());
        named(form, "domain.datasource.parameter.category", javax.swing.JTextField.class)
                .setText("Category:Groups of Egyptian deities");
        named(form, "domain.datasource.parameter.limit", javax.swing.JTextField.class)
                .setText("100");
        panel.applyEdits();

        assertEquals("category-members", group.populationSource().operationId());
        assertEquals("Category:Groups of Egyptian deities",
                group.populationSource().parameter("category"));
        assertFalse(group.membership().bounded());
        assertTrue(group.seedQids().isEmpty());

        panel.edit(group);
        assertTrue(source.getSelectedItem() instanceof domain.workbench.ui.DatasourceOperationForm.Choice,
                "reopening the class shows the chosen source, not the triple");
        assertEquals("Category:Groups of Egyptian deities",
                named(form, "domain.datasource.parameter.category", javax.swing.JTextField.class)
                        .getText());

        source.setSelectedIndex(0);
        panel.applyEdits();
        assertNull(ClassSourceBindings.alternativePopulationSource(group),
                "choosing the Wikidata triple again removes the category population");
    }

    /** Apply discards the Wikidata membership a category replaces, so the panel says
     * so before Apply instead of only in a tooltip; a class without one gets no note. */
    @Test void choosingACategoryNamesTheWikidataMembershipItWillReplace() {
        GeneratedClassModel group = new GeneratedClassModel("DeityGroup");
        group.membership(domain.config.EntityBound.relation(
                "P31", java.util.List.of("Q20830276"), true));
        ClassSourcePanel panel = panelFor(group);
        panel.edit(group);
        JComboBox<?> source = named(panel, "class.populationSource", JComboBox.class);
        javax.swing.JLabel note = named(panel, "class.populationSource.replaces",
                javax.swing.JLabel.class);
        assertFalse(note.isVisible(), "nothing is replaced while the triple is chosen");

        selectCategoryMembers(source);
        assertTrue(note.isVisible());
        assertTrue(note.getText().contains("replaces its Wikidata membership"),
                note.getText());
    }

    @Test void aPropertyWithoutObjectsIsNotAMembershipToReplace() {
        GeneratedClassModel group = new GeneratedClassModel("DeityGroup");
        ClassSourcePanel panel = panelFor(group);
        panel.edit(group);
        find(panel, TripleEditor.class).propertyPid("P279", "");

        selectCategoryMembers(named(
                panel, "class.populationSource", JComboBox.class));

        assertFalse(named(panel, "class.populationSource.replaces", JLabel.class)
                .isVisible(), "a property alone is stored unbounded, so nothing is replaced");
    }

    @Test void replacementWarningReadsTheVisibleDraftNotOnlyTheStoredClass() {
        GeneratedClassModel group = new GeneratedClassModel("DeityGroup");
        ClassSourcePanel panel = panelFor(group);
        panel.edit(group);
        TripleEditor triple = find(panel, TripleEditor.class);
        triple.objectQids(List.of("Q20830276"));
        TextBackedEditor seeds = (TextBackedEditor) named(
                panel, "class.seedQids", javax.swing.JComponent.class);
        seeds.storage().setText("Q233458 Q188236");

        selectCategoryMembers(named(
                panel, "class.populationSource", JComboBox.class));

        JLabel note = named(panel, "class.populationSource.replaces", JLabel.class);
        assertTrue(note.isVisible());
        assertTrue(note.getText().contains(
                "Wikidata membership and 2 seed QID(s)"), note.getText());
        assertFalse(group.membership().bounded(),
                "inspection of the draft must not mutate the stored model");
        assertTrue(group.seedQids().isEmpty());
    }

    /** The other side: a Wikidata membership written while a category is chosen does
     * not become a second, silently winning answer. */
    @Test void aChosenDatasourceRemainsThePopulationWhateverMembershipIsStored() {
        GeneratedClassModel group = new GeneratedClassModel("DeityGroup");
        domain.datasource.api.SourceRecipe category = new domain.datasource.api.SourceRecipe(
                "wikipedia", "category-members",
                java.util.Map.of("category", "Category:Groups of Egyptian deities",
                        "limit", "100"));
        ClassSourceBindings.alternativePopulationSource(group, category);
        group.membership(domain.config.EntityBound.relation(
                "P31", List.of("Q20830276"), false));

        assertEquals(category, group.populationSource(),
                "generation reads the chosen datasource, the same answer the editor shows");
    }

    private static void selectCategoryMembers(JComboBox<?> source) {
        for (int index = 0; index < source.getItemCount(); index++) {
            Object item = source.getItemAt(index);
            if (item instanceof domain.workbench.ui.DatasourceOperationForm.Choice choice
                    && "category-members".equals(choice.operation().id())) {
                source.setSelectedIndex(index);
                return;
            }
        }
        throw new AssertionError("category-members is not offered as a population source");
    }

    @Test void groupSemanticsAreAnExplicitPersistedClassChoice() {
        GeneratedClassModel group = new GeneratedClassModel("DeityGroup");
        group.addField("members", domain.datasource.schema.FieldType.ENTITY,
                domain.config.FieldCardinality.COLLECTION)
                .entityClassName("Deity");
        ClassSourcePanel panel = panelFor(group);
        panel.edit(group);
        JComboBox<?> choice = named(panel, "class.groupMembersField", JComboBox.class);

        assertEquals("(not a group)", choice.getSelectedItem());
        choice.setSelectedItem("members");
        panel.applyEdits();

        assertEquals("members", group.groupSpec().membersField());
        panel.edit(group);
        assertEquals("members", choice.getSelectedItem());
    }

    @Test void inspectingASubclassDoesNotInvalidateSavedInstances() {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        GeneratedClassModel withHolders = new GeneratedClassModel("PositionWithHolders");
        withHolders.baseClassName("Position");
        withHolders.clearIndependentPopulation();
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(position);
        project.addClass(withHolders);
        String before = DomainSave.signature(project);

        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(withHolders);
        panel.applyEdits();

        assertEquals(before, DomainSave.signature(project),
                "selecting a class and then Vocabularies is navigation, not a model edit");
    }

    @Test void inspectingAliasesDoesNotOptIntoTheOptionalField() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        ClassSourcePanel panel = panelFor(person);
        panel.edit(person);

        JCheckBox aliases = checkBox(panel, "Add aliases (Also known as)");
        assertNotNull(aliases, "the automatic datasource field must be visible in config");
        assertFalse(aliases.isSelected(),
                "a new class must not silently opt into an optional field");
        panel.applyEdits();

        assertNull(ClassSourceBindings.binding(
                person, SourceBindingSlot.CLASS_ALIASES));
    }

    @Test void selectingAliasesExplicitlyAddsTheOptionalField() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        ClassSourcePanel panel = panelFor(person);
        panel.edit(person);

        checkBox(panel, "Add aliases (Also known as)").setSelected(true);
        panel.applyEdits();

        assertNotNull(ClassSourceBindings.binding(person, SourceBindingSlot.CLASS_ALIASES));
    }

    @Test void wikipediaSourceLinkIsAnExplicitPersistedClassChoice() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        ClassSourcePanel panel = panelFor(person);
        panel.edit(person);

        JCheckBox wikipedia = checkBox(panel, "Add English Wikipedia source link");
        assertNotNull(wikipedia);
        assertFalse(wikipedia.isSelected());
        wikipedia.setSelected(true);
        panel.applyEdits();

        assertNotNull(ClassSourceBindings.binding(
                person, SourceBindingSlot.ARTICLE_CORRESPONDENCE));
        panel.edit(person);
        assertTrue(checkBox(panel, "Add English Wikipedia source link").isSelected());
    }

    @Test void applyingAPopulationNeverDeclaresInstanceFields() {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(domain.config.EntityBound.relation(
                "P279", List.of("Q12097"), false));
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);

        panel.applyEdits();

        assertTrue(position.fields().isEmpty(),
                "P31/type and P279/target are field choices, not population side effects");
    }

    @Test void applyingExplicitSubjectQidsRefreshesTheReadinessIndicator() throws IOException {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);
        WikidataIdListEditor qids = findQidListBackedByTextArea(panel);
        qids.draftField().setText("Q4164871");
        qids.addButton().doClick();

        panel.applyEdits();

        assertTrue(labels(panel).stream().anyMatch(text -> text.contains(
                "Ready — 1 explicit QID")), labels(panel).toString());
        assertTrue(panel.configurationEditor().tree().selectDetail(ClassConfigurationProjection.PRODUCTION));
        render(panel, "target/ui-artifacts/class-source-ready-after-apply.png");
    }

    @Test void aPropertyWithoutAnObjectIsVisiblyAnUnappliedDraft() throws IOException {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);
        TripleEditor triple = find(panel, TripleEditor.class);
        triple.propertyPid("P279", "subclass of");

        panel.applyEdits();

        assertFalse(position.membership().bounded());
        assertEquals("P279", triple.propertyPid(), "the draft stays available to finish");
        assertTrue(labels(panel).stream().anyMatch(text -> text.contains(
                "property P279 was not saved — add an object")), labels(panel).toString());
        assertTrue(panel.configurationEditor().tree().selectDetail(ClassConfigurationProjection.PRODUCTION));
        render(panel, "target/ui-artifacts/class-source-unapplied-property.png");
    }

    @Test void descendantMembershipIsAVisibleSourceConfigurationChoice()
            throws IOException {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(domain.config.EntityBound.relation(
                "P31", List.of("Q4164871"), true));
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);

        assertTrue(panel.configurationEditor().tree().selectDetail(ClassConfigurationProjection.PRODUCTION));
        JCheckBox descendants = visibleCheckBox(
                panel, "include subclasses of these QIDs (P279*)");
        assertNotNull(descendants);
        assertTrue(descendants.isVisible());
        assertTrue(descendants.isSelected());

        render(panel, "target/ui-artifacts/class-source-descendant-membership.png");
    }

    @Test void applyingTwiceKeepsTheSubclassClosureTheReaderChose() {
        // Apply re-shows the object QIDs it has just written, and an explicit bound
        // has nowhere to carry the closure — so the redisplay cleared the checkbox
        // and the next Apply stored the cleared value. A saved position model came
        // back with includeDescendants false for a class configured with it on.
        GeneratedClassModel position = new GeneratedClassModel("PositionKind");
        position.membership(domain.config.EntityBound.relation(
                "P279", List.of("Q4164871"), true));
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);

        panel.applyEdits();
        assertTrue(position.membership().includeDescendants(),
                "the first apply stores what the reader configured");
        assertTrue(visibleCheckBox(panel,
                        "include subclasses of these QIDs (P279*)").isSelected(),
                "and leaves the control showing it");

        panel.applyEdits();
        assertTrue(position.membership().includeDescendants(),
                "a second apply must not store a choice nobody made");
        assertEquals(List.of("Q4164871"), position.membership().qids());
        assertEquals("P279", position.membership().relationPid());
    }

    @Test void theInheritedPopulationFilterExistsOnlyWhenAClassExtendsAnother() {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        ClassSourcePanel rootPanel = panelFor(position);
        rootPanel.edit(position);
        assertNull(visibleLabel(rootPanel, "Inherited population filter:"),
                "a root class has no inherited population to filter");

        GeneratedClassModel king = new GeneratedClassModel("King");
        king.baseClassName("Position");
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(position);
        project.addClass(king);
        ClassSourcePanel subclassPanel = new ClassSourcePanel();
        subclassPanel.setProjectModel(project);
        subclassPanel.edit(king);

        assertNotNull(visibleLabel(subclassPanel, "Inherited population filter:"));
    }

    @Test void applyEditsKeepsDeclarationsThisEditorCannotSee() {
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource("Person", "P39");
        source.valueSelectionName("Positions");
        source.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        holding.statementSource(source);

        ClassSourcePanel panel = panelFor(holding);
        panel.edit(holding);
        panel.applyEdits();

        assertNotNull(holding.statementSource());
        assertEquals("Positions", holding.statementSource().valueSelectionName(),
                "the value Selection is not editable here and must survive");
        assertEquals(GraphExpansionPolicy.CURATED,
                holding.statementSource().graphExpansionPolicy(),
                "the graph policy is not editable here and must survive");
    }

    // Regression: every statement class in the shipped models (History OfficeHolding,
    // Oscars Nomination) discovers its subjects from the property and so has a BLANK
    // source class. Applying the Class editor to one must not null its statement
    // source — a set property alone is enough to make it a statement class.
    @Test void applyEditsKeepsASourceClasslessStatementClass() {
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource("P39");
        source.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        holding.statementSource(source);

        ClassSourcePanel panel = panelFor(holding);
        panel.edit(holding);
        panel.applyEdits();

        assertNotNull(holding.statementSource(),
                "a discovered-subject statement class must survive the Class editor");
        assertEquals("P39", holding.statementSource().propertyPid());
        assertEquals(GraphExpansionPolicy.CURATED,
                holding.statementSource().graphExpansionPolicy());
    }

    private static JCheckBox checkBox(Component component, String text) {
        if (component instanceof JCheckBox box && text.equals(box.getText())) return box;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JCheckBox found = checkBox(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JCheckBox visibleCheckBox(Component component, String text) {
        if (component instanceof JCheckBox box && box.isVisible()
                && text.equals(box.getText())) return box;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JCheckBox found = visibleCheckBox(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel visibleLabel(Component component, String text) {
        if (component instanceof JLabel label && label.isVisible()
                && text.equals(label.getText())) return label;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = visibleLabel(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = findOrNull(child, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No " + type.getSimpleName());
    }

    private static <T extends Component> T named(
            Component component, String name, Class<T> type) {
        if (type.isInstance(component) && name.equals(component.getName())) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = namedOrNull(child, name, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " named " + name);
    }

    private static <T extends Component> T namedOrNull(
            Component component, String name, Class<T> type) {
        if (type.isInstance(component) && name.equals(component.getName())) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = namedOrNull(child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T findOrNull(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = findOrNull(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static WikidataIdListEditor findQidListBackedByTextArea(Component component) {
        if (component instanceof WikidataIdListEditor ids
                && ids.storage() instanceof JTextArea) return ids;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                WikidataIdListEditor found = findQidListBackedByTextArea(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<String> labels(Component component) {
        List<String> labels = new java.util.ArrayList<>();
        collectLabels(component, labels);
        return labels;
    }

    private static void collectLabels(Component component, List<String> labels) {
        if (component instanceof JLabel label && label.getText() != null) {
            labels.add(label.getText().replaceAll("<[^>]+>", ""));
        }
        // Read-only text whose ids are links (#404) — a status line reads as its words.
        if (component instanceof wikidata.ui.WikidataIdText text) {
            labels.add(text.shownText());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectLabels(child, labels);
        }
    }

    private static ClassSourcePanel panelFor(GeneratedClassModel clazz) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(clazz);
        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        return panel;
    }

    private static void render(Component component, String path) throws IOException {
        component.setSize(900, Math.max(720, component.getPreferredSize().height));
        layout(component);
        File artifact = new File(path);
        assertTrue(artifact.getParentFile().mkdirs() || artifact.getParentFile().isDirectory());
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layout(child);
        }
    }
}
