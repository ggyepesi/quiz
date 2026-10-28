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
        MaterializedPopulationStore.save(owner, output, List.of(typed), storage, "input");

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
}
