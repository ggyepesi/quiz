package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementFieldSemanticsTest {

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
}
