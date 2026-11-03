package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import datasource.schema.FieldType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationPipelineUnitResolutionTest {

    @Test void dimensionlessWikidataUnitIsNotRenderedAfterTheNumber() {
        GeneratedFieldModel atomicNumber = resolve("1");

        assertEquals("", atomicNumber.unit(),
                "Wikidata unit 1 means dimensionless, not a visible suffix");
    }

    @Test void physicalUnitSymbolIsKept() {
        GeneratedFieldModel meltingPoint = resolve("K");

        assertEquals("K", meltingPoint.unit());
    }

    private static GeneratedFieldModel resolve(String symbol) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel element = new GeneratedClassModel("Element");
        element.membership(EntityBound.relation("P31", List.of("Q11344"), false));
        GeneratedFieldModel field = element.addField(
                "quantity", FieldType.NUMBER, FieldCardinality.SINGLE);
        field.mapping().propertyPid("P1086");
        project.rootClass(element);

        try (WikidataSparqlClient client = new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String sparql) {
                return List.of(new WikidataBinding(Map.of("sym", symbol)));
            }
        }) {
            new GenerationPipeline().resolveUnits(project, client, GenerationLog.NOOP);
        }
        return field;
    }
}
