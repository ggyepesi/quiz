package wikidata.explore.workbench;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import datasource.graph.GraphExpansionPolicy;
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

    @Test void rederivePreviewsAndRequiresConfirmationWithoutResettingOtherPolicies() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.statementSource(new StatementClassSource("P166"));
        award.addField("category", datasource.schema.FieldType.ENTITY,
                        wikidata.explore.model.FieldCardinality.SINGLE)
                .mapping().propertyPid("P166");
        award.canonical().keyFields().add("customKey");
        award.canonical()
                .duplicatePolicy(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS)
                .displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE)
                .displayNameTemplate("{category}");
        project.addClass(award);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(award);

        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> seenCurrent =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> seenProposed =
                new java.util.concurrent.atomic.AtomicReference<>();
        panel.identityChangeConfirmation((current, proposed) -> {
            seenCurrent.set(current);
            seenProposed.set(proposed);
            return false;
        });
        panel.rederiveIdentity();

        assertEquals(java.util.List.of("customKey"), seenCurrent.get());
        assertEquals(java.util.List.of("category"), seenProposed.get());
        assertEquals(java.util.List.of("customKey"), award.canonical().keyFields(),
                "cancel leaves identity unchanged");

        panel.identityChangeConfirmation((current, proposed) -> true);
        panel.rederiveIdentity();

        assertEquals(java.util.List.of("category"), award.canonical().keyFields());
        assertEquals(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS,
                award.canonical().duplicatePolicy(),
                "re-derive identity must not reset duplicate handling");
        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE,
                award.canonical().displayNameMode(),
                "re-derive identity must not reset display policy");
        assertEquals("{category}", award.canonical().displayNameTemplate());
    }

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

    /**
     * A leg the model has not settled is a STATE, not an absence. The editor named after
     * the statement used to show every part of it except the subject — which was
     * configured, if at all, from the field editor — so a triple with an unfilled end
     * looked like a triple with no such end.
     */
    @Test void bothEntityLegsAreShownWhetherOrNotTheyAreConfigured() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        project.addClass(holding);

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(holding);

        String shown = labelTexts(panel);
        assertTrue(shown.contains("Subject:"), shown);
        assertTrue(shown.contains("Object:"), shown);
        assertTrue(shown.contains("Not configured"),
                "an unsettled leg must say so rather than vanish: " + shown);

        var subject = holding.addField("holder", FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.SINGLE);
        subject.entityClassName("PositionHolder");
        subject.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.STATEMENT_SUBJECT);
        panel.edit(holding);

        String settled = labelTexts(panel);
        assertTrue(settled.contains("holder"), settled);
        assertTrue(settled.contains("PositionHolder"),
                "the placeholder class names the leg once it is settled: " + settled);
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
}
