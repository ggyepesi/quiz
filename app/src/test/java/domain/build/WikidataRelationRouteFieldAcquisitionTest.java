package domain.build;

import domain.datasource.Datasources;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceExecutionPlan;
import domain.datasource.api.SourceRecipe;
import domain.datasource.api.SourceRuntimeServices;
import domain.datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import domain.runtime.GenerationLog;
import domain.runtime.WikidataDynamicObject;
import domain.config.FieldCardinality;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertEquals(List.of(
                        "direct Q201 Matched; final field not settled",
                        "qualified Q202 Matched; final field not settled",
                        "qualified Q203 Matched; final field not settled"),
                laocoon.wikidataRelationRouteMatches().stream()
                        .map(match -> match.routeId() + " " + match.terminal().qid()
                                + " " + match.status()).toList(),
                "coverage retains the exact configured route, not only its PIDs");
    }

    @Test void acquiredReferencesReenterTheExistingSemanticFieldPipeline()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        // Figure is not on a reference cycle with Character, so the ordinary referent
        // stamping admits a route terminal and the semantic pass loads its fields.
        GeneratedClassModel figure = new GeneratedClassModel("Figure");
        GeneratedClassModel artifact = new GeneratedClassModel("Artifact");
        GeneratedFieldModel children = character.addField(
                "children", FieldType.ENTITY, FieldCardinality.COLLECTION);
        children.entityClassName("Figure");
        GeneratedFieldModel father = figure.addField(
                "father", FieldType.ENTITY, FieldCardinality.COLLECTION);
        GeneratedFieldModel relics = figure.addField(
                "relics", FieldType.ENTITY, FieldCardinality.COLLECTION);
        relics.entityClassName("Artifact");
        model.rootClass(character);
        model.addClass(figure);
        model.addClass(artifact);
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
        SourceBinding relicRoute = new SourceBinding(
                SourceBindingTarget.fieldValue("Figure", "relics",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "relation-routes", Map.of("routes", """
                        [{"id":"relic","label":"Related relic","steps":[
                          {"relation":"P527","direction":"OUTGOING"}]}]
                        """)));
        domain.config.FieldSourceBindings.put(children, route);
        domain.config.FieldSourceBindings.put(father, parent);
        domain.config.FieldSourceBindings.put(relics, relicRoute);
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(route, parent, relicRoute), Datasources.standard());
        WikidataDynamicObject laocoon = character("Q101", "Laocoon");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q201", "child", Map.of("P22", List.of("Q301")))
                .entity("Q301", "parent")
                .entity("Q202", "relic")
                .statement("Q101", "P40", "Q101$P40-child", "Q201", Map.of())
                .statement("Q201", "P22", "Q201$P22-parent", "Q301", Map.of())
                .statement("Q201", "P527", "Q201$P527-relic", "Q202", Map.of());
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
                                domain.datasource.wikidata.WikidataDatasourceProvider
                                        .FAMILY_RELATION_ROUTE_FIELD));

        assertEquals(2, result.sources().values(),
                "the Figure admitted by pass one must become a route owner in pass two");
        assertNotNull(result.semantic(),
                "entity-valued route results must trigger the shared semantic pass");
        WikidataDynamicObject child = one(laocoon.get("children"));
        assertEquals(java.util.Set.of("Figure"), child.directClassNames());
        assertEquals("Q301", one(child.get("father")).qid());
        WikidataDynamicObject relic = one(child.get("relics"));
        assertEquals("Q202", relic.qid());
        assertEquals(java.util.Set.of("Artifact"), relic.directClassNames());
    }

    @Test void aCyclicRouteReusesTheCompatibleMemberAlreadyInThePool()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel children = character.addField(
                "children", FieldType.ENTITY, FieldCardinality.COLLECTION);
        children.entityClassName("Character");
        model.rootClass(character);
        SourceBinding binding = route("Character", "children", "P40");
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(binding), Datasources.standard());
        WikidataDynamicObject owner = character("Q101", "Laocoon");
        WikidataDynamicObject existingChild = character("Q201", "Antiphantes");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .statement("Q101", "P40", "Q101$P40-child", "Q201", Map.of());

        WikidataRelationRouteFieldAcquisition.apply(model,
                List.of(owner, existingChild), plan, api, null, GenerationLog.NOOP,
                new work.CancellationToken());

        assertSame(existingChild, one(owner.get("children")),
                "source identity is resolved inside the field's configured type");
    }

    @Test void pathsThroughSeveralQualifierValuesMergeOneStatementEvidence()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel children = character.addField(
                "children", FieldType.ENTITY, FieldCardinality.COLLECTION);
        children.entityClassName("Character");
        model.rootClass(character);
        String routes = """
                [{"id":"qualified","label":"Named child groups","steps":[
                  {"relation":"statement:P40","direction":"OUTGOING"},
                  {"relation":"qualifier:P3831","direction":"OUTGOING"},
                  {"relation":"P527","direction":"OUTGOING"}]}]
                """;
        SourceBinding binding = new SourceBinding(
                SourceBindingTarget.fieldValue("Character", "children",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "relation-routes", Map.of("routes", routes)));
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(binding), Datasources.standard());
        WikidataDynamicObject owner = character("Q101", "Laocoon");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .statement("Q101", "P40", "Q101$P40-groups", "",
                        Map.of("P3831", List.of("Q301", "Q302")))
                .statement("Q301", "P527", "Q301$P527-child", "Q201", Map.of())
                .statement("Q302", "P527", "Q302$P527-child", "Q202", Map.of());

        WikidataRelationRouteFieldAcquisition.apply(model, List.of(owner), plan,
                api, null, GenerationLog.NOOP, new work.CancellationToken());

        domain.provenance.WikidataStatementSource statement = owner.wikidataStatementSources()
                .stream().filter(source -> source.statement().equals("Q101$P40-groups"))
                .findFirst().orElseThrow();
        assertEquals(List.of("Q301", "Q302"), statement.qualifiers().get("P3831"));
    }

    private static SourceBinding route(String owner, String field, String pid) {
        return new SourceBinding(
                SourceBindingTarget.fieldValue(owner, field,
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "relation-routes", Map.of("routes", """
                        [{"id":"direct","label":"Direct","steps":[
                          {"relation":"%s","direction":"OUTGOING"}]}]
                        """.formatted(pid))));
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
