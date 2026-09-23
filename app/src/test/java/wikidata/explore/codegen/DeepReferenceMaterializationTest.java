package wikidata.explore.codegen;

import datasource.schema.FieldType;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A domain graph's depth is data, not a permitted use of the JVM call stack. */
class DeepReferenceMaterializationTest {

    @Test void aReferenceChainDeeperThanTheJvmStackMaterializesIteratively()
            throws Exception {
        GeneratedClassModel node = new GeneratedClassModel("DeepNode");
        node.addField("next", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("DeepNode");

        int length = 10_000;
        WikidataDynamicObject first = new WikidataDynamicObject("Q1", "1");
        first.type("DeepNode");
        WikidataDynamicObject current = first;
        for (int i = 2; i <= length; i++) {
            WikidataDynamicObject next = new WikidataDynamicObject("Q" + i,
                    Integer.toString(i));
            next.type("DeepNode");
            current.put("next", next);
            current = next;
        }

        GeneratedViewableRuntime runtime = new GeneratedViewableRuntimeBuilder().build(node);
        Viewable mapped = new GeneratedViewableMapper(runtime)
                .mapRoots(List.of(first)).getFirst();
        Field nextField = mapped.getClass().getDeclaredField("next");
        nextField.setAccessible(true);
        int visited = 1;
        Object cursor = mapped;
        while ((cursor = nextField.get(cursor)) != null) visited++;

        assertEquals(length, visited);
    }
}
