package wikidata.explore.generation;

import datasource.Datasources;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.api.SourceRecipe;
import datasource.api.SourceRuntimeServices;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikidataRelationRouteFieldAcquisitionTest {

    @Test void fillsOneEntityCollectionFromTheUnionOfConfiguredRoutes()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel children = character.addField(
                "children", FieldType.ENTITY, FieldCardinality.COLLECTION);
        children.entityClassName("Character");
        model.rootClass(character);
        String routes = """
                [{"id":"direct","label":"Direct child","steps":[
                   {"relation":"P40","direction":"OUTGOING"}]},
                 {"id":"qualified","label":"Named child group","steps":[
                   {"relation":"statement:P40","direction":"OUTGOING"},
                   {"relation":"qualifier:P3831","direction":"OUTGOING"},
                   {"relation":"P527","direction":"OUTGOING"}]}]
                """;
        SourceBinding binding = new SourceBinding(
                SourceBindingTarget.fieldValue("Character", "children",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "relation-routes",
                        Map.of("routes", routes)));
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(binding), Datasources.standard());
        WikidataDynamicObject laocoon = character("Q101", "Laocoon");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .statement("Q101", "P40", "Q101$P40-direct", "Q201", Map.of())
                .statement("Q101", "P40", "Q101$P40-group", "",
                        Map.of("P3831", List.of("Q301")))
                .statement("Q301", "P527", "Q301$P527-1", "Q202", Map.of())
                .statement("Q301", "P527", "Q301$P527-2", "Q203", Map.of());

        java.util.List<String> log = new java.util.ArrayList<>();
        WikidataRelationRouteFieldAcquisition.Result result =
                WikidataRelationRouteFieldAcquisition.apply(model, List.of(laocoon),
                        plan, api, null, GenerationLog.of(log::add),
                        new work.CancellationToken());

        assertEquals(1, result.fields(), String.join("", log));
        assertEquals(3, result.values(), String.join("", log));
        @SuppressWarnings("unchecked")
        List<WikidataDynamicObject> values =
                (List<WikidataDynamicObject>) laocoon.get("children");
        assertEquals(List.of("Q201", "Q202", "Q203"), values.stream()
                .map(WikidataDynamicObject::qid).toList());
        // A route links; it does not admit. The terminals stay bare so the ordinary
        // referent stamping decides membership, as for a property-loaded value.
        values.forEach(value -> assertTrue(!value.hasTypeStamp(), value.qid()));
        // The statements that made each path true are the owner's provenance; the
        // statement and its P3831 qualifier are one statement.
        assertEquals(List.of(
                        "Q101$P40-direct Q101 P40 Q201 {}",
                        "Q101$P40-group Q101 P40  {P3831=[Q301]}",
                        "Q301$P527-1 Q301 P527 Q202 {}",
                        "Q301$P527-2 Q301 P527 Q203 {}"),
                laocoon.wikidataStatementSources().stream()
                        .map(source -> source.statement() + " " + source.subject() + " "
                                + source.property() + " " + source.object() + " "
                                + source.qualifiers()).toList());
    }

    @Test void acquiredReferencesReenterTheExistingSemanticFieldPipeline()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        // Figure is not on a reference cycle with Character, so the ordinary referent
        // stamping admits a route terminal and the semantic pass loads its fields.
        GeneratedClassModel figure = new GeneratedClassModel("Figure");
        GeneratedFieldModel children = character.addField(
                "children", FieldType.ENTITY, FieldCardinality.COLLECTION);
        children.entityClassName("Figure");
        GeneratedFieldModel father = figure.addField(
                "father", FieldType.ENTITY, FieldCardinality.COLLECTION);
        model.rootClass(character);
        model.addClass(figure);
        SourceBinding route = new SourceBinding(
                SourceBindingTarget.fieldValue("Character", "children",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "relation-routes", Map.of("routes", """
                        [{"id":"direct","label":"Direct child","steps":[
                          {"relation":"P40","direction":"OUTGOING"}]}]
                        """)));
        SourceBinding parent = new SourceBinding(
                SourceBindingTarget.fieldValue("Figure", "father",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "property-value", Map.of(
                        "property", "P22", "label", "father",
                        "sourceType", "SPARQL", "valueLanguage", "")));
        wikidata.explore.model.FieldSourceBindings.put(children, route);
        wikidata.explore.model.FieldSourceBindings.put(father, parent);
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(route, parent), Datasources.standard());
        WikidataDynamicObject laocoon = character("Q101", "Laocoon");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q201", "child", Map.of("P22", List.of("Q301")))
                .entity("Q301", "parent")
                .statement("Q101", "P40", "Q101$P40-child", "Q201", Map.of())
                .statement("Q201", "P22", "Q201$P22-parent", "Q301", Map.of());
        SourceRuntimeServices services = SourceRuntimeServices.builder()
                .put("wikidata", wikidata.api.WikidataApiClient.class, api)
                .build();

        ExternalSourceAcquisition.Applied result =
                ExternalSourceAcquisition.applyWithSemanticReplay(
                        model, List.of(laocoon), plan, services, GenerationLog.NOOP,
                        new work.CancellationToken(),
                        ExternalSourceAcquisition.FailurePolicy.STRICT,
                        List.of(), new GenerationQualityTracker(),
                        java.util.Set.of(
                                datasource.wikidata.WikidataDatasourceProvider
                                        .FAMILY_RELATION_ROUTE_FIELD));

        assertEquals(1, result.sources().values());
        assertNotNull(result.semantic(),
                "entity-valued route results must trigger the shared semantic pass");
        WikidataDynamicObject child = one(laocoon.get("children"));
        assertEquals(java.util.Set.of("Figure"), child.directClassNames());
        assertEquals("Q301", one(child.get("father")).qid());
    }

    private static WikidataDynamicObject character(String qid, String label) {
        WikidataDynamicObject object = new WikidataDynamicObject(qid, label);
        object.type("Character");
        return object;
    }

    private static WikidataDynamicObject one(Object value) {
        if (value instanceof WikidataDynamicObject object) return object;
        if (value instanceof List<?> values && values.size() == 1
                && values.getFirst() instanceof WikidataDynamicObject object) return object;
        throw new AssertionError("Expected one entity value, got " + value);
    }
}
