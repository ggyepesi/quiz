package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The checked-in Oscars model keeps name structure separate from name roles. */
class OscarsNameModelTest {

    @Test void modelsStructuredAndAttributedNamesSeparately() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(new File(
                "../data/wikidata/oscarnominations/oscarnominations.model.json"));

        GeneratedClassModel person = model.findClass("Person");
        GeneratedClassModel name = model.findClass("Name");
        assertNotNull(person);
        assertNotNull(name);
        assertTrue(name.ownedClass());

        GeneratedFieldModel structured = field(person, "structuredName");
        assertNotNull(structured);
        assertEquals("Name", structured.entityClassName());
        assertEquals(FieldProductionKind.OWNED_COMPONENT,
                structured.mapping().productionKind());
        assertEquals("P1477", field(person, "birthName").mapping().propertyPid());
        assertEquals("P1559", field(person, "nativeName").mapping().propertyPid());
        assertEquals("P742", field(person, "pseudonyms").mapping().propertyPid());

        assertEquals("P735", field(name, "givenName").mapping().propertyPid());
        assertEquals("P734", field(name, "familyName").mapping().propertyPid());
    }

    private static GeneratedFieldModel field(GeneratedClassModel clazz, String name) {
        return clazz.fields().stream().filter(candidate -> name.equals(candidate.name()))
                .findFirst().orElseThrow();
    }
}
