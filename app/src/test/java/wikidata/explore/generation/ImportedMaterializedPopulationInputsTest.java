package wikidata.explore.generation;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.ClassPopulationMode;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.TransformationClassSource;
import wikidata.explore.model.TransformationStep;
import wikidata.explore.model.ConstructInventory;
import wikidata.explore.transform.MaterializedPopulationStore;
import quiz.transform.FilterOperator;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An importer reads exactly what a population selection published. The publication used to
 * overwrite the transformation's own output under the transformation's signature, so one
 * file had two producers: after a graph expansion added predecessor positions, the file that
 * claimed to be the holder-count filter's output held members the filter never kept.
 */
class ImportedMaterializedPopulationInputsTest {
    @TempDir Path root;

    @Test void consumerLoadsOnlyPublishedTypedArtifactNotOwnersSnapshot() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel owner = new GeneratedProjectModel();
        owner.name("Positions");
        owner.rootClass().className("Position");
        GeneratedClassModel output = new GeneratedClassModel("PositionWithHolders");
        output.baseClassName("Position");
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        owner.addClass(output);
        TransformationClassSource transformation = new TransformationClassSource();
        transformation.inputClassReference(owner.rootClass().declarationId(), "Position");
        transformation.addStep(TransformationStep.filter(
                "holderCount", FilterOperator.GREATER_THAN, 0, null));
        output.transformationSource(transformation);
        PopulationSelection published = new PopulationSelection("PositionsForHistory");
        published.className(output.className());
        published.instanceQids(List.of("Q1"));
        owner.addSelection(published);
        new GeneratedProjectModelStore().save(owner, storage.modelFile(owner.name()));
        storage.reconcileConstructs(owner.name(), ConstructInventory.of(owner), "input");

        WikidataDynamicObject typed = new WikidataDynamicObject("Q1", "one");
        typed.assignClass("PositionWithHolders");
        typed.put("holderCount", 7);
        MaterializedPopulationStore.publish(owner, published, output, List.of(typed), storage,
                "input");

        GeneratedProjectModel consumer = new GeneratedProjectModel();
        consumer.name("History");
        consumer.rootClass().className("OfficeHolding");
        consumer.rootClass().membership(EntityBound.vocabulary("PositionsForHistory"));
        GeneratedClassModel imported = output.copy();
        imported.importedFrom(owner.name());
        consumer.addClass(imported);
        PopulationSelection importedSelection = published.copy();
        importedSelection.importedFrom(owner.name());
        importedSelection.instanceQids(List.of("Q2"));
        consumer.addSelection(importedSelection);

        ImportedMaterializedPopulationInputs inputs =
                ImportedMaterializedPopulationInputs.resolve(consumer, storage);
        PopulationSourceExecution.Resolution resolution = PopulationSourceExecution.resolve(
                consumer, imported, null, inputs);

