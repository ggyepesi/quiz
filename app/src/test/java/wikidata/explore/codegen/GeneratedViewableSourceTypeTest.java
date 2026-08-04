package wikidata.explore.codegen;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.StatementClassSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedViewableSourceTypeTest {
    private final GeneratedViewableSourceGenerator generator =
            new GeneratedViewableSourceGenerator("generated.test");

    @Test void entityClassExtendsTheNeutralGeneratedEntityBase() {
        String source = generator.sourceFor(new GeneratedClassModel("Country"));

        assertTrue(source.contains("extends quiz.source.GeneratedEntity"));
        assertFalse(source.contains("public String qid"));
    }

    @Test void statementClassAlsoExtendsGeneratedEntityNotAnEntityBase() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));

        String source = generator.sourceFor(nomination);

        // A statement is not an entity: it extends the neutral base, its
        // statement/property provenance living in the anchor, not a superclass.
        assertTrue(source.contains("extends quiz.source.GeneratedEntity"));
        assertFalse(source.contains("extends quiz.source.WikidataSource"));
        assertFalse(source.contains("public String qid"));
    }

    @Test void generatedEntityAndStatementSourcesCompileAndMaterialize() throws Exception {
        GeneratedClassModel country = new GeneratedClassModel("Country");
        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(country)) {
            wikidata.explore.extract.WikidataDynamicObject source =
                    new wikidata.explore.extract.WikidataDynamicObject("Q28", "Hungary");
            source.type("Country");
            Object mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(java.util.List.of(source)).getFirst();
            assertTrue(mapped instanceof quiz.source.GeneratedEntity);
            assertEquals("Q28", ((objectview.Viewable) mapped).getIdentifier());
        }

        GeneratedClassModel statementClass = new GeneratedClassModel("Fact");
        statementClass.statementSource(new StatementClassSource("P31"));
        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(statementClass)) {
            wikidata.explore.extract.WikidataDynamicObject statement =
                    new wikidata.explore.extract.WikidataDynamicObject(
                            "Q28$statement-guid", "statement fact");
            statement.type("Fact");
            Object mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(java.util.List.of(statement)).getFirst();
            assertEquals("Q28$statement-guid",
                    ((objectview.Viewable) mapped).getIdentifier());
        }
    }
}
