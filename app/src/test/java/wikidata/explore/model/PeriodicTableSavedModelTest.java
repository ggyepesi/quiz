package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Periodic Table's named families are Wikidata entities reached from elements,
 * not eleven parallel classes whose authored aliases duplicate those entity names. */
class PeriodicTableSavedModelTest {

    private static final File MODEL =
            new File("../data/wikidata/periodictable/periodictable.model.json");
    private static final List<String> FAMILY_QIDS = List.of(
            "Q19557", "Q19563", "Q19588", "Q189294", "Q106693", "Q106675",
            "Q104567", "Q19605", "Q19609", "Q19569", "Q19577");

    @Test void elementFamiliesAreOneBoundedReferenceVocabulary() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(MODEL);
        GeneratedClassModel element = model.findClass("Element");
        GeneratedClassModel family = model.findClass("ElementFamily");
        GeneratedFieldModel partOf = element.fields().stream()
                .filter(field -> field.name().equals("partOf"))
                .findFirst().orElseThrow();

        assertEquals(List.of("Element", "ElementFamily"),
                model.classes().stream().map(GeneratedClassModel::className).toList());
        assertNotNull(family);
        assertEquals(EntityBound.Kind.UNBOUNDED, family.membership().kind());
        assertTrue(family.fields().isEmpty());
        assertEquals(FieldType.ENTITY, partOf.type());
        assertEquals(FieldCardinality.COLLECTION, partOf.cardinality());
        assertEquals("ElementFamily", partOf.entityClassName());
        assertEquals(family.declarationId(), partOf.entityDeclarationId());
        assertEquals("P361", partOf.mapping().propertyPid());
        assertEquals(RuleDirection.ROOT_TO_ITEM, partOf.mapping().direction());
        assertEquals(FAMILY_QIDS, List.copyOf(partOf.mapping().allowedQids()));

        assertNull(model.findClass("AlkaliMetal"));
        assertNull(model.findClass("TransitionMetal"));
        assertNull(model.findClass("NobleGas"));
    }

    @Test void elementPopulationAndInfoboxFieldsStayBounded() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(MODEL);
        GeneratedClassModel element = model.findClass("Element");
        GeneratedFieldModel atomicNumber = field(element, "atomicNumber");

        assertEquals(wikidata.explore.filter.WikidataValueFilterOperator.LE,
                atomicNumber.filterOperator());
        assertEquals(118.0, atomicNumber.filterValue());
        assertInfoboxField(element, "group", "Rendered.Group");
        assertInfoboxField(element, "period", "Rendered.Period");
        assertInfoboxField(element, "block", "Rendered.Block");
        assertInfoboxField(element, "electronsPerShell", "Rendered.Electrons per shell");
        GeneratedFieldModel nameOrigin = field(element, "nameOrigin");
        assertEquals(FieldType.TEXT, nameOrigin.type());
        assertInfoboxField(element, "nameOrigin", "Rendered.Naming");
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }

    private static void assertInfoboxField(
            GeneratedClassModel owner, String name, String key) {
        GeneratedFieldModel field = field(owner, name);
        assertEquals(FieldSourceType.WIKIPEDIA_INFOBOX, field.mapping().sourceType());
        assertEquals(key, field.mapping().propertyPid());
    }

    private static GeneratedFieldModel field(GeneratedClassModel owner, String name) {
        return owner.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }
}
