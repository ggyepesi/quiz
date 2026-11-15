package wikidata.explore.generation;

import datasource.Datasources;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.api.SourceRecipe;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Preview must interpret configured sources through the same semantic stages as a run. */
class PreviewRelationRouteReplayTest {

    @Test void previewSettlesEntityReferencesBeforeMaterialization() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("preview-routes");
        GeneratedClassModel character = new GeneratedClassModel("Character");
        character.membership(EntityBound.explicit(List.of("Q101")));
        GeneratedClassModel figure = new GeneratedClassModel("Figure");
        GeneratedFieldModel children = character.addField(
                "children", FieldType.ENTITY, FieldCardinality.COLLECTION);
        children.entityClassName("Figure");
        model.rootClass(character);
        model.addClass(figure);
        SourceBinding route = new SourceBinding(
                SourceBindingTarget.fieldValue("Character", "children",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "relation-routes", Map.of("routes", """
                        [{"id":"direct","label":"Direct child","steps":[
                          {"relation":"P40","direction":"OUTGOING"}]}]
                        """)));
        wikidata.explore.model.FieldSourceBindings.put(children, route);
        SourceBinding population = new SourceBinding(
                SourceBindingTarget.classPopulation("Character"),
                new SourceRecipe("wikidata", "seed-list", Map.of("ids", "Q101")));
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(population, route), Datasources.standard());
        WikidataDynamicObject owner = new WikidataDynamicObject("Q101", "Laocoon");
        owner.type("Character");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q201", "Antiphantes")
                .statement("Q101", "P40", "Q101$P40-child", "Q201", Map.of());
        GenerationPipeline pipeline = new GenerationPipeline() {
            @Override public List<WikidataDynamicObject> extract(
                    wikidata.WikidataSparqlClient client,
                    wikidata.explore.rule.RuleNode query, int depth, GenerationLog log) {
                return new java.util.ArrayList<>(List.of(owner));
            }
        };

        GenerationRun preview = pipeline.fullRun(model, 1, null, GenerationLog.NOOP,
                api, new work.CancellationToken(), plan, null);

        WikidataDynamicObject child = one(
                preview.dynamicObjects().getFirst().get("children"));
        assertEquals(java.util.Set.of("Figure"), child.directClassNames());
        assertTrue(preview.kindClassificationAudit().executed(),
                "Preview reports the same semantic stage it executed");
    }

    /** Without an entity-valued source the preview still skips semantic convergence, so
     *  it must still say that owned parts were not made; when a route's references were
     *  settled, convergence made them and the note would be false. */
    @Test void thePreviewSaysWhenOwnedPartsWereNotMade() throws Exception {
        assertTrue(previewLog(false).contains("1 owned-component field(s) NOT materialized"));
        assertTrue(!previewLog(true).contains("NOT materialized"));
    }

    private static String previewLog(boolean withRoute) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("preview-owned");
        GeneratedClassModel character = new GeneratedClassModel("Character");
        character.membership(EntityBound.explicit(List.of("Q101")));
        GeneratedClassModel figure = new GeneratedClassModel("Figure");
        GeneratedClassModel name = new GeneratedClassModel("BirthName");
        GeneratedFieldModel birthName = character.addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        birthName.entityClassName("BirthName");
        birthName.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.OWNED_COMPONENT);
        GeneratedFieldModel children = character.addField(
                "children", FieldType.ENTITY, FieldCardinality.COLLECTION);
        children.entityClassName("Figure");
        model.rootClass(character);
        name.ownedClass(true);
        name.addField("givenName", FieldType.STRING, FieldCardinality.SINGLE)
                .mapping().propertyPid("P735");
        model.addClass(figure);
        model.addClass(name);
        wikidata.explore.model.OwnedClassSemantics.synchronizeOwnerReferences(model);
        List<SourceBinding> bindings = new java.util.ArrayList<>(List.of(new SourceBinding(
                SourceBindingTarget.classPopulation("Character"),
                new SourceRecipe("wikidata", "seed-list", Map.of("ids", "Q101")))));
        if (withRoute) {
            SourceBinding route = new SourceBinding(
                    SourceBindingTarget.fieldValue("Character", "children",
                            SourceBindingSlot.PRIMARY_FIELD_VALUE),
                    new SourceRecipe("wikidata", "relation-routes", Map.of("routes", """
                            [{"id":"direct","label":"Direct child","steps":[
                              {"relation":"P40","direction":"OUTGOING"}]}]
                            """)));
            wikidata.explore.model.FieldSourceBindings.put(children, route);
            bindings.add(route);
        }
        SourceExecutionPlan plan = SourceExecutionPlan.compile(bindings,
                Datasources.standard());
        WikidataDynamicObject owner = new WikidataDynamicObject("Q101", "Laocoon");
        owner.type("Character");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q201", "Antiphantes")
                .statement("Q101", "P40", "Q101$P40-child", "Q201", Map.of());
        GenerationPipeline pipeline = new GenerationPipeline() {
            @Override public List<WikidataDynamicObject> extract(
                    wikidata.WikidataSparqlClient client,
                    wikidata.explore.rule.RuleNode query, int depth, GenerationLog log) {
                return new java.util.ArrayList<>(List.of(owner));
            }
        };
        StringBuilder log = new StringBuilder();
        pipeline.fullRun(model, 1, null, GenerationLog.of(log::append), api,
                new work.CancellationToken(), plan, null);
        return log.toString();
    }

    private static WikidataDynamicObject one(Object value) {
        if (value instanceof WikidataDynamicObject object) return object;
        if (value instanceof List<?> values && values.size() == 1
                && values.getFirst() instanceof WikidataDynamicObject object) return object;
        throw new AssertionError("Expected one entity value, got " + value);
    }
}
