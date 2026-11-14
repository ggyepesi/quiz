package workbench;

import datasource.api.SourceRecipe;
import org.junit.jupiter.api.Test;

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
                datasource.Datasources.standard(),
                wikidata.explore.model.FieldSourceBindings::registryOnly);

        assertTrue(form.hasOperations());
        assertNull(form.recipe(), "nothing is chosen until the modeller chooses it");

        form.show(new SourceRecipe("wikimedia-pageviews", "article-views", Map.of()));

        assertEquals(new SourceRecipe("wikimedia-pageviews", "article-views",
                Map.of("months", "12")), form.recipe());
    }

    @Test void aStoredRecipeIsShownAsStored() {
        DatasourceOperationForm form = new DatasourceOperationForm(
                datasource.Datasources.standard(),
                wikidata.explore.model.FieldSourceBindings::registryOnly);
        SourceRecipe stored = new SourceRecipe("wikimedia-pageviews", "article-views",
                Map.of("months", "6"));

        form.show(stored);

        assertEquals(stored, form.recipe());
    }

    @Test void anOperationWithItsOwnSourceTypeIsNotOfferedTwice() {
        DatasourceOperationForm form = new DatasourceOperationForm(
                datasource.Datasources.standard(),
                wikidata.explore.model.FieldSourceBindings::registryOnly);

        form.show(new SourceRecipe("dbpedia", "property", Map.of("property", "country")));

        assertNull(form.recipe());
    }
}
