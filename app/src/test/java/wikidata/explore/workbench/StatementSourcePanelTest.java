package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import datasource.graph.GraphExpansionPolicy;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementSourcePanelTest {

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
}
