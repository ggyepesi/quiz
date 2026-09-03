package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipPatternTest {

    private static GeneratedClassModel clazz() {
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("X");
        return c;
    }

    @Test void multiTargetRelation() {
        GeneratedClassModel c = clazz();
        c.instanceMapping().propertyPid("P1411");
        c.instanceMapping().additionalTypeQids().add("Q102427");
        c.instanceMapping().additionalTypeQids().add("Q103916");
        assertEquals(MembershipPattern.MULTI_TARGET_RELATION, MembershipPattern.of(c));
        assertEquals("Multi-target relation (P1411 → 2)", MembershipPattern.describe(c));
    }

    @Test void multiType() {
        GeneratedClassModel c = clazz();
        c.instanceMapping().propertyPid("P31");
        c.instanceMapping().sourceQid("Q523");
        c.instanceMapping().additionalTypeQids().add("Q6243");
        assertEquals(MembershipPattern.MULTI_TYPE, MembershipPattern.of(c));
    }

    @Test void singleType() {
        GeneratedClassModel c = clazz();
        c.instanceMapping().propertyPid("P31");
        c.instanceMapping().sourceQid("Q5");
        assertEquals(MembershipPattern.SINGLE_TYPE, MembershipPattern.of(c));
        assertEquals("Single type (Q5)", MembershipPattern.describe(c));
    }

    @Test void singleTargetRelation() {
        GeneratedClassModel c = clazz();
        c.instanceMapping().propertyPid("P166");
        c.instanceMapping().sourceQid("Q35637");
        assertEquals(MembershipPattern.SINGLE_TARGET_RELATION, MembershipPattern.of(c));
    }

    @Test void seededWhenNoRelation() {
        GeneratedClassModel c = clazz();
        c.seedQids().add("Q1");
        c.seedQids().add("Q2");
        assertEquals(MembershipPattern.SEEDED, MembershipPattern.of(c));
    }

    @Test void unconfigured() {
        assertEquals(MembershipPattern.UNCONFIGURED, MembershipPattern.of(clazz()));
    }

    @Test void referencedOnlyClassIsDerivedFromTheFieldThatTargetsIt() {
        GeneratedProjectModel p = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        GeneratedFieldModel forWork =
                nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE);
        forWork.entityClassName("ForWork");
        forWork.mapping().qualifierPid("P1686");
        p.addClass(nom);

        GeneratedClassModel forWorkClass = new GeneratedClassModel("ForWork");
        p.addClass(forWorkClass);
        p.rootClass(nom);

        // Bare, no membership of its own -> UNCONFIGURED without project context...
        assertEquals(MembershipPattern.UNCONFIGURED, MembershipPattern.of(forWorkClass));
        // ...but REFERENCED once we can see the field that targets it.
        assertEquals(MembershipPattern.REFERENCED,
                MembershipPattern.of(forWorkClass, p));
        assertEquals("Derived from Nomination.forWork (P1686)",
                MembershipPattern.describe(forWorkClass, p));
    }

    @Test void reifiedDiscoveredWithSelection() {
        GeneratedClassModel c = clazz();
        StatementClassSource s = new StatementClassSource("P1411");
        s.valueSelectionName("OscarCategories");
        c.statementSource(s);
        assertEquals(MembershipPattern.REIFIED, MembershipPattern.of(c));
        assertEquals("Reified statements (P1411 · subjects found from the property itself → Selection 'OscarCategories')",
                MembershipPattern.describe(c));
    }

    @Test void reifiedFromSourceClass() {
        GeneratedClassModel c = clazz();
        c.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        assertEquals(MembershipPattern.REIFIED, MembershipPattern.of(c));
        assertEquals("Reified statements (P1411 of OscarNominations)",
                MembershipPattern.describe(c));
    }
    /** A kind class has no membership query by design — its members are ASSIGNED by
     *  evidence — so reading "Unconfigured" would say the opposite of the truth and
     *  leave Apply with no visible trace anywhere on the class. */
    @Test void anEvidenceRuleConfiguresAClassThatQueriesNothing() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        project.addClass(person);

        assertEquals(MembershipPattern.UNCONFIGURED,
                MembershipPattern.of(person, project));

        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));

        assertEquals(MembershipPattern.EVIDENCE_KIND,
                MembershipPattern.of(person, project));
        assertEquals("Evidence-derived kind (P31 = Q5)",
                MembershipPattern.describe(person, project));
    }

    @Test void theDescriptionNamesTheFirstEvidenceValuesAndCountsTheRest() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel film = new GeneratedClassModel("Film");
        project.addClass(film);
        project.addEntityKindRule(new EntityKindRule("Film",
                List.of("Q11424", "Q24862", "Q17517379", "Q202866", "Q506240")));

        assertEquals("Evidence-derived kind (P31 = Q11424, Q24862, Q17517379, +2)",
                MembershipPattern.describe(film, project));
    }

    /** Being a field's target drives role inference and referent loads; an evidence
     *  rule does not, so REFERENCED still wins when a class is both. */
    @Test void aFieldTargetStaysReferencedEvenWithAKindRule() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        project.addClass(person);
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");
        project.addClass(nomination);
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));

        assertEquals(MembershipPattern.REFERENCED,
                MembershipPattern.of(person, project));
    }

    @Test void queriedMembershipDescriptionWinsOverAnIncidentalKindRule() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        project.addClass(person);
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));

        assertEquals(MembershipPattern.SINGLE_TYPE,
                MembershipPattern.of(person, project));
        assertEquals("Single type (Q5)",
                MembershipPattern.describe(person, project));
    }

    /**
     * Where a class's instances come from is a fact about roles, not about which field
     * happens to be declared first. History's Person was reported as "Derived from
     * Person.spouse (P26)" — arbitrary, because Person is the first class and spouse its
     * first entity field pointing back, and circular, because a self-reference
     * presupposes the population it claims to explain. The P39 subject is what actually
     * produces those people.
     */
    @Test void derivationPrefersTheStatementSubjectOverASelfReference() {
        GeneratedProjectModel project = new GeneratedProjectModel();

        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel spouse = person.addField(
                "spouse", datasource.schema.FieldType.ENTITY, FieldCardinality.SINGLE);
        spouse.entityClassName("Person");
        spouse.mapping().propertyPid("P26");
        project.addClass(person);
        project.rootClass(person);

        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.seedQids().add("Q6412254");
        project.addClass(position);

        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        GeneratedFieldModel subject = holding.addField(
                "source", datasource.schema.FieldType.ENTITY, FieldCardinality.SINGLE);
        subject.entityClassName("Person");
        subject.mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        GeneratedFieldModel held = holding.addField(
                "position", datasource.schema.FieldType.ENTITY, FieldCardinality.SINGLE);
        held.entityClassName("Position");
        held.mapping().propertyPid("P39");
        project.addClass(holding);

        var derived = MembershipPattern.derivedFrom(person, project);

        assertEquals("OfficeHolding", derived.ownerClass());
        assertEquals("source", derived.fieldName());
        assertEquals("P39", derived.pid(),
                "the subject reads no property of its own, so the statement's is shown");
        assertTrue(MembershipPattern.describe(person, project)
                        .startsWith("Derived from OfficeHolding.source (P39)"),
                MembershipPattern.describe(person, project));
    }
}
