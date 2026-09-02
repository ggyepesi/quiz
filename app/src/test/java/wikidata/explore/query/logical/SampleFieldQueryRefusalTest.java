package wikidata.explore.query.logical;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.FieldSampleContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a reader is told when a field cannot be sampled on its own.
 *
 * <p>The refusal used to report the precondition that failed — "the owning class has no
 * population QID" — which names an internal fact about the model rather than the
 * reader's situation, and reads as a defect for a class that is working exactly as
 * configured.
 */
class SampleFieldQueryRefusalTest {

    private static GeneratedClassModel officeHolding() {
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        holding.addField("position", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Position");
        return holding;
    }

    private static String refusalFor(GeneratedClassModel owner, String fieldName) {
        GeneratedFieldModel field = owner.fields().stream()
                .filter(f -> f.name().equals(fieldName)).findFirst().orElseThrow();
        SampleFieldQuery query = new SampleFieldQuery(
                new FieldSampleContext(owner, field, ""), 8);
        return assertThrows(IllegalStateException.class,
                () -> query.execute(null)).getMessage();
    }

    /** A statement's roles are filled together, so the class is the smallest sample. */
    @Test void aStatementRoleSaysToSampleTheStatement() {
        String message = refusalFor(officeHolding(), "position");

        assertTrue(message.contains("role of the OfficeHolding statement"), message);
        assertTrue(message.contains("sample the OfficeHolding class"), message);
        assertFalse(message.contains("population QID"),
                "the reader is told what to do, not which precondition failed: " + message);
    }

    /** An ordinary class without a population says what is missing and why it matters. */
    @Test void anUnpopulatedClassSaysWhatAFieldSampleNeeds() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("spouse", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");

        String message = refusalFor(person, "spouse");

        assertTrue(message.contains("no population to sample members from"), message);
        assertTrue(message.contains("classification rule"),
                "and names the other way a population can be established: " + message);
    }
}
