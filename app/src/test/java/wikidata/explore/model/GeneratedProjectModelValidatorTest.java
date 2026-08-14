package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModelValidator.ValidationResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedProjectModelValidatorTest {

    private static GeneratedProjectModel oscarLikeProject() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscar-like");

        project.addClass(new GeneratedClassModel("OscarNominations"));

        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nomination.instanceMapping().propertyPid("P1411");
        nomination.addField("edition", FieldType.ENTITY, FieldCardinality.SINGLE)
                  .entityClassName("Edition");
        nomination.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        project.addClass(new GeneratedClassModel("Edition"));
        project.addClass(nomination);
        return project;
    }

    @Test
    void aBaseClassCycleIsAnError() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("cycle");
        GeneratedClassModel alpha = new GeneratedClassModel("Alpha");
        alpha.baseClassName("Beta");
        GeneratedClassModel beta = new GeneratedClassModel("Beta");
        beta.baseClassName("Alpha");
        project.addClass(alpha);
        project.addClass(beta);

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertFalse(result.valid());
        assertTrue(result.errors().stream()
                        .anyMatch(p -> p.message().contains("Base class cycle")),
                "the inheritance cycle is reported once: " + result.format());
    }

    @Test
    void unmodeledEntityRefIsAWarningNotABlockingError() {
        GeneratedProjectModel project = oscarLikeProject();
        // forWork -> ForWork, which is deliberately unmodeled (renders as a string).
        project.findClass("Nomination")
               .addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
               .entityClassName("ForWork");

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertTrue(result.valid(), "an unmodeled entity ref must not block the save");
        assertTrue(result.warnings().stream()
                         .anyMatch(p -> p.location().equals("Nomination.forWork")),
                "but it is surfaced as a warning so a real typo stays visible");
    }

    @Test
    void dottedTypedPathMatchValueFieldIsAccepted() {
        GeneratedProjectModel project = oscarLikeProject();
        // year = edition -> date.year: a typed projection, not a flat field of
        // Nomination — the structural validator must not try to resolve it locally.
        GeneratedFieldModel year = project.findClass("Nomination")
                .addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().qualifierPid("P585");
        year.mapping().subjectField("edition");
        year.mapping().matchValueField("date.year");
        year.mapping().matchRoleField("category");

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertTrue(result.valid(),
                "a dotted typed-path match value field is valid: " + result.format());
    }

    // Enforcement that a fallback policy only applies to a scalar entity qualifier
    // lives in the reification runtime (ModelStatementReifications.fallbackRoles),
    // not the validator — the validator only requires a qualifier PID. The
    // scalar-entity rule itself is covered by StatementFieldSemanticsTest.

    @Test
    void aMissingQualifierPolicyOnAScalarEntityQualifierIsAccepted() {
        GeneratedProjectModel project = oscarLikeProject();
        GeneratedFieldModel edition = project.findClass("Nomination")
                .addField("edition2", FieldType.ENTITY, FieldCardinality.SINGLE);
        edition.mapping().qualifierPid("P805");
        edition.mapping().missingQualifierPolicy(
                MissingQualifierPolicy.STATEMENT_SUBJECT);

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertTrue(result.valid(),
                "a scalar entity qualifier may carry a policy: " + result.format());
    }

    @Test
    void aFlatUnknownMatchValueFieldStillErrors() {
        GeneratedProjectModel project = oscarLikeProject();
        // Only dotted paths are exempt — a plain misspelled field is still an error.
        GeneratedFieldModel year = project.findClass("Nomination")
                .addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().qualifierPid("P585");
        year.mapping().matchValueField("nope");

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertFalse(result.valid(),
                "a flat unknown match value field remains a blocking error");
    }

    @Test void aValidOwnedComponentNeedsNoOwnerConfigurationOnTheTarget() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        GeneratedFieldModel field = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        field.entityClassName("Name");
        field.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.addField("givenName", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P735");
        project.addClass(person);
        project.addClass(name);

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertTrue(result.valid(), result.format());
        assertTrue(MembershipPattern.describe(name, project)
                .contains("Person.structuredName"));
    }

    @Test void extensionAndOwnedCompositionOfTheSameClassIsRejected() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel name = new GeneratedClassModel("Name");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.baseClassName("Name");
        GeneratedFieldModel field = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        field.entityClassName("Name");
        field.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        project.addClass(name);
        project.addClass(person);

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(problem ->
                problem.message().contains("already is a Name")), result.format());
    }

    @Test void ownedSelfCycleHasOneClearDiagnostic() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel name = new GeneratedClassModel("Name");
        GeneratedFieldModel field = name.addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        field.entityClassName("Name");
        field.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        project.addClass(name);

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertFalse(result.valid());
        assertEquals(1, result.errors().stream()
                .filter(problem -> problem.message().contains("Owned-component cycle"))
                .count(), result.format());
        assertFalse(result.errors().stream().anyMatch(problem ->
                problem.message().contains("through class extension")), result.format());
    }
}
