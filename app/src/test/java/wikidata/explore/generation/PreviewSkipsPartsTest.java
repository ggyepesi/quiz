package wikidata.explore.generation;

import wikidata.explore.model.EntityBound;
import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.*;
import wikidata.explore.transform.OwnedComponents;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A single-class preview asks "who is in this class, carrying what". Materializing a
 * component per owner answers neither: nothing on that path fetches their declared
 * properties, so every part comes out empty — 200 sampled people became 200 blank
 * BirthNames sitting beside them in the instances panel.
 */
class PreviewSkipsPartsTest {

    @Test void theSampleItselfProducesPartsOnlyWhenAskedTo() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = new WikidataDynamicObject("Q42", "Douglas Adams");
        person.type("Person");
        person.typeKey("Person");

        // What the preview path leaves undone…
        assertEquals(null, person.get("birthName"),
                "a sampled instance carries no part until something produces one");

        // …and what Generate domain / Enrich do, which is where the values come from.
        OwnedComponents.Result owned =
                OwnedComponents.apply(project, List.of(person), null, null);
        assertEquals(1, owned.created());
        assertTrue(((WikidataDynamicObject) person.get("birthName")).isPart());
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        GeneratedFieldModel site = person.addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("BirthName");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel birthName = new GeneratedClassModel("BirthName");
        birthName.ownedClass(true);
        project.rootClass(person);
        project.addClass(birthName);
        return project;
    }
}
