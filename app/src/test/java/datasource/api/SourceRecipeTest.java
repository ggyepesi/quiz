package datasource.api;

import datasource.Datasources;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceRecipeTest {

    @Test void normalizesDurableConfigurationWithoutRuntimeState() {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put(" property ", " P31 ");
        parameters.put("", "ignored");

        SourceRecipe recipe = new SourceRecipe(
                " wikidata ", " statement-membership ", parameters);
        parameters.put("property", "P999");

        assertEquals("wikidata", recipe.providerId());
        assertEquals("statement-membership", recipe.operationId());
        assertEquals("P31", recipe.parameter("property"));
        assertFalse(recipe.parameters().containsKey(""));
    }

    @Test void resolvesThroughTheRegistryAndChecksTheBindingScope() {
        SourceRecipe recipe = new SourceRecipe("wikidata", "statement-membership",
                Map.of("property", "P31", "values", "Q5"));

        assertEquals(BindingScope.CLASS_POPULATION,
                recipe.resolve(Datasources.standard(), BindingScope.CLASS_POPULATION).scope());
        IllegalArgumentException wrongScope = assertThrows(IllegalArgumentException.class,
                () -> recipe.resolve(Datasources.standard(), BindingScope.FIELD_VALUE));
        assertTrue(wrongScope.getMessage().contains("CLASS_POPULATION"));
    }

    @Test void missingOfferingFailsWhereTheRecipeIsResolved() {
        SourceRecipe recipe = new SourceRecipe("wikidata", "missing", Map.of());

        assertThrows(IllegalStateException.class,
                () -> recipe.resolve(Datasources.standard()));
    }
}
