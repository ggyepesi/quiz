package wikidata.explore.workbench;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import datasource.graph.GraphExpansionPolicy;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.CanonicalSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JTextField;
import javax.swing.JComboBox;
import java.lang.reflect.Field;

class StatementSourcePanelTest {

    @Test void statementAnatomyNamesTheContainingEntityAndInverseCollection() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource("P39");
        source.valueSelectionName("Positions");
        holding.statementSource(source);
        var subject = holding.addField("source", FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.SINGLE);
        subject.entityClassName("Person");
        subject.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.STATEMENT_SUBJECT);
        var position = holding.addField("position", FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.SINGLE);
        position.entityClassName("Position");
        position.mapping().propertyPid("P39");
        position.mapping().propertyLabel("position held");
        var start = holding.addField("startDate", FieldType.DATE,
                wikidata.explore.model.FieldCardinality.SINGLE);
        start.mapping().qualifierPid("P580");
        start.mapping().propertyLabel("start time");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        var offices = person.addField("offices", FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.COLLECTION);
        offices.entityClassName("OfficeHolding");
        offices.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.INVERT);
        offices.mapping().inverseField("source");
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        holding.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(holding));
        project.addClass(holding);
        project.addClass(person);

        StatementAnatomyPanel panel = new StatementAnatomyPanel();
        panel.show(project, holding);

        assertTrue(panel.meaningText().contains("position held (P39)"));
        assertTrue(panel.meaningText().contains("stored on a Person entity"));
        assertTrue(panel.meaningText().contains("Positions"));
        assertTrue(panel.mappingsText().contains("subject entity  → source (Person)"));
        assertTrue(panel.mappingsText().contains("statement value → position (Position)"));
        assertTrue(panel.mappingsText().contains("qualifier start time (P580) → startDate"));
        assertTrue(panel.mappingsText().contains(
                "records whose source is a Person → Person.offices (list)"));
    }

    @Test void duplicatePolicyIsEditableAndAppliedWithTheCanonicalControls()
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.statementSource(new StatementClassSource("P166"));
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        award.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(award));
        project.addClass(award);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(award);
        Field field = StatementSourcePanel.class.getDeclaredField("duplicatePolicyBox");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        JComboBox<CanonicalSpec.DuplicatePolicy> box =
                (JComboBox<CanonicalSpec.DuplicatePolicy>) field.get(panel);
        box.setSelectedItem(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS);

        panel.applyEdits();

        assertEquals(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS,
                award.canonical().duplicatePolicy());
    }

    // The "Re-derive identity" test went with the button. A key swept from the scalar
    // AUTO fields was never a derivation: identity is configured, and a command that
    // overwrites the configuration is not the same thing as deriving it. Subject,
    // object and qualifiers are all components of the tuple an instance represents, and
    // the key is whichever of them the modeller picks.

    @Test void applyingCompactEditorPreservesAnExistingDisplayTemplate() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        holding.canonical(new CanonicalSpec()
                .displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE)
                .displayNameTemplate("{position} ({startDate}–{endDate})"));
        project.addClass(holding);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(holding);
        panel.applyEdits();

        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE,
                holding.canonical().displayNameMode());
        assertEquals("{position} ({startDate}–{endDate})",
                holding.canonical().displayNameTemplate());
    }

    // Regression: a statement class with NO source class (subjects discovered from
    // the property) must survive applyEdits(). Previously a blank "Reify from"
    // nulled the statement source on every applyEdits — silently reverting the
    // Oscars Nomination to a plain class (then "no membership type" errors).
    @Test void applyEditsKeepsSourceClasslessStatementClass() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        StatementClassSource src = new StatementClassSource("P1411");
        src.valueSelectionName("OscarCategories");
        src.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        nom.statementSource(src);
        project.addClass(nom);
        project.rootClass(nom);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(nom);
        panel.applyEdits();

        assertTrue(nom.reifiesStatements(),
                "a discovered-subject statement class must remain one after applyEdits");
        assertFalse(nom.statementSource().hasSourceClass());
        assertEquals("P1411", nom.statementSource().propertyPid());
        assertEquals("OscarCategories", nom.statementSource().valueSelectionName(),
                "the value Selection must be preserved through applyEdits");
        assertEquals(GraphExpansionPolicy.CURATED,
                nom.statementSource().graphExpansionPolicy(),
                "the explicit graph policy must survive an unrelated panel apply");
    }

    @Test void renameUsesTheProjectOperationSoReferencesFollow() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel prize = new GeneratedClassModel("NobelPrizes");
        prize.statementSource(new StatementClassSource("P166"));
        GeneratedClassModel laureate = new GeneratedClassModel("Laureate");
        laureate.addField("prizes",
                datasource.schema.FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.COLLECTION)
                .entityClassName("NobelPrizes");
        project.rootClass(prize);
        project.addClass(laureate);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(prize);
        Field field = StatementSourcePanel.class.getDeclaredField("classNameField");
        field.setAccessible(true);
        ((JTextField) field.get(panel)).setText("Nobel Prize");
        panel.applyEdits();

        assertEquals("NobelPrize", prize.className());
        assertEquals("NobelPrize", laureate.fields().getFirst().entityClassName(),
                "a field target must follow a rename performed in the Statement editor");
    }


    /** Every label the panel currently renders, joined. */
    private static String labelTexts(java.awt.Container root) {
        StringBuilder text = new StringBuilder();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel label && label.getText() != null) {
                text.append(label.getText()).append('\n');
            }
            if (child instanceof java.awt.Container container) {
                text.append(labelTexts(container));
            }
        }
        return text.toString();
    }



    private static void setCombo(StatementSourcePanel panel, String name, String value)
            throws Exception {
        Field f = StatementSourcePanel.class.getDeclaredField(name);
        f.setAccessible(true);
        ((JComboBox<?>) f.get(panel)).setSelectedItem(value);
    }

    private static String comboValue(StatementSourcePanel panel, String name)
            throws Exception {
        Field f = StatementSourcePanel.class.getDeclaredField(name);
        f.setAccessible(true);
        Object selected = ((JComboBox<?>) f.get(panel)).getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    private static void setText(StatementSourcePanel panel, String name, String value)
            throws Exception {
        Field f = StatementSourcePanel.class.getDeclaredField(name);
        f.setAccessible(true);
        ((javax.swing.text.JTextComponent) f.get(panel)).setText(value);
    }
    /**
     * Both ends are configured by the SAME editor, given a different word.
     *
     * <p>They were two sets of controls asking one question, and they had already
     * drifted: only the object could be bounded by a vocabulary, only the subject by
     * explicit QIDs. Asserting that two EntityEndEditors exist is what keeps a second
     * hand-written end from reappearing beside the shared one.
     */
    @Test void eachEndIsConfiguredByTheSameEditor() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        holding.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(holding));
        project.addClass(holding);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(holding);

        java.util.List<EntityEndEditor> ends = new java.util.ArrayList<>();
        collect(panel, EntityEndEditor.class, ends);
        assertEquals(2, ends.size(), "one editor per end, and no more");

        String shown = labelTexts(panel);
        // "Nothing holds it", not "Not configured": an end with no destination field is
        // missing a HOME, which is not the same as being unconfigured. Nobel's subject
        // has no QIDs bounding it and is modelled as Laureate — unbounded and
        // configured — and the old wording made those read as one state.
        assertTrue(shown.contains("Nothing holds it"),
                "an unsettled end says so rather than vanishing: " + shown);
    }

    /** A bound set through the editor reaches the model, and comes back on reopening. */
    @Test void aBoundSetOnAnEndIsStoredAndShownAgain() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        holding.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(holding));
        project.addClass(holding);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(holding);

        java.util.List<EntityEndEditor> ends = new java.util.ArrayList<>();
        collect(panel, EntityEndEditor.class, ends);
        EntityEndEditor subject = ends.get(0);
        subject.show(EntityBound.instancesOf("Q5"));
        panel.applyEdits();

        EntityBound stored = holding.statementSource().subjectBound();
        assertEquals(EntityBound.Kind.RELATION, stored.kind());
        assertEquals(java.util.List.of("Q5"), stored.qids());

        panel.edit(holding);
        collect(panel, EntityEndEditor.class, ends = new java.util.ArrayList<>());
        assertEquals(EntityBound.Kind.RELATION, ends.get(0).bound().kind(),
                "reopening shows what was configured, not the default");
    }

    /**
     * A vocabulary this project cannot list is still a reference the model holds.
     * Leaving the box on something else would DELETE it on the next apply, because what
     * the control shows is what gets written.
     */
    @Test void aVocabularyTheProjectCannotListSurvivesAnApply() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        StatementClassSource source = new StatementClassSource("P1411");
        source.objectBound(EntityBound.vocabulary("OscarCategories"));
        nom.statementSource(source);
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        nom.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(nom));
        project.addClass(nom);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(nom);
        panel.applyEdits();

        assertEquals("OscarCategories",
                nom.statementSource().objectBound().selectionName());
    }

    private static <T> void collect(
            java.awt.Container root, Class<T> type, java.util.List<T> into) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) into.add(type.cast(child));
            else if (child instanceof java.awt.Container container) {
                collect(container, type, into);
            }
        }
    }
}
