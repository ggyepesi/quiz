package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OwnedComponentsTest {

    /** A part takes the owner's IDENTITY, and a name saying WHOSE view it is and WHICH
     *  view. The owner's label alone would claim the part IS the owner — a claim its own
     *  fields can contradict, as a person known as Elia Kazan whose name parts are Elias
     *  Kazantzoglou — and a card whose only field holds a same-named child drops its own
     *  title, which is how the owner lost its heading. */
    @Test void aPartIsNamedByItsOwnerAndItsSite() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = entity("Q42", "Douglas Adams", "Person");

        OwnedComponents.apply(project, List.of(person), null, null);

        WikidataDynamicObject name = (WikidataDynamicObject) person.get("structuredName");
        assertEquals("Q42", name.getIdentifier(), "the owner's identity");
        assertEquals("Douglas Adams — Structured Name", name.getDisplayName());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                person.getDisplayName(), name.getDisplayName(),
                "distinct from the owner's own name, or the owner's title is suppressed");
        org.junit.jupiter.api.Assertions.assertTrue(name.isPart(),
                "and it is a part: never served as a dataset of its own");
    }

    @Test void ownerFieldCreatesASeparateSameQidComponent() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = entity("Q42", "Douglas Adams", "Person");

        OwnedComponents.Result result = OwnedComponents.apply(
                project, List.of(person), null, null);

        assertEquals(1, result.created());
        WikidataDynamicObject name = (WikidataDynamicObject) person.get("structuredName");
        assertEquals("Q42", name.getIdentifier());
        assertEquals("Name", name.typeName());
        assertEquals("Name@Person.structuredName", name.typeKey());
        assertSame(name, result.components().get(0));
        assertEquals(MembershipPattern.OWNED_COMPONENT,
                MembershipPattern.of(project.findClass("Name"), project));
    }

    @Test void subclassInstancesUseTheDeclaringOwnerSiteOnlyOnce() {
        GeneratedProjectModel project = project();
        GeneratedClassModel employee = new GeneratedClassModel("Employee");
        employee.baseClassName("Person");
        project.addClass(employee);
        WikidataDynamicObject value = entity("Q1", "Ada", "Employee");

        OwnedComponents.Result result = OwnedComponents.apply(
                project, List.of(value), null, null);

        assertEquals(1, result.components().size());
        assertEquals("Name@Person.structuredName",
                ((WikidataDynamicObject) value.get("structuredName")).typeKey());
    }

    @Test void remapCopiesPreviouslyLoadedComponentFields() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject previousPerson = entity("Q42", "Douglas", "Person");
        WikidataDynamicObject previousName = entity("Q42", "Douglas", "Name");
        previousName.typeKey("Name@Person.structuredName");
        previousName.put("givenName", entity("Q1", "Douglas", "GivenName"));
        previousPerson.put("structuredName", previousName);
        WikidataDynamicObject freshPerson = entity("Q42", "Douglas", "Person");

        OwnedComponents.apply(project, List.of(freshPerson),
                List.of(previousPerson), null);

        WikidataDynamicObject remapped =
                (WikidataDynamicObject) freshPerson.get("structuredName");
        assertEquals("Douglas",
                ((WikidataDynamicObject) remapped.get("givenName")).getDisplayName());
    }

    /** A component owns a component in turn. ONE pass must materialize the whole chain:
     *  when the candidates were snapshotted before the first component existed, the
     *  nested one appeared only on the next generation, whose pool already held its
     *  owner — present after a remap, missing after a generate. */
    @Test void aComponentOwnedByAComponentIsMaterializedInTheSamePass() {
        GeneratedProjectModel project = project();
        GeneratedFieldModel nested = project.findClass("Name").addField(
                "pronunciation", FieldType.ENTITY, FieldCardinality.SINGLE);
        nested.entityClassName("Pronunciation");
        nested.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel pronunciationClass =
                new GeneratedClassModel("Pronunciation");
        pronunciationClass.ownedClass(true);
        project.addClass(pronunciationClass);
        WikidataDynamicObject person = entity("Q42", "Douglas Adams", "Person");

        OwnedComponents.Result result = OwnedComponents.apply(
                project, List.of(person), null, null);

        WikidataDynamicObject name = (WikidataDynamicObject) person.get("structuredName");
        WikidataDynamicObject pronunciation =
                (WikidataDynamicObject) name.get("pronunciation");
        assertEquals(2, result.created());
        assertEquals("Pronunciation@Name.pronunciation", pronunciation.typeKey());
        // The owner's identity runs the whole way down: every component in a chain
        // loads its properties from the same entity.
        assertEquals("Q42", pronunciation.getIdentifier());
    }

    /** Identity is invariant along a chain and a site produces one component per
     *  identity, so the rounds converge even on a model the validator would reject. */
    @Test void aCyclicOwnershipModelStillTerminates() {
        GeneratedProjectModel project = project();
        GeneratedFieldModel birthName = project.findClass("Name").addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        birthName.entityClassName("Name");
        birthName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);

        OwnedComponents.Result result = OwnedComponents.apply(
                project, List.of(entity("Q42", "Douglas Adams", "Person")), null, null);

        // Name@Person.structuredName, then Name@Name.birthName once — coming round
        // again, the site finds its own component under the same identity.
        assertEquals(2, result.created());
        assertEquals(2, result.components().size(),
                "a reused component is reported once even when revisited by a later round");
    }

    /** The part's name is DERIVED from the owner's label and the site. A repaired label
     *  must reach it: a derived value that never refreshes is stale data wearing the
     *  appearance of current data. */
    @Test void aReusedPartFollowsARepairedOwnerLabel() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = entity("Q42", "Duglas Adams", "Person");
        OwnedComponents.apply(project, List.of(person), null, null);
        WikidataDynamicObject part =
                (WikidataDynamicObject) person.get("structuredName");
        assertEquals("Duglas Adams — Structured Name", part.getDisplayName());

        person.name("Douglas Adams");   // the label is corrected
        OwnedComponents.Result again = OwnedComponents.apply(
                project, List.of(person), null, null);

        assertEquals(0, again.created(), "the same component, not a second one");
        assertSame(part, person.get("structuredName"));
        assertEquals("Douglas Adams — Structured Name", part.getDisplayName());
    }

    /**
     * A generation composes owned values more than once — kinds settle, new owners
     * appear, and composition runs again over a pool that already holds parts. Each
     * owner must still end with ONE part per site: the run that lost this produced two
     * birth names for every person (13,726 for 6,853), because a pass in between had
     * rewritten the parts' type keys and the reuse lookup no longer matched.
     */
    @Test void composingAgainOverAPoolThatAlreadyHasPartsAddsNothing() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = entity("Q42", "Douglas Adams", "Person");
        java.util.List<WikidataDynamicObject> pool = new java.util.ArrayList<>(
                List.of(person));

        OwnedComponents.Result first = OwnedComponents.apply(project, pool, null, null);
        first.addTo(pool);
        Object part = person.get("structuredName");

        OwnedComponents.Result second = OwnedComponents.apply(project, pool, null, null);
        second.addTo(pool);

        assertEquals(0, second.created(), "the second pass finds the work already done");
        assertSame(part, person.get("structuredName"), "and leaves the same part in place");
        assertEquals(1, pool.stream().filter(WikidataDynamicObject::isPart).count(),
                "one part per owner, however many passes ran");
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        GeneratedFieldModel name = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        name.entityClassName("Name");
        name.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel component = new GeneratedClassModel("Name");
        component.ownedClass(true);
        GeneratedFieldModel given = component.addField(
                "givenName", FieldType.ENTITY, FieldCardinality.SINGLE);
        given.entityClassName("GivenName");
        given.mapping().propertyPid("P735");
        project.rootClass(person);
        project.addClass(component);
        return project;
    }

    private static WikidataDynamicObject entity(String id, String name, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, name);
        value.type(type);
        value.typeKey(type);
        return value;
    }
}
