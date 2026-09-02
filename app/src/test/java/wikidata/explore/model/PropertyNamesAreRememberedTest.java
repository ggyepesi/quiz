package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A property is remembered by name wherever it is configured, not only where it was
 * convenient.
 *
 * <p>A field mapping already kept the name of the property it was given, so a value
 * role could explain itself as "position held (P39)". A statement class and a qualifier
 * could not: both stored a bare PID, so an explanation assembled from the model could
 * only ever say "P39" and "P580". The catalogue that knows those names is a workbench
 * concern this package must not reach into, so the name travels with the choice.
 */
class PropertyNamesAreRememberedTest {

    @Test void aStatementClassRemembersWhatItsPropertyIsCalled() {
        StatementClassSource source = new StatementClassSource("P39");
        assertEquals("P39", source.describeProperty(),
                "a PID alone is all it can say until it is told the name");

        source.propertyLabel("position held");

        assertEquals("position held (P39)", source.describeProperty());
    }

    @Test void aQualifierRemembersWhatItIsCalled() {
        FieldSourceMapping mapping = new FieldSourceMapping();
        mapping.qualifierPid("P580");
        assertEquals("P580", mapping.displayQualifier());

        mapping.qualifierLabel("start time");

        assertEquals("start time (P580)", mapping.displayQualifier());
    }

    @Test void anUnconfiguredPropertyDescribesNothingRatherThanEmptyParentheses() {
        assertEquals("", new StatementClassSource().describeProperty());
        assertEquals("", new FieldSourceMapping().displayQualifier());
    }

    /** The name survives copying, or it is lost the first time a model is duplicated. */
    @Test void bothNamesSurviveACopy() {
        StatementClassSource source = new StatementClassSource("OfficeHolding", "P39");
        source.propertyLabel("position held");
        assertEquals("position held (P39)", source.copy().describeProperty());

        GeneratedClassModel owner = new GeneratedClassModel("OfficeHolding");
        owner.addField("startDate", FieldType.STRING, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P580");
        owner.fields().getFirst().mapping().qualifierLabel("start time");

        assertEquals("start time (P580)",
                owner.copy().fields().getFirst().mapping().displayQualifier());
    }
}
