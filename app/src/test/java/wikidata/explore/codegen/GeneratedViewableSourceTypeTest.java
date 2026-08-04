package wikidata.explore.codegen;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.StatementClassSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertFalse(source.contains("extends quiz.source.WikidataViewable"));
        assertFalse(source.contains("public String qid"));
    }
}
