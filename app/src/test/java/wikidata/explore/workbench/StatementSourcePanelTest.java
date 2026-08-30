package wikidata.explore.workbench;

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
        award.addField("category", wikidata.explore.model.FieldType.ENTITY,
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
                wikidata.explore.model.FieldType.ENTITY,
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
}
