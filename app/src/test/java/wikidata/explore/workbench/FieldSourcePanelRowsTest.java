package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceType;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.StatementFieldSemantics;
import wikidata.explore.model.OwnedFieldSource;

import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a reader is shown when configuring one field.
 *
 * <p>A row that cannot apply to the field being configured is not shown at all. This is
 * the test the panel could not have while rows were built from string literals: nothing
 * held the label, so nothing could be hidden and there was no visible set to assert.
 */
class FieldSourcePanelRowsTest {

    @Test void aReferenceCollectionCanPersistWikipediaIntroductionMentions() {
        GeneratedFieldModel characters = field("characters", FieldType.ENTITY,
                FieldProductionKind.AUTO, "Character");
        characters.cardinality(FieldCardinality.COLLECTION);
        GeneratedProjectModel project = projectContaining(characters, true);
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(characters);

        JComboBox<?> candidates = comboContainingLabel(panel,
                "Loaded target instances mentioned in the introduction");
        assertTrue(candidates.isEnabled());
        selectLabel(candidates, "Loaded target instances mentioned in the introduction");
        button(panel, "Apply field source").doClick();

        datasource.api.SourceBinding binding =
                wikidata.explore.model.FieldSourceBindings.binding(characters,
                        datasource.api.SourceBindingSlot.CANDIDATE_EVIDENCE);
        assertTrue(datasource.wikipedia.WikipediaDatasourceProvider
                .articleIntroductionMentions(binding));

        FieldSourcePanel reopened = new FieldSourcePanel();
        reopened.setProjectModel(project);
        reopened.edit(characters);
        JComboBox<?> reopenedCandidates = comboContainingLabel(reopened,
                "Loaded target instances mentioned in the introduction");
        assertEquals("Loaded target instances mentioned in the introduction",
                reopenedCandidates.getSelectedItem().toString());
    }

    @Test void oneTextValueCanPersistBoundedWikipediaArticlePassages() {
        GeneratedFieldModel narrative = field("narrativeText", FieldType.TEXT,
                FieldProductionKind.AUTO, "");
        GeneratedProjectModel project = projectContaining(narrative, true);
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(narrative);

        JComboBox<?> evidence = comboContainingLabel(panel,
                "Bounded passages from the full article");
        assertTrue(evidence.isEnabled());
        selectLabel(evidence, "Bounded passages from the full article");
        button(panel, "Apply field source").doClick();

        datasource.api.SourceBinding binding =
                wikidata.explore.model.FieldSourceBindings.binding(narrative,
                        datasource.api.SourceBindingSlot.CANDIDATE_EVIDENCE);
        assertTrue(datasource.wikipedia.WikipediaDatasourceProvider.articlePassages(binding));

        FieldSourcePanel reopened = new FieldSourcePanel();
        reopened.setProjectModel(project);
        reopened.edit(narrative);
        JComboBox<?> reopenedEvidence = comboContainingLabel(reopened,
                "Bounded passages from the full article");
        assertEquals("Bounded passages from the full article",
                reopenedEvidence.getSelectedItem().toString());
    }

    @Test void aReferenceListMapsArticlePassagesOntoItsTargetClass() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel episodes = character.addField(
                "episodes", FieldType.ENTITY, FieldCardinality.COLLECTION);
        episodes.entityClassName("Episode");
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        episode.addField("summary", FieldType.TEXT, FieldCardinality.SINGLE);
        GeneratedFieldModel characters = episode.addField(
                "characters", FieldType.ENTITY, FieldCardinality.COLLECTION);
        characters.entityClassName("Character");
        project.rootClass(character);
        project.addClass(episode);
        episodes.entityDeclarationId(episode.declarationId());
        characters.entityDeclarationId(character.declarationId());
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(episodes);