        assertTrue(resolution.importedMaterialized());
        assertEquals(1, resolution.materializedObjects().size());
        assertEquals(7, resolution.materializedObjects().getFirst().get("holderCount"));
        assertEquals(List.of("Q1"), resolution.qids(),
                "a dependency rebuilt in this run supplies its current published boundary");
    }

    @Test void aPublicationCarriesExpandedMembersWithoutRewritingTheTransformationOutput()
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel owner = new GeneratedProjectModel();
        owner.name("Positions");
        owner.rootClass().className("Position");
        GeneratedClassModel output = new GeneratedClassModel("PositionWithHolders");
        output.baseClassName("Position");
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        owner.addClass(output);
        TransformationClassSource transformation = new TransformationClassSource();
        transformation.inputClassReference(owner.rootClass().declarationId(), "Position");
        transformation.addStep(TransformationStep.filter(
                "holderCount", FilterOperator.GREATER_THAN, 0, null));
        output.transformationSource(transformation);
        PopulationSelection published = new PopulationSelection("PositionsForHistory");
        published.className(output.className());
        published.instanceQids(List.of("Q1", "Q3"));
        owner.addSelection(published);
        new GeneratedProjectModelStore().save(owner, storage.modelFile(owner.name()));
        storage.reconcileConstructs(owner.name(), ConstructInventory.of(owner), "input");

        WikidataDynamicObject held = position("Q1", 7);
        WikidataDynamicObject predecessor = position("Q3", 0); // reached by the expansion
        MaterializedPopulationStore.save(owner, output, List.of(held), storage, "input");
        MaterializedPopulationStore.publish(owner, published, output,
                List.of(held, predecessor), storage, "input");

        assertEquals(List.of("Q1"), MaterializedPopulationStore.load(owner, output, storage)
                        .stream().map(WikidataDynamicObject::getIdentifier).toList(),
                "the transformation's output is still only what its filter kept");
        GeneratedProjectModel consumer = new GeneratedProjectModel();
        consumer.name("History");
        consumer.rootClass().className("OfficeHolding");
        consumer.rootClass().membership(EntityBound.vocabulary("PositionsForHistory"));
        GeneratedClassModel imported = output.copy();
        imported.importedFrom(owner.name());
        consumer.addClass(imported);
        PopulationSelection importedSelection = published.copy();
        importedSelection.importedFrom(owner.name());
        consumer.addSelection(importedSelection);

        ImportedMaterializedPopulationInputs inputs =
                ImportedMaterializedPopulationInputs.resolve(consumer, storage);

        assertEquals(List.of("Q1", "Q3"), inputs.membersFor("PositionWithHolders").stream()
                        .map(WikidataDynamicObject::getIdentifier).toList(),
                "the importer receives the expanded publication");
    }

    @Test void anUnpublishedPopulationIsRefusedEvenWhenTheTransformationRan() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel owner = new GeneratedProjectModel();
        owner.name("Positions");
        owner.rootClass().className("Position");
        GeneratedClassModel output = new GeneratedClassModel("PositionWithHolders");
        output.baseClassName("Position");
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        owner.addClass(output);
        TransformationClassSource transformation = new TransformationClassSource();
        transformation.inputClassReference(owner.rootClass().declarationId(), "Position");
        transformation.addStep(TransformationStep.filter(
                "holderCount", FilterOperator.GREATER_THAN, 0, null));
        output.transformationSource(transformation);
        PopulationSelection published = new PopulationSelection("PositionsForHistory");
        published.className(output.className());
        published.instanceQids(List.of("Q1"));
        owner.addSelection(published);
        new GeneratedProjectModelStore().save(owner, storage.modelFile(owner.name()));
        storage.reconcileConstructs(owner.name(), ConstructInventory.of(owner), "input");
        MaterializedPopulationStore.save(owner, output, List.of(position("Q1", 7)), storage,
                "input");
        GeneratedProjectModel consumer = new GeneratedProjectModel();
        consumer.name("History");
        consumer.rootClass().className("OfficeHolding");
        consumer.rootClass().membership(EntityBound.vocabulary("PositionsForHistory"));
        GeneratedClassModel imported = output.copy();
        imported.importedFrom(owner.name());
        consumer.addClass(imported);
        PopulationSelection importedSelection = published.copy();
        importedSelection.importedFrom(owner.name());
        consumer.addSelection(importedSelection);

        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> ImportedMaterializedPopulationInputs.resolve(consumer, storage));
        assertTrue(refused.getMessage().contains("has not been published"),
                refused.getMessage());
    }

    /** A publication is the selection's typed payload, not a larger cache from which an
     *  importer may silently take a subset. After a selection shrinks, the old artifact
     *  must be refused until its owner republishes it. */
    @Test void extraPublishedRootsAreRefusedAfterTheSelectionShrinks() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel owner = new GeneratedProjectModel();
        owner.name("Positions");
        owner.rootClass().className("Position");
        GeneratedClassModel output = new GeneratedClassModel("PositionWithHolders");
        output.baseClassName("Position");
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        owner.addClass(output);
        TransformationClassSource transformation = new TransformationClassSource();
        transformation.inputClassReference(owner.rootClass().declarationId(), "Position");
        transformation.addStep(TransformationStep.filter(
                "holderCount", FilterOperator.GREATER_THAN, 0, null));
        output.transformationSource(transformation);
        PopulationSelection published = new PopulationSelection("PositionsForHistory");
        published.className(output.className());
        published.instanceQids(List.of("Q1"));
        owner.addSelection(published);
        new GeneratedProjectModelStore().save(owner, storage.modelFile(owner.name()));
        storage.reconcileConstructs(owner.name(), ConstructInventory.of(owner), "input");
        MaterializedPopulationStore.publish(owner, published, output,
                List.of(position("Q1", 7), position("Q2", 3)), storage, "input");

        GeneratedProjectModel consumer = new GeneratedProjectModel();
        consumer.name("History");
        consumer.rootClass().className("OfficeHolding");
        consumer.rootClass().membership(EntityBound.vocabulary("PositionsForHistory"));
        GeneratedClassModel imported = output.copy();
        imported.importedFrom(owner.name());
        consumer.addClass(imported);
        PopulationSelection importedSelection = published.copy();
        importedSelection.importedFrom(owner.name());
        consumer.addSelection(importedSelection);

        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> ImportedMaterializedPopulationInputs.resolve(consumer, storage));

        assertTrue(refused.getMessage().contains("does not exactly match"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("extra 1 (first: Q2)"),
                refused.getMessage());
    }

    private static WikidataDynamicObject position(String qid, int holders) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.assignClass("PositionWithHolders");
        value.put("holderCount", holders);
        return value;
    }
}
