package wikidata.explore.generation;

import datasource.Datasources;
import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.ModelSourceExecutionPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinalLabelHydrationSourcePlanTest {

    @Test void sourceLabelsAreConfiguredWhileUnmodelledReferencesKeepTheirFallback() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel owned = new GeneratedClassModel("Name");
        owned.classKind(ClassKind.OWNED);
        model.rootClass(person);
        model.addClass(owned);
        var sourcePlan = ModelSourceExecutionPlan.compile(model, Datasources.standard());

        WikidataDynamicObject source = object("Q1", "Person");
        WikidataDynamicObject part = object("Q2", "Name");
        WikidataDynamicObject unmodelled = object("Q3", "ReferencedOnly");
        List<String> asked = new ArrayList<>();
        WikidataApiClient api = new WikidataApiClient(
                WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public PartialEntities getEntitiesBestEffort(
                    List<String> qids, List<String> pids, BatchLog log) {
                asked.addAll(qids);
                Map<String, ApiEntity> values = new LinkedHashMap<>();
                qids.forEach(qid -> values.put(qid,
                        new ApiEntity(qid, "Label " + qid, Map.of())));
                return new PartialEntities(values, 0);
            }
        };

        FinalLabelHydration.apply(List.of(source, part, unmodelled), api, null,
                new GenerationQualityTracker(), model, sourcePlan);

        assertEquals(List.of("Q1", "Q3"), asked);
        assertEquals("Label Q1", source.getDisplayName());
        assertEquals("Q2", part.getDisplayName(),
                "an owned class has no source-label binding");
        assertEquals("Label Q3", unmodelled.getDisplayName(),
                "an unmodelled QID remains safely readable");
    }

    private static WikidataDynamicObject object(String qid, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.type(type);
        return value;
    }
}
