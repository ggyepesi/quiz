package wikidata.explore.model;

import datasource.api.SourceOfferingBinding;
import datasource.wikidata.WikidataDatasourceProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PopulationSourceBindingTest {

    @Test void statementMembershipIsExposedAsAnOfferingBinding() {
        GeneratedClassModel clazz = new GeneratedClassModel("Film");
        clazz.instanceMapping().propertyPid("P31");
        clazz.instanceMapping().sourceQid("Q11424");
        clazz.instanceMapping().additionalTypeQids().add("Q202866");

        SourceOfferingBinding binding = clazz.populationSource();

        assertEquals(WikidataDatasourceProvider.ID, binding.providerId());
        assertEquals(WikidataDatasourceProvider.STATEMENT_MEMBERSHIP,
                binding.operationId());
        assertEquals("P31", binding.parameter("property"));
        assertEquals("Q11424,Q202866", binding.parameter("values"));
        assertEquals("false", binding.parameter("includeSubclasses"));
    }

    @Test void seedPopulationIsExposedAsAnOfferingBinding() {
        GeneratedClassModel clazz = new GeneratedClassModel("CuratedSet");
        clazz.seedQids().add("Q2");
        clazz.seedQids().add("Q1");

        SourceOfferingBinding binding = clazz.populationSource();

        assertEquals(WikidataDatasourceProvider.SEED_LIST, binding.operationId());
        assertEquals("Q2,Q1", binding.parameter("ids"));
    }

    @Test void assigningAnOfferingUpdatesTheConfigurationGenerationAlreadyUses() {
        GeneratedClassModel clazz = new GeneratedClassModel("Film");

        clazz.populationSource(new SourceOfferingBinding("wikidata",
                "statement-membership", java.util.Map.of(
                        "property", "P31", "values", "Q11424,Q202866",
                        "includeSubclasses", "false")));

        assertEquals("P31", clazz.instanceMapping().propertyPid());
        assertEquals("Q11424", clazz.instanceMapping().sourceQid());
        assertEquals(java.util.Set.of("Q202866"),
                clazz.instanceMapping().additionalTypeQids());
        assertEquals(MembershipPattern.MULTI_TYPE, MembershipPattern.of(clazz));
    }

    @Test void clearingAClassPopulationDoesNotEraseStatementProduction() {
        GeneratedClassModel clazz = new GeneratedClassModel("Nomination");
        StatementClassSource statement = new StatementClassSource();
        statement.propertyPid("P1411");
        statement.sourceClassName("Film");
        clazz.statementSource(statement);

        clazz.populationSource(null);

        assertTrue(clazz.reifiesStatements());
    }

    @Test void savePersistsTheNormalizedBindingAndRoundTripsIt() throws Exception {
        GeneratedProjectModel model = GeneratedProjectModel.constellationDemo();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();

        String json = store.toJson(model);

        assertTrue(json.contains("\"populationSource\""));
        assertTrue(json.contains("\"statement-membership\""));
        java.io.File file = java.io.File.createTempFile("population-source", ".json");
        try {
            java.nio.file.Files.writeString(file.toPath(), json);
            GeneratedClassModel loaded = store.load(file).rootClass();
            assertNotNull(loaded.populationSource());
            assertEquals("P31", loaded.populationSource().parameter("property"));
        } finally {
            assertTrue(file.delete() || !file.exists());
        }
    }
}
