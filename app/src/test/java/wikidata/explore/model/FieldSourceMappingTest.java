package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FieldSourceMappingTest {

    // Regression: copy() must carry the explicit reification policy.
    @Test void copyCarriesReifyOverrides() {
        FieldSourceMapping m = new FieldSourceMapping();
        m.missingQualifierPolicy(MissingQualifierPolicy.MISSING);
        m.roleKind(RoleKind.IDENTITY);
        m.qualifierDateMode(QualifierDateMode.DATE);

        FieldSourceMapping c = m.copy();

        assertEquals(MissingQualifierPolicy.MISSING, c.missingQualifierPolicy());
        assertEquals(RoleKind.IDENTITY, c.roleKind());
        assertEquals(QualifierDateMode.DATE, c.qualifierDateMode());
    }

    @Test void copyKeepsOptionalPolicyUnset() {
        FieldSourceMapping c = new FieldSourceMapping().copy();
        assertNull(c.missingQualifierPolicy());
    }

    @Test void anOldMappingDefaultsToTheLegacyYearProjection() {
        assertEquals(QualifierDateMode.YEAR,
                new FieldSourceMapping().qualifierDateMode());
    }

    // --- new field vs existing model: two different questions ----------------

    @Test void aNewDateFieldKeepsWhatTheSourceStates() {
        GeneratedClassModel cls = new GeneratedClassModel("Reign");
        GeneratedFieldModel start = cls.addField(
                "reignStart", FieldType.DATE, FieldCardinality.SINGLE);

        assertEquals(QualifierDateMode.DATE, start.mapping().qualifierDateMode(),
                "a field created now has no legacy projection to preserve");
    }

    @Test void aModelWrittenBeforeQualifierDatesStillMeansYear() {
        // The migration guarantee: absence on disk is YEAR, and it must stay so
        // however new fields behave.
        FieldSourceMapping fromDisk = new FieldSourceMapping();

        assertEquals(QualifierDateMode.YEAR, fromDisk.qualifierDateMode(),
                "an unset mode is what every model written before this feature has");
    }

    @Test void choosingYearWritesNothingSoAnUntouchedModelDoesNotChurn() {
        FieldSourceMapping m = new FieldSourceMapping();
        m.qualifierDateMode(QualifierDateMode.YEAR);

        assertEquals(QualifierDateMode.YEAR, m.qualifierDateMode());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper()
                        .valueToTree(m).has("qualifierDateMode"),
                "YEAR and unset mean the same thing, so YEAR stores nothing");
    }

    @Test void onlyDateFieldsCarryTheSetting() {
        GeneratedClassModel cls = new GeneratedClassModel("Reign");
        GeneratedFieldModel position = cls.addField(
                "position", FieldType.ENTITY, FieldCardinality.SINGLE);

        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper()
                        .valueToTree(position.mapping()).has("qualifierDateMode"),
                "a projection for dates is noise on a field that is not one");
    }
}
