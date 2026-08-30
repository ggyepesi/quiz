package wikidata.explore.generation;

import datasource.Datasources;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.api.SourceRecipe;
import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DBpediaFieldAcquisitionTest {

    @Test void resolvedBindingDrivesAcquisitionWithoutTheLegacyMapping() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.addField("country", FieldType.STRING, FieldCardinality.SINGLE);
        model.rootClass(movie);
        SourceBinding binding = new SourceBinding(
                SourceBindingTarget.fieldValue("Movie", "country",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("dbpedia", "property",
                        Map.of("property", "country")));
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(binding), Datasources.standard());
        WikidataDynamicObject object = new WikidataDynamicObject("Q1", "Film");
        object.type("Movie");

        try (FakeClient client = new FakeClient()) {
            DBpediaFieldAcquisition.Result result = DBpediaFieldAcquisition.apply(
                    model, List.of(object), plan, client, GenerationLog.NOOP);

            assertEquals("Sierra Leone", object.get("country"));
            assertEquals(1, result.fields());
            assertEquals(1, result.values());
            assertTrue(client.query.contains("dbp:country"));
        }
    }

    @Test void unsupportedNestedBindingIsSkippedWithoutAbortingTheRun() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        var details = movie.addField("details", FieldType.ENTITY,
                FieldCardinality.SINGLE);
        details.fields().add(new wikidata.explore.model.GeneratedFieldModel(
                "country", FieldType.STRING, FieldCardinality.SINGLE));
        model.rootClass(movie);
        SourceExecutionPlan plan = SourceExecutionPlan.compile(List.of(new SourceBinding(
                SourceBindingTarget.fieldValue("Movie", "details.country",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("dbpedia", "property",
                        Map.of("property", "country")))), Datasources.standard());
        WikidataDynamicObject object = new WikidataDynamicObject("Q1", "Film");
        object.type("Movie");

        try (FakeClient client = new FakeClient()) {
            DBpediaFieldAcquisition.Result result = DBpediaFieldAcquisition.apply(
                    model, List.of(object), plan, client, GenerationLog.NOOP);

            assertEquals(0, result.fields());
            assertEquals(0, result.values());
            assertEquals("", client.query);
        }
    }

    private static final class FakeClient extends WikidataSparqlClient {
        private String query = "";
        private FakeClient() { super("test", 1, "https://example.test/sparql"); }
        @Override public List<WikidataBinding> query(String sparql) {
            query = sparql;
            return List.of(new WikidataBinding(Map.of(
                    "wd", "http://www.wikidata.org/entity/Q1",
                    "val", "Sierra Leone")));
        }
    }
}
