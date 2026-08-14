package wikidata.explore.codegen;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.OwnedComponents;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

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
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        GeneratedFieldModel structuredName = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structuredName.entityClassName("Name");
        structuredName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.addField("givenName", FieldType.STRING, FieldCardinality.SINGLE)
                .mapping().propertyPid("P735");
        project.rootClass(person);
        project.addClass(name);

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