        JComboBox<?> evidence = comboContainingLabel(panel,
                "One target instance per bounded article passage");
        selectLabel(evidence, "One target instance per bounded article passage");
        panel.passageTextFieldBox().setSelectedItem("summary");
        panel.passageSourceFieldBox().setSelectedItem("characters");
        assertTrue(visibleRowLabels(panel).contains("Passage goes into:"));
        assertTrue(visibleRowLabels(panel).contains("Source instance goes into:"));
        button(panel, "Apply field source").doClick();

        var binding = wikidata.explore.model.FieldSourceBindings.binding(episodes,
                datasource.api.SourceBindingSlot.CANDIDATE_EVIDENCE);
        var spec = datasource.wikipedia.WikipediaDatasourceProvider
                .articlePassageInstances(binding);
        assertEquals("summary", spec.textField());
        assertEquals("characters", spec.sourceField());

        FieldSourcePanel reopened = new FieldSourcePanel();
        reopened.setProjectModel(project);
        reopened.edit(episodes);
        assertEquals("One target instance per bounded article passage",
                comboContainingLabel(reopened,
                        "One target instance per bounded article passage")
                        .getSelectedItem().toString());
        assertEquals("summary", reopened.passageTextFieldBox().getSelectedItem());
        assertEquals("characters", reopened.passageSourceFieldBox().getSelectedItem());
    }

    @Test void ownedFieldListsOwnerSitesAndOnlyApplyCreatesAnOverride() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel personDiscovery = person.addField(
                "discovery", FieldType.ENTITY, FieldCardinality.SINGLE);
        personDiscovery.entityClassName("Discovery");
        personDiscovery.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel organisation = new GeneratedClassModel("Organisation");
        GeneratedFieldModel organisationDiscovery = organisation.addField(
                "discovery", FieldType.ENTITY, FieldCardinality.SINGLE);
        organisationDiscovery.entityClassName("Discovery");
        organisationDiscovery.mapping().productionKind(
                FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel discovery = new GeneratedClassModel("Discovery");
        discovery.ownedClass(true);
        GeneratedFieldModel date = discovery.addField(
                "date", FieldType.DATE, FieldCardinality.SINGLE);
        date.mapping().propertyPid("P575");
        project.rootClass(person);
        project.addClass(organisation);
        project.addClass(discovery);
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(date);

        JComboBox<FieldSourcePanel.SourceContext> contexts = panel.sourceContextBox();
        assertEquals(3, contexts.getItemCount());
        contexts.setSelectedIndex(2);
        assertTrue(organisationDiscovery.ownedFieldSources().isEmpty(),
                "inspection must not mutate the model");

        panel.pendingPropertyField().setText("P734");
        panel.applyEdits();

        OwnedFieldSource override =
                organisationDiscovery.ownedFieldSources().getFirst();
        assertEquals("date", override.fieldPath());
        assertEquals("P734", override.mapping().propertyPid());
        assertEquals("P575", date.mapping().propertyPid(),
                "the shared default remains unchanged");
        assertTrue(personDiscovery.ownedFieldSources().isEmpty());

        button(panel, "Use shared default").doClick();
        assertTrue(organisationDiscovery.ownedFieldSources().isEmpty());
        assertEquals("P575", panel.pendingPropertyField().getText());
    }

    @Test void anOrdinaryFieldIsNotOfferedTheRowsOfOtherKinds() {
        FieldSourcePanel panel = editing(field("birthName", FieldType.STRING,
                FieldProductionKind.AUTO, ""));

        List<String> shown = visibleRowLabels(panel);
        assertTrue(shown.contains("Load as:"), shown.toString());
        for (String elsewhere : List.of("Inverse of:", "Match role field:",
                "Match value field:", "Subject field:", "Graph expansion:",
                "Qualifier time:", "Missing qualifier:", "Reify role:")) {
            assertTrue(!shown.contains(elsewhere),
                    elsewhere + " belongs to another kind of field: " + shown);
        }
    }

    @Test void anInverseFieldIsAskedWhichForwardFieldItReads() {
        FieldSourcePanel panel = editing(field("holders", FieldType.ENTITY,
                FieldProductionKind.INVERT, "Person"));

        assertTrue(visibleRowLabels(panel).contains("Inverse of:"),
                "an INVERT field must be asked the one thing it needs");
    }

    /** A union's sources are picked from the owning class's declared fields, the way
     *  a quiz key's paths are, and never typed. */
    @Test void aFieldUnionPicksItsSourcePathsFromTheOwnersFieldsAndIsNotAnInverse()
            throws Exception {
        GeneratedFieldModel allMoons = field("allMoons", FieldType.ENTITY,
                FieldProductionKind.UNION, "Moon");
        GeneratedProjectModel project = projectContaining(allMoons, true);
        GeneratedClassModel planet = project.declaringClass(allMoons);
        planet.addField("moons", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Moon");
        planet.addField("moonKinds", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("MoonKind");
        GeneratedClassModel moonKind = new GeneratedClassModel("MoonKind");
        moonKind.addField("moons", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Moon");
        project.addClass(moonKind);
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(allMoons);

        List<String> shown = visibleRowLabels(panel);
        assertTrue(shown.contains("Union of fields:"), shown.toString());
        assertFalse(shown.contains("Inverse of:"), shown.toString());
        assertTrue(components(panel, javax.swing.JTextArea.class).isEmpty(),
                "no path is typed");

        objectview.viewconfig.ViewConfigEditor picker = only(
                components(panel, objectview.viewconfig.ViewConfigEditor.class));
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            picker.setSelectedPath(objectview.field.FieldPath.parse("moonKinds.moons"));
            assertTrue(picker.checkFieldPath(objectview.field.FieldPath.of("moons")));
            assertTrue(picker.checkFieldPath(objectview.field.FieldPath.of("moonKinds")));
            assertTrue(picker.checkFieldPath(
                    objectview.field.FieldPath.of("moonKinds", "moons")));
        });
        button(panel, "Apply field source").doClick();

        assertEquals(FieldCardinality.COLLECTION, allMoons.cardinality());
        assertEquals(List.of("moons", "moonKinds.moons"),
                allMoons.mapping().unionSourcePaths(),
                "moonKinds is the way to its moons, not a source of its own");

        FieldSourcePanel reopened = new FieldSourcePanel();
        reopened.setProjectModel(project);
        reopened.edit(allMoons);
        assertEquals(List.of(objectview.field.FieldPath.of("moons"),
                        objectview.field.FieldPath.of("moonKinds"),
                        objectview.field.FieldPath.of("moonKinds", "moons")),
                only(components(reopened, objectview.viewconfig.ViewConfigEditor.class))
                        .selectedFieldPaths(),
                "the stored sources come back ticked");
    }

    /**
     * Expansion follows an edge and then follows it again, so it is offered only where
     * the target is the owner's own class. A cross-class target reaches instances of
     * something else, and treating their seeds as coverage of this edge would be false.
     */
    @Test void aFieldPointingAtItsOwnClassMayDeclareGraphExpansion() {
        GeneratedFieldModel broader =
                field("broader", FieldType.ENTITY, FieldProductionKind.AUTO, "Owner");
        broader.mapping().sourceType(FieldSourceType.SPARQL);
        broader.mapping().propertyPid("P279");

        assertTrue(visibleRowLabels(editing(broader)).contains("Graph expansion:"),
                "the row appears exactly where the traversal rule says it could apply");
    }

    @Test void aFieldPointingAtAnotherClassMayNot() {
        GeneratedFieldModel spouse =
                field("spouse", FieldType.ENTITY, FieldProductionKind.AUTO, "Person");
        spouse.mapping().sourceType(FieldSourceType.SPARQL);
        spouse.mapping().propertyPid("P26");

        assertFalse(visibleRowLabels(editing(spouse)).contains("Graph expansion:"),
                "Person is not the owner's class, so following the edge twice would "
                        + "leave the population this edge is about");
    }

    @Test void aVocabularyTargetIsNotOfferedEntityGraphExpansion() {
        GeneratedFieldModel genre =
                field("genre", FieldType.ENTITY, FieldProductionKind.AUTO, "Genres");
        genre.mapping().sourceType(FieldSourceType.SPARQL);
        genre.mapping().propertyPid("P136");

        FieldSourcePanel panel = new FieldSourcePanel();
        GeneratedProjectModel project = projectContaining(genre, false);
        project.addSelection(new wikidata.explore.model.VocabularySelection("Genres"));
        panel.setProjectModel(project);
        panel.edit(genre);

        assertTrue(!visibleRowLabels(panel).contains("Graph expansion:"),
                "a selection is an entity-shaped target, but not a modeled traversal class");
    }

    @Test void changingTheLiveTypeImmediatelyRevealsDateProjectionRows() {
        FieldSourcePanel panel = editing(field(
                "when", FieldType.STRING, FieldProductionKind.AUTO, ""));
        JComboBox<?> type = comboContaining(panel, FieldType.DATE);

        type.setSelectedItem(FieldType.DATE);
        List<String> shown = visibleRowLabels(panel);
        assertTrue(shown.contains("Subject field:"), shown.toString());
        assertTrue(shown.contains("Match value field:"), shown.toString());
        assertTrue(!shown.contains("Match role field:"), shown.toString());

        type.setSelectedItem(FieldType.STRING);
        shown = visibleRowLabels(panel);
        assertTrue(!shown.contains("Subject field:"), shown.toString());
        assertTrue(!shown.contains("Match value field:"), shown.toString());
    }

    @Test void companionMatchShowsAllThreeMatchingRows() {
        List<String> shown = visibleRowLabels(editing(field(
                "match", FieldType.STRING, FieldProductionKind.COMPANION_MATCH, "")));

        assertTrue(shown.contains("Subject field:"), shown.toString());
        assertTrue(shown.contains("Match value field:"), shown.toString());
        assertTrue(shown.contains("Match role field:"), shown.toString());
    }

    @Test void statementObjectIsAnExplicitFieldSourceNotARepeatedProperty() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        GeneratedFieldModel position = holding.addField(
                "position", FieldType.ENTITY, FieldCardinality.SINGLE);
        position.mapping().propertyPid("P39");
        position.sourceBindings().add(new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue(
                        "OfficeHolding", "position",
                        datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new datasource.api.SourceRecipe("wikidata", "property",
                        java.util.Map.of("property", "P39"))));
        // An additional source the field carried before it became an end of the
        // triple. Bindings are re-derived from it at save time, so clearing the
        // binding list alone let the producer walk back in one save later.
        wikidata.explore.model.FieldSourceMapping fallback =
                new wikidata.explore.model.FieldSourceMapping();
        fallback.propertyPid("birthPlace");
        fallback.sourceType(wikidata.explore.model.FieldSourceType.DBPEDIA);
        position.fallbackMapping(fallback);
        project.addClass(holding);

        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(position);
        comboContaining(panel, FieldProductionKind.STATEMENT_OBJECT)
                .setSelectedItem(FieldProductionKind.STATEMENT_OBJECT);
        button(panel, "Apply field source").doClick();

        assertEquals(FieldProductionKind.STATEMENT_OBJECT,
                position.mapping().productionKind());
        assertEquals("", position.mapping().propertyPid(),
                "the statement class owns P39; its object field does not repeat it");
        assertTrue(position.sourceBindings().isEmpty(),
                "the old direct field recipe must not remain as a second producer");
        assertEquals("position",
                StatementFieldSemantics.statementValueFieldName(holding));

        wikidata.explore.model.FieldSourceBindings.synchronizeForSave(project);

        assertTrue(position.sourceBindings().isEmpty(),
                "and no producer is re-derived when the model is saved: "
                        + position.sourceBindings());
    }

    // The qualifier SOURCE is the deliberate exception: it stays visible on a class
    // that cannot use it, so the class/field relationship is explicit. Only the
    // settings OF a qualifier disappear when there is no qualifier to settle.
    @Test void theQualifierSourceStaysVisibleWhileItsSettingsDoNot() {
        List<String> shown = visibleRowLabels(editing(field(
                "birthName", FieldType.STRING, FieldProductionKind.AUTO, "")));

        assertTrue(shown.contains("Qualifier of:"), shown.toString());
        assertTrue(!shown.contains("Reify role:"), shown.toString());
    }

    private static GeneratedFieldModel field(
            String name, FieldType type, FieldProductionKind kind, String target) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        project.addClass(person);
        project.rootClass(person);
        GeneratedFieldModel created = person.addField(name, type, FieldCardinality.SINGLE);
        created.entityClassName(target);
        created.mapping().productionKind(kind);
        return created;
    }

    private static FieldSourcePanel editing(GeneratedFieldModel field) {
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(projectContaining(field, true));
        panel.edit(field);
        return panel;
    }

    private static GeneratedProjectModel projectContaining(
            GeneratedFieldModel field, boolean includeTargetClass) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel owner = new GeneratedClassModel("Owner");
        // The field has to be IN this project, or it has no declaring class here and
        // every rule that asks who owns it answers null.
        owner.fields().add(field);
        project.addClass(owner);
        project.rootClass(owner);
        if (includeTargetClass && !field.entityClassName().isBlank()
                && project.findClass(field.entityClassName()) == null) {
            project.addClass(new GeneratedClassModel(field.entityClassName()));
        }
        return project;
    }

    private static JComboBox<?> comboContaining(Container root, Object value) {
        ArrayDeque<Container> pending = new ArrayDeque<>();
        Set<Component> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            for (Component component : pending.removeFirst().getComponents()) {
                if (!seen.add(component)) continue;
                if (component instanceof JComboBox<?> combo) {
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        if (value.equals(combo.getItemAt(i))) return combo;
                    }
                }
                if (component instanceof Container child) pending.addLast(child);
            }
        }
        throw new AssertionError("No combo contains " + value);
    }

    private static JComboBox<?> comboContainingLabel(Container root, String label) {
        for (JComboBox<?> combo : components(root, JComboBox.class)) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (label.equals(combo.getItemAt(i).toString())) return combo;
            }
        }
        throw new AssertionError("No combo contains " + label);
    }

    private static void selectLabel(JComboBox<?> combo, String label) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (label.equals(combo.getItemAt(i).toString())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        throw new AssertionError("No combo item named " + label);
    }

    private static JButton button(Container root, String text) {
        ArrayDeque<Container> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            for (Component component : pending.removeFirst().getComponents()) {
                if (component instanceof JButton button && text.equals(button.getText())) {
                    return button;
                }
                if (component instanceof Container child) pending.addLast(child);
            }
        }
        throw new AssertionError("No button named " + text);
    }

    private static JCheckBox checkBox(Container root, String text) {
        return components(root, JCheckBox.class).stream()
                .filter(box -> text.equals(box.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("No checkbox named " + text));
    }

    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        ArrayDeque<Container> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            for (Component component : pending.removeFirst().getComponents()) {
                if (type.isInstance(component)) found.add(type.cast(component));
                if (component instanceof Container child) pending.addLast(child);
            }
        }
        return found;
    }

    private static <T> T only(List<T> found) {
        assertEquals(1, found.size(), found.toString());
        return found.get(0);
    }

    /** The labels a reader can actually see, in layout order. */
    private static List<String> visibleRowLabels(Container root) {
        List<String> labels = new ArrayList<>();
        collect(root, labels);
        return labels;
    }

    private static void collect(Container root, List<String> labels) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && component.isVisible()
                    && label.getText() != null && label.getText().endsWith(":")) {
                labels.add(label.getText());
            }
            if (component instanceof Container child
                    && !(component instanceof JComponent c && !c.isVisible())) {
                collect(child, labels);
            }
        }
    }
}
