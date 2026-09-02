package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPolicy;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A field pointing at its own class is an edge, and generation now walks it.
 *
 * <p>It was derived, drawn in the configuration diagram and sampled, and then ignored:
 * ledger application read statement patterns only, so a curated self-reference could be
 * configured, seen, and never followed. Nothing here fetches — expansion means the nodes
 * a walk reached become the target class's acquisition seeds, which is exactly what a
 * statement pattern already did.
 */
class FieldEdgeExpansionTest {

    private static GeneratedProjectModel positions() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("History");
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.instanceMapping().sourceQid("Q4164871");
        position.instanceMapping().propertyPid("P31");
        position.seedQids().add("Q6412254");
        GeneratedFieldModel broader = position.addField(
                "broaderPosition", FieldType.ENTITY, FieldCardinality.COLLECTION);
        broader.entityClassName("Position");
        broader.mapping().propertyPid("P279");
        broader.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        model.rootClass(position);
        return model;
    }

    private static WikidataDynamicObject position(String qid, String name) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type("Position");
        o.assignClass("Position");
        return o;
    }

    @Test void aSelfReferencingFieldIsDerivedAsAnEdge() {
        var steps = WikidataFieldGraphTraversal.derive(positions());

        assertEquals(1, steps.size());
        assertEquals("Position", steps.getFirst().sourceNodeClass());
        assertEquals("Position", steps.getFirst().targetNodeClass());
        assertEquals("broaderPosition", steps.getFirst().sourceField());
        assertEquals("P279", steps.getFirst().relation().relationId());
    }

    /** What the field reached is the walk's frontier — no statement class between. */
    @Test void whatTheFieldReachedBecomesTheFrontier() {
        GeneratedProjectModel model = positions();
        WikidataDynamicObject king = position("Q6412254", "King of Bohemia");
        WikidataDynamicObject monarch = position("Q116", "monarch");
        king.put("broaderPosition", List.of(monarch));

        GraphDiscoveryState state = WikidataGraphDiscoveryState.compute(
                model, List.of(king, monarch));

        var step = WikidataFieldGraphTraversal.derive(model).getFirst();
        assertEquals(List.of("Q116"), state.frontier(step).stream()
                .map(c -> c.node().id()).toList(),
                "the broader position it reached is awaiting a decision");
        assertEquals(List.of("Q6412254"), state.coverage(
                        step, GraphExpansionCoverage.State.EXPANDED).stream()
                .map(c -> c.node().id()).toList(),
                "and the seed it started from is already expanded");
    }

    @Test void aCrossClassFieldCannotPretendItsTargetsExpandTheSourceEdge() {
        GeneratedProjectModel model = positions();
        GeneratedClassModel category = new GeneratedClassModel("Category");
        category.seedQids().add("Q999");
        model.addClass(category);
        GeneratedFieldModel broader = model.findClass("Position").fields().getFirst();
        broader.entityClassName("Category");
        assertEquals(List.of(), WikidataFieldGraphTraversal.derive(model),
                "a Category seed has not enumerated Position.broaderPosition");
    }

    /** Expanding is seeding: a queued node becomes what the class acquires next. */
    @Test void aQueuedNodeBecomesASeedOnTheTargetClass() {
        GeneratedProjectModel model = positions();
        var step = WikidataFieldGraphTraversal.derive(model).getFirst();
        GraphDiscoveryState ledger = new GraphDiscoveryState(List.of(), List.of())
                .queue(step, EntityRef.wikidata("Q116"));

        assertFalse(model.findClass("Position").seedQids().contains("Q116"));

        WikidataGraphDiscoveryState.applyExpansionLedger(model, ledger);

        assertTrue(model.findClass("Position").seedQids().contains("Q116"),
                "the node the reader chose is now acquired by the next generation");
        assertTrue(model.findClass("Position").seedQids().contains("Q6412254"),
                "and the original anchor is still there");
    }

    /** A field with no expansion policy is not an edge and seeds nothing. */
    @Test void aFieldWithoutACuratedPolicyIsNotWalked() {
        GeneratedProjectModel model = positions();
        model.findClass("Position").fields().getFirst()
                .graphExpansionPolicy(GraphExpansionPolicy.NONE);

        assertEquals(List.of(), WikidataFieldGraphTraversal.derive(model));
        assertTrue(WikidataGraphDiscoveryState.compute(model, List.of()).coverage().isEmpty());
    }
}
