package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StatementFieldSemanticsTest {

    @org.junit.jupiter.api.condition.EnabledIf("nobelModelPresent")
    @Test void savedNobelModelHasANonEmptyDerivedIdentityProposal() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                new java.io.File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
        GeneratedClassModel award = model.findClass("LaureatesWithMotivation");

        assertEquals(java.util.List.of("category", "year"),
                StatementCanonicalDefaults.suggest(award),
                "Re-derive must never present the saved Nobel statement class with "
                        + "an empty proposal");
    }

    static boolean nobelModelPresent() {
        return new java.io.File(
                "../data/wikidata/nobelprizes/nobelprizes.model.json").isFile();
    }

    /**
     * Participants were briefly admitted to the key on the theory that the set is a
     * stable natural grain. Nobel disproved it: 393 award statements name no co-laureate
     * at all, so the "set" is whatever one statement happened to list, and identifying by
     * it split shares that belong together. Participants are unioned by the duplicate
     * policy instead — one mechanism for the question, not two.
     */
    @Test void participantsDoNotIdentifyAStatement() {
        GeneratedFieldModel participants = new GeneratedFieldModel(
                "laureates", FieldType.ENTITY, FieldCardinality.COLLECTION);
        participants.mapping().productionKind(FieldProductionKind.STATEMENT_PARTICIPANTS);

        org.junit.jupiter.api.Assertions.assertFalse(
                StatementFieldSemantics.isCanonicalKeyCandidate(participants),
                "a collection never identifies, participants included");
    }

    private static GeneratedClassModel reifyingClass() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(
                new StatementClassSource("OscarNominations", "P1411"));
        return nomination;
    }

    private static GeneratedFieldModel qualifier(
            GeneratedClassModel owner,
            String name,
            FieldType type,
            FieldCardinality cardinality,
            String qualifierPid) {

        GeneratedFieldModel field = owner.addField(name, type, cardinality);
        field.mapping().qualifierPid(qualifierPid);
        return field;
    }

    @Test
    void derivedFieldsAreNotRuntimeStatementFields() {
        GeneratedClassModel nom = reifyingClass();
        GeneratedFieldModel won =
                nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE);
        won.mapping().productionKind(FieldProductionKind.COMPANION_MATCH);

        assertFalse(StatementFieldSemantics.isRuntimeStatementField(won),
                "a COMPANION_MATCH field is produced after reify");
        assertFalse(StatementFieldSemantics.isCanonicalKeyCandidate(won),
                "and so must never enter the canonical key");
    }

    @Test
    void collectionScalarAutoFieldIsAKeyCandidateButCollectionIsNot() {
        GeneratedClassModel nom = reifyingClass();
        GeneratedFieldModel category =
                nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        GeneratedFieldModel others =
                nom.addField("others", FieldType.ENTITY, FieldCardinality.COLLECTION);

        assertTrue(StatementFieldSemantics.isCanonicalKeyCandidate(category));
        assertFalse(StatementFieldSemantics.isCanonicalKeyCandidate(others),
                "a collection field can't be part of a natural key");
    }

    @Test
    void statementDefaultKeyIsValuePlusScalarEntityAndDateQualifiers() {
        GeneratedClassModel nom = reifyingClass();
        GeneratedFieldModel category =
                nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        qualifier(nom, "nominee", FieldType.ENTITY,
                FieldCardinality.SINGLE, "P2453");
        qualifier(nom, "year", FieldType.DATE,
                FieldCardinality.SINGLE, "P585");
        qualifier(nom, "nominees", FieldType.ENTITY,
                FieldCardinality.COLLECTION, "P2453");
        GeneratedFieldModel won =
                nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE);
        won.mapping().productionKind(FieldProductionKind.COMPANION_MATCH);

        assertEquals(
                java.util.List.of("category", "nominee", "year"),
                StatementCanonicalDefaults.suggest(nom));
    }

    @Test
    void statementDefaultDisplayFieldIsFirstSingleValuedNonDerivedField() {
        GeneratedClassModel nom = reifyingClass();
        // A collection can't be the display; the value comes next and wins.
        nom.addField("nominees", FieldType.ENTITY, FieldCardinality.COLLECTION);
        GeneratedFieldModel category =
                nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        GeneratedFieldModel won =
                nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE);
        won.mapping().productionKind(FieldProductionKind.COMPANION_MATCH);

        assertEquals("category",
                StatementCanonicalDefaults.suggestDisplayField(nom),
                "a reified statement has no label; the first single-valued "
                        + "non-derived field is proposed instead");
    }

    @Test
    void replaceWithSuggestionMaterializesKeyAndDisplay() {
        GeneratedClassModel nom = reifyingClass();
        GeneratedFieldModel category =
                nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        qualifier(nom, "nominee", FieldType.ENTITY,
                FieldCardinality.SINGLE, "P2453");

        StatementCanonicalDefaults.replaceWithSuggestion(nom);

        CanonicalSpec spec = nom.canonical();
        assertEquals(java.util.List.of("category", "nominee"), spec.keyFields());
        assertEquals(CanonicalSpec.DisplayNameMode.FIELD, spec.displayNameMode());
        assertEquals("category", spec.displayNameField());
    }

    @Test
    void dateAndCollectionStatementValuesAreNotDefaultKeyFields() {
        GeneratedClassModel dateStatement = reifyingClass();
        GeneratedFieldModel date = dateStatement.addField(
                "date", FieldType.DATE, FieldCardinality.SINGLE);
        date.mapping().propertyPid("P1411");

        GeneratedClassModel collectionStatement = reifyingClass();
        GeneratedFieldModel values = collectionStatement.addField(
                "values", FieldType.ENTITY, FieldCardinality.COLLECTION);
        values.mapping().propertyPid("P1411");

        assertTrue(StatementCanonicalDefaults.suggest(dateStatement).isEmpty(),
                "DATE values are attributes unless explicitly selected");
        assertTrue(StatementCanonicalDefaults.suggest(collectionStatement).isEmpty(),
                "a collection cannot be materialized as a canonical key field");
    }

    @Test
    void updatingSuggestedKeyDoesNotReplaceCustomDisplay() {
        GeneratedClassModel nom = reifyingClass();
        GeneratedFieldModel category = nom.addField(
                "category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        nom.canonical()
                .displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE)
                .displayNameTemplate("{category} · custom");

        StatementCanonicalDefaults.replaceKeyWithSuggestion(nom);

        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE,
                nom.canonical().displayNameMode());
        assertEquals("{category} · custom",
                nom.canonical().displayNameTemplate());
    }

    @Test
    void missingQualifierPolicyOnlyAppliesToAScalarEntityQualifier() {
        GeneratedClassModel nom = reifyingClass();

        GeneratedFieldModel entityQualifier =
                qualifier(nom, "edition", FieldType.ENTITY,
                        FieldCardinality.SINGLE, "P805");
        assertTrue(StatementFieldSemantics
                .supportsMissingQualifierPolicy(nom, entityQualifier));

        GeneratedFieldModel dateQualifier =
                qualifier(nom, "year", FieldType.DATE,
                        FieldCardinality.SINGLE, "P585");
        assertFalse(StatementFieldSemantics
                        .supportsMissingQualifierPolicy(nom, dateQualifier),
                "a date qualifier has no scalar-entity fallback semantics");

        GeneratedFieldModel entityCollection =
                qualifier(nom, "recipients", FieldType.ENTITY,
                        FieldCardinality.COLLECTION, "P1686");
        assertFalse(StatementFieldSemantics
                        .supportsMissingQualifierPolicy(nom, entityCollection),
                "a collection already represents zero-or-more values");

        GeneratedFieldModel notAQualifier =
                nom.addField("value", FieldType.ENTITY, FieldCardinality.SINGLE);
        assertFalse(StatementFieldSemantics
                        .supportsMissingQualifierPolicy(nom, notAQualifier),
                "the statement value is not a qualifier");
    }

    @Test
    void policyOnANonReifyingClassIsNotSupported() {
        GeneratedClassModel plain = new GeneratedClassModel("Person");
        GeneratedFieldModel field =
                qualifier(plain, "born", FieldType.ENTITY,
                        FieldCardinality.SINGLE, "P569");

        assertFalse(StatementFieldSemantics
                        .supportsMissingQualifierPolicy(plain, field),
                "qualifiers only mean something on a statement class");
    }

    @Test
    void normalizeClearsAPolicyThatNoLongerApplies() {
        GeneratedClassModel nom = reifyingClass();
        GeneratedFieldModel year =
                qualifier(nom, "year", FieldType.DATE,
                        FieldCardinality.SINGLE, "P585");
        year.mapping().missingQualifierPolicy(
                MissingQualifierPolicy.STATEMENT_SUBJECT);

        boolean changed =
                StatementFieldSemantics.normalizeMissingQualifierPolicy(nom, year);

        assertTrue(changed, "a date qualifier can't carry a policy");
        assertNull(year.mapping().missingQualifierPolicy());
    }

    @Test
    void normalizeKeepsAValidPolicy() {
        GeneratedClassModel nom = reifyingClass();
        GeneratedFieldModel edition =
                qualifier(nom, "edition", FieldType.ENTITY,
                        FieldCardinality.SINGLE, "P805");
        edition.mapping().missingQualifierPolicy(
                MissingQualifierPolicy.STATEMENT_SUBJECT);

        boolean changed =
                StatementFieldSemantics.normalizeMissingQualifierPolicy(nom, edition);

        assertFalse(changed, "a scalar entity qualifier keeps its policy");
        assertTrue(edition.mapping().missingQualifierPolicy()
                == MissingQualifierPolicy.STATEMENT_SUBJECT);
    }

    /**
     * The subject is the field that reads NOTHING: not a qualifier, not the value, and
     * carrying no property of its own, because it is filled from the item the statement
     * sits on. Models built through the UI leave production kind AUTO, so a rule that
     * only honours an explicit mark answers "no subject" for real saved data.
     */
    @Test
    void theSubjectIsResolvedWithoutBeingMarked() {
        GeneratedClassModel nom = reifyingClass();
        GeneratedFieldModel subject =
                nom.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE);
        subject.entityClassName("Person");

        assertEquals("source",
                StatementFieldSemantics.statementSubjectFieldName(nom));
        assertTrue(StatementFieldSemantics.isStatementSubject(nom, subject));
    }

    @Test
    void anExplicitlyMarkedSubjectWins() {
        GeneratedClassModel nom = reifyingClass();
        nom.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE);
        GeneratedFieldModel marked =
                nom.addField("carrier", FieldType.ENTITY, FieldCardinality.SINGLE);
        marked.mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);

        assertEquals("carrier",
                StatementFieldSemantics.statementSubjectFieldName(nom),
                "the modeller's explicit answer is never overruled by the rule");
    }

    /** Two unmapped entity fields make the subject genuinely ambiguous. Guessing one
     *  would be silently wrong, so it is refused the way a missing value field is. */
    @Test
    void anAmbiguousSubjectIsRefusedRatherThanGuessed() {
        GeneratedClassModel nom = reifyingClass();
        nom.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE);
        nom.addField("alsoUnmapped", FieldType.ENTITY, FieldCardinality.SINGLE);

        assertEquals("", StatementFieldSemantics.statementSubjectFieldName(nom));
    }

    @Test
    void anOrdinaryClassHasNoSubject() {
        GeneratedClassModel plain = new GeneratedClassModel("Person");
        plain.addField("spouse", FieldType.ENTITY, FieldCardinality.SINGLE);

        assertEquals("", StatementFieldSemantics.statementSubjectFieldName(plain));
    }
}
