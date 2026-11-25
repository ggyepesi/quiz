package domain.workbench.ui;

import domain.datasource.api.SourceRecipe;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.awt.BorderLayout;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field-value operation the registry declares is offered with the inputs it declares.
 * Before this form, adding a source meant a new FieldSourceType constant and a branch in
 * every editor that switched on it; a provider now joins by being registered.
 */
class DatasourceOperationFormTest {

    @Test void aRegisteredOperationIsOfferedWithItsDeclaredDefault() {
        DatasourceOperationForm form = new DatasourceOperationForm(
                domain.datasource.Datasources.standard(),
                domain.config.FieldSourceBindings::registryOnly);

        assertTrue(form.hasOperations());
        assertNull(form.recipe(), "nothing is chosen until the modeller chooses it");

        var chooser = (JComboBox<?>) ((BorderLayout) form.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        var pageviews = form.choices().stream().filter(choice -> "wikimedia-pageviews".equals(choice.provider().id()))
                .findFirst().orElseThrow();
        chooser.setSelectedItem(pageviews);

        assertEquals(new SourceRecipe("wikimedia-pageviews", "article-views",
                Map.of("months", "12")), form.recipe());
    }

    @Test void aStoredRecipeIsShownAsStored() {
        DatasourceOperationForm form = new DatasourceOperationForm(
                domain.datasource.Datasources.standard(),
                domain.config.FieldSourceBindings::registryOnly);
        SourceRecipe stored = new SourceRecipe("wikimedia-pageviews", "article-views",
                Map.of("months", "6"));

        form.show(stored);

        assertEquals(stored, form.recipe());
    }

    @Test void anOperationWithItsOwnSourceTypeIsNotOfferedTwice() {
        DatasourceOperationForm form = new DatasourceOperationForm(
                domain.datasource.Datasources.standard(),
                domain.config.FieldSourceBindings::registryOnly);

        form.show(new SourceRecipe("dbpedia", "property", Map.of("property", "country")));

        assertNull(form.recipe());
    }

    @Test void relationRoutesRoundTripThroughTheStructuralParameterControl() {
        DatasourceOperationForm form = new DatasourceOperationForm(
                domain.datasource.Datasources.standard(),
                domain.config.FieldSourceBindings::registryOnly);
        String routes = """
                [{"id":"direct","label":"Direct child","steps":[
                  {"relation":"P40","direction":"OUTGOING"}]}]
                """;
        SourceRecipe stored = new SourceRecipe("wikidata", "relation-routes",
                Map.of("routes", routes));

        form.show(stored);

        assertEquals(domain.datasource.wikidata.WikidataRelationRouteSpec.parse(routes).routes(),
                domain.datasource.wikidata.WikidataRelationRouteSpec.parse(
                        form.recipe().parameter("routes")).routes());
    }
}
