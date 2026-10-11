package wikidata.explore.codegen;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.OwnedComponents;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * An owned component carries its OWNER's qid (one Name per Person), so the mapper's
 * QID unification — which exists to collapse several WDO copies of the same entity —
 * must not treat the two as one instance. It used to: mapping the component found the
 * owner's instance under that qid and applied the component class's fields to it,
 * failing the whole load with "Can not get field Name.givenname on Person".
 */
class OwnedComponentMappingTest {

    @Test void aComponentAndItsOwnerMapToSeparateInstances() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        GeneratedFieldModel structuredName = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structuredName.entityClassName("Name");
        structuredName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        name.addField("givenName", FieldType.STRING, FieldCardinality.SINGLE)
                .mapping().propertyPid("P735");
        project.rootClass(person);
        project.addClass(name);
        wikidata.explore.model.OwnedClassSemantics.synchronizeOwnerReferences(project);

        WikidataDynamicObject source = new WikidataDynamicObject("Q42", "Douglas Adams");
        source.type("Person");
        source.typeKey("Person");
        OwnedComponents.apply(project, List.of(source), null, null);
        ((WikidataDynamicObject) source.get("structuredName")).put("givenName", "Douglas");

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(project)) {
            Object mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(List.of(source)).getFirst();

            Object component = read(mapped, "structuredName");
            assertNotNull(component, "the owned component must map to its own instance");
            assertNotSame(mapped, component,
                    "sharing the owner's qid must not collapse the two");
            assertEquals("Name", component.getClass().getSimpleName());
            assertEquals("Douglas", read(component, "givenName"));
            // Both still answer with the qid they legitimately share.
            assertEquals("Q42", ((objectview.Viewable) mapped).getIdentifier());
            assertEquals("Q42", ((objectview.Viewable) component).getIdentifier());
            assertEquals("Name@Person.structuredName",
                    ((objectview.Viewable) component).identityTypeName(),
                    "an owned instance keeps its production site in its identity");
            Object ownerReference = read(component, "owner");
            assertNotNull(ownerReference);
            assertEquals("Person",
                    ((objectview.Viewable) ownerReference).identityTypeName());
            assertEquals("Q42",
                    ((objectview.Viewable) ownerReference).getIdentifier());
        }
    }

    @Test void aNamedOwnerRoleStillLinksToTheConcreteTopLevelOwner() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("astronomy");
        GeneratedClassModel constellation = project.rootClass();
        constellation.className("Constellation");
        GeneratedFieldModel constellationDiscovery = constellation.addField(
                "discovery", FieldType.ENTITY, FieldCardinality.SINGLE);
        constellationDiscovery.entityClassName("Discovery");
        constellationDiscovery.mapping().productionKind(
                FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel body = new GeneratedClassModel("CelestialBody");
        GeneratedFieldModel bodyDiscovery = body.addField(
                "discovery", FieldType.ENTITY, FieldCardinality.SINGLE);
        bodyDiscovery.entityClassName("Discovery");
        bodyDiscovery.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel discovery = new GeneratedClassModel("Discovery");
        discovery.ownedClass(true);
        project.addClass(body);
        project.addClass(discovery);
        project.addClass(new GeneratedClassModel("Owner"));
        wikidata.explore.model.OwnedClassSemantics.synchronizeOwnerReferences(project);
        project.representationClasses(project.findClass("Owner"),
                List.of("Constellation", "CelestialBody"));
        org.junit.jupiter.api.Assertions.assertTrue(
                wikidata.explore.model.GeneratedProjectModelValidator
                        .validate(project).valid());

        WikidataDynamicObject source = new WikidataDynamicObject("Q1", "Andromeda");
        source.type("Constellation");
        source.typeKey("Constellation");
        OwnedComponents.apply(project, List.of(source), null, null);

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(project)) {
            List<objectview.Viewable> mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(List.of(source));
            objectview.Viewable mappedOwner = mapped.getFirst();
            objectview.Viewable mappedDiscovery = (objectview.Viewable)
                    read(mappedOwner, "discovery");

            assertEquals("Owner", discovery.fields().stream()
                    .filter(field -> field.name().equals("owner"))
                    .findFirst().orElseThrow().entityClassName());
            assertSame(mappedOwner, read(mappedDiscovery, "owner"),
                    "the role reference navigates to the original concrete owner card");
            assertEquals("Constellation", ((objectview.Viewable)
                    read(mappedDiscovery, "owner")).identityTypeName());
        }
    }

    /** Reads a declared field whatever case the generator gave it. */
    private static Object read(Object owner, String field) {
        objectview.field.FieldSet set = ((objectview.Viewable) owner).fields();
        for (objectview.field.FieldRef ref : set.fields()) {
            if (ref.name().equalsIgnoreCase(field)) return set.read(ref.name());
        }
        return null;
    }
}
