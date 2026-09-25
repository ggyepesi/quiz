package wikidata.explore.codegen;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** One Wikidata entity may intentionally play two modeled roles with different schemas. */
class SameQidDifferentModeledTypesMappingTest {

    @Test void aBaseClassReferenceUsesTheDisplayedSubclassInstance() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.addField("successor", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Position");
        GeneratedClassModel withHolders = new GeneratedClassModel("PositionWithHolders");
        withHolders.baseClassName("Position");
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.addField("position", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Position");
        project.rootClass(position);
        project.addClass(withHolders);
        project.addClass(holding);

        WikidataDynamicObject office = object("Q1$holding", "OfficeHolding");
        WikidataDynamicObject reachedPosition = object("Q2", "PositionWithHolders");
        reachedPosition.put("successor", reachedPosition);
        office.put("position", reachedPosition);

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(project)) {
            List<objectview.Viewable> mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(List.of(reachedPosition, office));
            Object mappedOffice = mapped.get(1);
            java.lang.reflect.Field field = mappedOffice.getClass()
                    .getDeclaredField("position");

            assertNotNull(field.get(mappedOffice));
            assertEquals(objectview.Viewable.class, field.getType(),
                    "a base reference with modeled subclasses must accept the final carrier");
            assertEquals("PositionWithHolders",
                    field.get(mappedOffice).getClass().getSimpleName());
            assertSame(mapped.getFirst(), field.get(mappedOffice),
                    "the field and subclass panel must share one navigable instance");
        }
    }

    @Test void aSubclassInstanceReceivesItsBaseClassFields() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.addField("holderCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        GeneratedClassModel withHolders = new GeneratedClassModel("PositionWithHolders");
        withHolders.baseClassName("Position");
        project.rootClass(position);
        project.addClass(withHolders);

        WikidataDynamicObject source = object("Q18811", "PositionWithHolders");
        source.put("holderCount", 66L);

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(project)) {
            Object mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(List.of(source)).getFirst();
            java.lang.reflect.Field inherited = mapped.getClass()
                    .getDeclaredField("holderCount");

            assertEquals("66", String.valueOf(inherited.get(mapped)),
                    "a field shown by the effective schema must also be populated");
        }
    }

    @Test void sameQidInDifferentClassesProducesTwoCorrectlyTypedInstances() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.addField("superClasses", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Position");
        GeneratedClassModel start = new GeneratedClassModel("PositionDiscoveryStart");
        project.rootClass(position);
        project.addClass(start);

        WikidataDynamicObject startObject = object("Q114962596", "PositionDiscoveryStart");
        WikidataDynamicObject positionObject = object("Q114962596", "Position");
        positionObject.put("superClasses", List.of());

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(project)) {
            List<objectview.Viewable> mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(List.of(startObject, positionObject));

            assertEquals(2, mapped.size());
            assertEquals("PositionDiscoveryStart", mapped.get(0).getClass().getSimpleName());
            assertEquals("Position", mapped.get(1).getClass().getSimpleName());
            assertNotSame(mapped.get(0), mapped.get(1));
        }
    }

    private static WikidataDynamicObject object(String qid, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.type(type);
        value.typeKey(type);
        return value;
    }
}
