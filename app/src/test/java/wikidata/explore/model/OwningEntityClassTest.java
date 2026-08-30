package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A component's instances ARE the owner's entities, so everything asked of the entities
 * behind a component — property discovery, sampling, a DBpedia type lookup — has to be
 * asked of the class that supplies them. Without that, configuring Name.familyName found
 * no type to discover properties for, while P734 is a property of the Person one hop up.
 */
class OwningEntityClassTest {

    @Test void aComponentResolvesToTheClassThatOwnsIt() {
        GeneratedProjectModel project = project();

        GeneratedClassModel bearer = MembershipPattern.owningEntityClass(
                project.findClass("Name"), project);

        assertEquals("Person", bearer.className());
        assertEquals("Q5", bearer.instanceMapping().sourceQid());
    }

    @Test void aChainOfComponentsResolvesToTheEntityAtItsHead() {
        GeneratedProjectModel project = project();
        GeneratedClassModel name = project.findClass("Name");
        GeneratedFieldModel nested = name.addField(
                "pronunciation", FieldType.ENTITY, FieldCardinality.SINGLE);
        nested.entityClassName("Pronunciation");
        nested.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel pronunciation = new GeneratedClassModel("Pronunciation");
        pronunciation.ownedClass(true);
        project.addClass(pronunciation);

        assertEquals("Person", MembershipPattern.owningEntityClass(pronunciation, project)
                .className());
    }

    @Test void anOrdinaryClassIsItsOwnBearer() {
        GeneratedProjectModel project = project();

        assertEquals("Person", MembershipPattern.owningEntityClass(
                project.findClass("Person"), project).className());
    }

    /** Two sites on the SAME owner agree: what matters is the owner's kind, not how many
     *  fields produce the class. A person's full name and birth name are both a Person's. */
    @Test void twoSitesOnOneOwnerStillResolve() {
        GeneratedProjectModel project = project();
        GeneratedFieldModel second = project.findClass("Person").addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        second.entityClassName("Name");
        second.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = project.findClass("Name");

        assertEquals(2, MembershipPattern.ownedBy(name, project).size());
        assertEquals("Person",
                MembershipPattern.owningEntityClass(name, project).className());
        assertEquals("Owned class — produced at Person.nameValue, Person.birthName",
                MembershipPattern.describe(name, project));
    }

    /** Owners of DIFFERENT kinds disagree: no one type speaks for the class's fields, so
     *  the resolution reports nothing rather than taking whichever was declared first —
     *  and the validator rejects the model, so the UI never has to reason about it. */
    @Test void sitesOnDifferentKindsOfOwnerDoNotResolve() {
        GeneratedProjectModel project = project();
        GeneratedClassModel organisation = new GeneratedClassModel("Organisation");
        organisation.instanceMapping().sourceQid("Q43229");
        GeneratedFieldModel legalName = organisation.addField(
                "legalName", FieldType.ENTITY, FieldCardinality.SINGLE);
        legalName.entityClassName("Name");
        legalName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        project.addClass(organisation);
        GeneratedClassModel name = project.findClass("Name");

        assertEquals(java.util.List.of("Person", "Organisation"),
                MembershipPattern.owningEntityClasses(name, project).stream()
                        .map(GeneratedClassModel::className).toList());
        assertNull(MembershipPattern.owningEntityClass(name, project));

        GeneratedProjectModelValidator.ValidationResult result =
                GeneratedProjectModelValidator.validate(project);
        org.junit.jupiter.api.Assertions.assertTrue(
                result.problems().stream().anyMatch(problem ->
                        problem.message().contains("different kinds of entity")),
                result.format());
    }

    /** An owned class nobody owns has no entities behind it — and no cycle hangs it. */
    @Test void anUnownedComponentResolvesToNothing() {
        GeneratedProjectModel project = project();
        GeneratedClassModel orphan = new GeneratedClassModel("Orphan");
        orphan.ownedClass(true);
        project.addClass(orphan);

        assertNull(MembershipPattern.owningEntityClass(orphan, project));
    }

    /** An evidence kind is stamped, never queried, so it declares no membership type —
     *  but its rule says what those entities are, and that is what the discovery and
     *  sampling tools need. Person carries P31 = Q5 in a rule, not a sourceQid. */
    @Test void anEvidenceKindTakesItsTypeFromTheRuleThatStampsIt() {
        GeneratedProjectModel project = project();
        GeneratedClassModel person = project.findClass("Person");
        person.instanceMapping().sourceQid("");
        project.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));

        assertEquals(MembershipPattern.EVIDENCE_KIND,
                MembershipPattern.of(person, project));
        assertEquals("Q5", MembershipPattern.typeQid(person, project));
        // …and through a component, which is how Name.familyName reaches P734's subject.
        assertEquals("Q5", MembershipPattern.typeQid(
                MembershipPattern.owningEntityClass(project.findClass("Name"), project),
                project));
    }

    /** A declared membership type still wins: the rule is the fallback, not an override. */
    @Test void aDeclaredMembershipTypeIsPreferred() {
        GeneratedProjectModel project = project();
        GeneratedClassModel person = project.findClass("Person");
        person.instanceMapping().sourceQid("Q215627");
        project.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));

        assertEquals("Q215627", MembershipPattern.typeQid(person, project));
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        GeneratedFieldModel nameValue = person.addField(
                "nameValue", FieldType.ENTITY, FieldCardinality.SINGLE);
        nameValue.entityClassName("Name");
        nameValue.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.rootClass(person);
        project.addClass(name);
        return project;
    }
}
