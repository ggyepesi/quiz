package wikidata.explore.generation;

import datasource.schema.FieldType;

import datasource.graph.GraphExpansionPolicy;
import datasource.graph.GraphTraversalDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.model.*;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;

class WikidataFieldGraphTraversalTest {
    @Test void compilesAnExplicitOutgoingEntityField() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel successor = person.addField(
                "successor", FieldType.ENTITY, FieldCardinality.COLLECTION);
        successor.entityClassName("Person");
        successor.mapping().propertyPid("P156");
        successor.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        successor.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        model.addClass(person);

        var step = assertDoesNotThrow(() ->
                WikidataFieldGraphTraversal.derive(model).getFirst());
        assertEquals("Person", step.sourceNodeClass());
        assertEquals("Person", step.targetNodeClass());
        assertEquals("P156", step.relation().relationId());
        assertEquals(GraphTraversalDirection.OUTGOING, step.direction());
        assertEquals(GraphExpansionPolicy.CURATED, step.policy());
    }

    @Test void incompleteAndOrdinaryFieldsDoNotBecomeTraversalSteps() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        model.addClass(person);
        GeneratedFieldModel spouse = person.addField(
                "spouse", FieldType.ENTITY, FieldCardinality.COLLECTION);
        spouse.entityClassName("Person");
        spouse.mapping().propertyPid("P26");
        assertTrue(WikidataFieldGraphTraversal.derive(model).isEmpty());

        spouse.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        spouse.entityClassName("");
        assertTrue(WikidataFieldGraphTraversal.derive(model).isEmpty());
    }

    @Test void nonSparqlAndUnmodeledTargetsCannotCompile() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel spouse = person.addField(
                "spouse", FieldType.ENTITY, FieldCardinality.COLLECTION);
        spouse.entityClassName("MissingPerson");
        spouse.mapping().propertyPid("P26");
        spouse.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        model.addClass(person);

        assertTrue(WikidataFieldGraphTraversal.derive(model).isEmpty());

        GeneratedClassModel target = new GeneratedClassModel("MissingPerson");
        model.addClass(target);
        spouse.mapping().sourceType(FieldSourceType.DBPEDIA);
        assertTrue(WikidataFieldGraphTraversal.derive(model).isEmpty());
    }

    @Test void copyPreservesThePolicyWithoutMakingNoneExplicit() {
        GeneratedFieldModel field = new GeneratedFieldModel(
                "successor", FieldType.ENTITY, FieldCardinality.SINGLE);
        assertEquals(GraphExpansionPolicy.NONE, field.copy().graphExpansionPolicy());
        field.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        assertEquals(GraphExpansionPolicy.CURATED, field.copy().graphExpansionPolicy());
    }

    @Test void fieldTraversalPolicyRoundTripsWithTheModel(@TempDir Path directory)
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().sourceQid("Q5");
        person.instanceMapping().propertyPid("P31");
        GeneratedFieldModel successor = person.addField(
                "successor", FieldType.ENTITY, FieldCardinality.COLLECTION);
        successor.entityClassName("Person");
        successor.mapping().propertyPid("P156");
        successor.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        model.rootClass(person);
        var file = directory.resolve("model.json").toFile();

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(model, file);
        GeneratedProjectModel loaded = store.load(file);

        assertEquals(GraphExpansionPolicy.CURATED,
                loaded.rootClass().fields().getFirst().graphExpansionPolicy());
    }
}
