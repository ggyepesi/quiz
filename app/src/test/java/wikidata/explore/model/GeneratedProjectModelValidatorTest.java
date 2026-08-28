package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModelValidator.ValidationResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedProjectModelValidatorTest {

    // The invariant, not an example of it: validation must accept exactly what
    // generation can resolve. These drifted once — the resolver preferred a property
    // match and inverted, while validation counted class references alone and called
    // the same model ambiguous, so a model that generated correctly failed to load.
    @Test void validationAcceptsExactlyWhatGenerationCanResolve() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        var held = person.addField("held", FieldType.ENTITY, FieldCardinality.SINGLE);
        held.entityClassName("Position");
        held.mapping().propertyPid("P39");
        var sought = person.addField("sought", FieldType.ENTITY, FieldCardinality.SINGLE);
        sought.entityClassName("Position");
        sought.mapping().propertyPid("P999");
        project.addClass(person);
        project.rootClass(person);

        GeneratedClassModel position = new GeneratedClassModel("Position");
        var holders = position.addField(
                "holders", FieldType.ENTITY, FieldCardinality.COLLECTION);
        holders.entityClassName("Person");
        holders.mapping().productionKind(FieldProductionKind.INVERT);
        holders.mapping().propertyPid("P39");
        project.addClass(position);

        assertEquals(1, wikidata.explore.transform.ModelInverts.derive(project).size(),
                "the property match resolves both references to one forward field");
        assertFalse(GeneratedProjectModelValidator.validate(project).format()
                        .contains("choose the exact inverse field"),
                "so validation must not call it ambiguous");

        // Remove what disambiguated them and both sides must refuse together.
        held.mapping().propertyPid("P999");
        assertTrue(wikidata.explore.transform.ModelInverts.derive(project).isEmpty());
        assertTrue(GeneratedProjectModelValidator.validate(project).format()
                .contains("choose the exact inverse field"));
    }

    @Test void ambiguousInverseRequiresTheExactForwardField() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel offices = new GeneratedFieldModel(
                "offices", FieldType.ENTITY, FieldCardinality.COLLECTION);
        offices.entityClassName("OfficeHolding");
        offices.mapping().productionKind(FieldProductionKind.INVERT);
        person.fields().add(offices);

        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.fields().add(reference("source", "Person"));
        holding.fields().add(reference("predecessor", "Person"));

        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(person);
        project.addClass(holding);

        ValidationResult ambiguous = GeneratedProjectModelValidator.validate(project);
        assertTrue(ambiguous.format().contains("choose the exact inverse field"),
                ambiguous.format());

        offices.mapping().inverseField("source");
        assertFalse(GeneratedProjectModelValidator.validate(project).format()
                .contains("choose the exact inverse field"));
    }

    private static GeneratedFieldModel reference(String name, String target) {
        GeneratedFieldModel field = new GeneratedFieldModel(
                name, FieldType.ENTITY, FieldCardinality.SINGLE);
        field.entityClassName(target);
        return field;
    }

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
        name.ownedClass(true);
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
        name.ownedClass(true);
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
        name.ownedClass(true);
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

    @Test void explicitOwnedClassIsConfiguredBeforeItHasAProducingField() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.addClass(name);

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertTrue(result.valid(), result.format());
        assertEquals(MembershipPattern.OWNED_COMPONENT,
                MembershipPattern.of(name, project));
        assertTrue(MembershipPattern.describe(name, project)
                .contains("no producing field yet"));
    }

    @Test void makingAClassOwnedClearsItsIndependentPopulation() {
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.instanceMapping().propertyPid("P31");
        name.instanceMapping().sourceQid("Q5");
        name.seedQids().add("Q42");

        name.ownedClass(true);

        assertTrue(name.instanceMapping().propertyPid().isBlank());
        assertTrue(name.instanceMapping().sourceQid().isBlank());
        assertTrue(name.seedQids().isEmpty());
    }

    @Test void ownedClassExtendsFieldsButNotTheBasePopulation() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel base = new GeneratedClassModel("BaseName");
        base.ownedClass(true);
        base.addField("language", FieldType.ENTITY, FieldCardinality.SINGLE);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.baseClassName("BaseName");
        name.ownedClass(true);
        project.addClass(base);
        project.addClass(name);

        assertEquals(1, name.effectiveFields(project).size());
        assertTrue(name.effectiveInstanceMapping(project).sourceQid().isBlank());
        assertTrue(GeneratedProjectModelValidator.validate(project).valid());
    }

    @Test void ownedClassCannotExtendAStatementClass() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel statement = new GeneratedClassModel("Nomination");
        statement.statementSource(new StatementClassSource("Films", "P1411"));
        GeneratedClassModel owned = new GeneratedClassModel("Name");
        owned.baseClassName("Nomination");
        owned.ownedClass(true);
        project.addClass(statement);
        project.addClass(owned);

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(problem ->
                problem.message().contains("only another Owned class")), result.format());
    }

    @Test void ownedClassCannotExtendASourceClass() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(new GeneratedClassModel("BaseName"));
        GeneratedClassModel owned = new GeneratedClassModel("Name");
        owned.baseClassName("BaseName");
        owned.ownedClass(true);
        project.addClass(owned);

        ValidationResult result = GeneratedProjectModelValidator.validate(project);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(problem ->
                problem.message().contains("only another Owned class")), result.format());
    }
}
