package wikidata.explore.build;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.GeneratedProjectModelValidator;
import wikidata.explore.model.PopulationSelection;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalPositionsBuildConfigTest {
    @Test void checkedInModelDeclaresItsReproducibleTransformationPipeline()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(new File(
                "../data/wikidata/historicalpositions/historicalpositions.model.json"));

        assertTrue(GeneratedProjectModelValidator.validate(model).valid(),
                GeneratedProjectModelValidator.validate(model).format());
        var output = model.findClass("PositionWithHolders");
        assertTrue(output.materializedByTransformation());
        assertNotNull(output.transformationSource());
        assertEquals("holderCount",
                output.transformationSource().steps().getFirst().fieldPath());
        PopulationSelection population = (PopulationSelection)
                model.findSelection("PositionWithHoldersPopulation");
        assertEquals(output.className(), population.className());
        assertEquals(output.declarationId(), model.buildOperations().stream()
                .filter(operation -> operation.kind()
                        == BuildOperation.Kind.RUN_TRANSFORMATION)
                .findFirst().orElseThrow().targetDeclarationId());
        assertEquals(List.of(
                        BuildOperation.Kind.GENERATE_PROJECT,
                        BuildOperation.Kind.RUN_GRAPH_CONSTRAINT,
                        BuildOperation.Kind.APPLY_GRAPH_DECISIONS,
                        BuildOperation.Kind.RUN_TRANSFORMATION,
                        BuildOperation.Kind.RUN_GRAPH_CONSTRAINT,
                        BuildOperation.Kind.APPLY_GRAPH_DECISIONS,
                        BuildOperation.Kind.GENERATE_PROJECT,
                        BuildOperation.Kind.RUN_TRANSFORMATION,
                        BuildOperation.Kind.APPLY_GRAPH_DECISIONS,
                        BuildOperation.Kind.PUBLISH_POPULATION,
                        BuildOperation.Kind.SAVE_PROJECT_RESULT),
                model.buildOperations().stream().map(BuildOperation::kind).toList());
    }

    @Test void historyBuildsItsOwningPositionModelBeforeGeneration() throws Exception {
        GeneratedProjectModel history = new GeneratedProjectModelStore().load(new File(
                "../data/wikidata/history/history.model.json"));

        assertEquals(List.of(
                        BuildOperation.Kind.BUILD_DEPENDENCY,
                        BuildOperation.Kind.GENERATE_PROJECT,
                        BuildOperation.Kind.SAVE_PROJECT_RESULT),
                history.buildOperations().stream().map(BuildOperation::kind).toList());
        assertEquals("Historical Positions",
                history.buildOperations().getFirst().targetProjectName());
    }
}
