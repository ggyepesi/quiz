package wikidata.explore.generation;

import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPolicy;
import datasource.graph.GraphDiscoveryState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WikidataGraphDiscoveryStateTest {

    @TempDir Path temporary;

    @Test void seededPositionIsExpandedAndOtherReachedPositionIsFrontier() {
        GeneratedProjectModel model = historyModel();
        List<WikidataDynamicObject> objects = historyObjects();

        var state = WikidataGraphDiscoveryState.compute(model, objects);

        assertEquals(1, state.patterns().size());
        assertEquals(Map.of(
                        "Q6412254", GraphExpansionCoverage.State.EXPANDED,
                        "Q253779", GraphExpansionCoverage.State.ENCOUNTERED),
                state.coverage().stream().collect(Collectors.toMap(
                        item -> item.node().id(), GraphExpansionCoverage::state)));
    }

    @Test void graphCoverageSurvivesSnapshotSaveAndLoad() throws Exception {
        File snapshot = temporary.resolve("history.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(historyObjects(), snapshot, historyModel(), List.of());

        var restored = store.loadAllWithFieldGraph(snapshot).graphDiscovery();

        assertEquals(1, restored.patterns().size());
        var pattern = restored.patterns().getFirst();
        assertEquals(1, restored.frontier(pattern).size());
        assertEquals("Q253779", restored.frontier(pattern)
                .getFirst().node().id());
    }

    @Test void graphCoverageCanBeRestoredWithoutMaterializingTheEntityPool()
            throws Exception {
        File snapshot = temporary.resolve("history-metadata.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(historyObjects(), snapshot, historyModel(), List.of());

        // If this method accidentally enters the ordinary entity loader, the invalid
        // pool makes it fail. The ledger itself remains valid snapshot metadata.
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode tree = json.readTree(snapshot);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
                .putArray("entities").add("not an entity");
        json.writerWithDefaultPrettyPrinter().writeValue(snapshot, tree);

        GraphDiscoveryState restored = store.loadGraphDiscovery(snapshot);

        assertEquals(1, restored.patterns().size());
        assertEquals("Q253779", restored.frontier(restored.patterns().getFirst())
                .getFirst().node().id());
    }

    @Test void explicitLedgerSurvivesSnapshotWithoutBeingRecomputedFromSeeds()
            throws Exception {
        GeneratedProjectModel model = historyModel();
        var observed = WikidataGraphDiscoveryState.compute(model, historyObjects());
        var pattern = observed.patterns().getFirst();
        GraphDiscoveryState queued = observed.queue(
                pattern.id(), datasource.EntityRef.wikidata("Q253779"));
        File snapshot = temporary.resolve("queued.snapshot.json").toFile();

        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(historyObjects(), snapshot, model, List.of(), queued);

        assertEquals(GraphExpansionCoverage.State.QUEUED,
                store.loadAllWithFieldGraph(snapshot).graphDiscovery().coverage().stream()
                        .filter(item -> "Q253779".equals(item.node().id()))
                        .findFirst().orElseThrow().state());
    }

    @Test void expansionLedgerAugmentsOnlyTheDisposableGenerationModel() {
        GeneratedProjectModel authored = historyModel();
        GeneratedProjectModel execution = authored.copy();
        var state = WikidataGraphDiscoveryState.compute(authored, historyObjects());
        var pattern = state.patterns().getFirst();
        GraphDiscoveryState queued = state.queue(
                pattern.id(), datasource.EntityRef.wikidata("Q253779"));

        WikidataGraphDiscoveryState.applyExpansionLedger(execution, queued);

        assertEquals(List.of("Q6412254"), authored.findClass("Position").seedQids());
        assertEquals(List.of("Q6412254", "Q253779"),
                execution.findClass("Position").seedQids());
    }

    @Test void aDormantLedgerIsRetainedButNotExecutedWhileThePolicyIsDisabled() {
        GeneratedProjectModel model = historyModel();
        var state = WikidataGraphDiscoveryState.compute(model, historyObjects());
        var pattern = state.patterns().getFirst();
        GraphDiscoveryState queued = state.queue(
                pattern.id(), datasource.EntityRef.wikidata("Q253779"));
        model.findClass("OfficeHolding").statementSource()
                .graphExpansionPolicy(GraphExpansionPolicy.NONE);

        WikidataGraphDiscoveryState.applyExpansionLedger(model, queued);

        assertEquals(List.of("Q6412254"), model.findClass("Position").seedQids());
    }

    @Test void structurallyEligibleStatementDoesNotBecomeAGraphPatternUnlessEnabled() {
        GeneratedProjectModel model = historyModel();
        model.findClass("OfficeHolding").statementSource()
                .graphExpansionPolicy(GraphExpansionPolicy.NONE);

        assertEquals(0, WikidataGraphDiscoveryState.compute(model, historyObjects())
                .patterns().size());
    }

    // The editor explains a policy the user is choosing but has not yet applied, so
    // the structural question must be answerable independently of the policy — and
    // must be the SAME derivation generation uses, or the editor could promise a
    // pattern that generation would not build.
    @Test void theStructuralPatternIsAnswerableWithoutThePolicyBeingEnabled() {
        GeneratedProjectModel model = historyModel();
        model.findClass("OfficeHolding").statementSource()
                .graphExpansionPolicy(GraphExpansionPolicy.NONE);

        var structural = WikidataGraphDiscoveryState
                .structuralPattern(model, "OfficeHolding");
        assertNotNull(structural, "structure is eligible even while expansion is off");
        assertEquals("Position", structural.targetNodeClass());
        assertEquals(0, WikidataGraphDiscoveryState.compute(model, historyObjects())
                .patterns().size(), "but nothing is derived until it is enabled");

        model.findClass("OfficeHolding").statementSource()
                .graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        assertEquals(structural.id(), WikidataGraphDiscoveryState
                .compute(model, historyObjects()).patterns().getFirst().id(),
                "the editor and generation must derive the same pattern");
    }

    @Test void anIncompleteSourceEndpointIsUnavailableRatherThanExceptional() {
        GeneratedProjectModel model = historyModel();
        GeneratedClassModel holding = model.findClass("OfficeHolding");
        holding.fields().stream().filter(field -> "source".equals(field.name()))
                .findFirst().orElseThrow().entityClassName("");

        assertNull(WikidataGraphDiscoveryState
                .structuralPattern(model, "OfficeHolding"));
        assertEquals(0, WikidataGraphDiscoveryState.compute(model, historyObjects())
                .patterns().size());
    }

    private static GeneratedProjectModel historyModel() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        model.addClass(person);
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.seedQids().add("Q6412254");
        model.addClass(position);
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource statementSource = new StatementClassSource("P39");
        statementSource.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        holding.statementSource(statementSource);
        holding.instanceMapping().propertyPid("P39");
        var source = holding.addField("source", FieldType.ENTITY,
                FieldCardinality.SINGLE);
        source.entityClassName("Person");
        var target = holding.addField("position", FieldType.ENTITY,
                FieldCardinality.SINGLE);
        target.entityClassName("Position");
        target.mapping().propertyPid("P39");
        model.addClass(holding);
        return model;
    }

    private static List<WikidataDynamicObject> historyObjects() {
        WikidataDynamicObject bela = object("Q82686", "Bela II", "Person");
        WikidataDynamicObject king = object(
                "Q6412254", "Apostolic King of Hungary", "Position");
        WikidataDynamicObject ban = object("Q253779", "Ban of Croatia", "Position");
        WikidataDynamicObject first = object("Q82686$king", "King", "OfficeHolding");
        first.put("source", bela); first.put("position", king);
        WikidataDynamicObject second = object("Q82686$ban", "Ban", "OfficeHolding");
        second.put("source", bela); second.put("position", ban);
        return List.of(bela, king, ban, first, second);
    }

    private static WikidataDynamicObject object(String id, String name, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(id, name);
        object.type(type);
        return object;
    }
}
