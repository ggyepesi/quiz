package wikidata.explore.build;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import work.QueryContext;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build step must never read a membership an earlier step has silently discarded.
 *
 * <p>Generate replaces the whole run and carries forward only graph results that ADD members,
 * so it discards every narrowing applied before it. Historical Positions generated a second
 * time (to acquire accepted predecessor positions) and then ran its holder-count filter over
 * the whole Position population, because nothing re-applied GraphConstraint's narrowing —
 * PositionWithHolders would have taken in positions the graph had rejected. A transformation
 * likewise rebuilds its output class, discarding graph memberships added to it.
 */
class BuildOrderTest {
    @TempDir Path root;

    private static final File HISTORICAL_POSITIONS = new File(
            "../data/wikidata/historicalpositions/historicalpositions.model.json");
    /** The Apply GraphConstraint that follows the second Generate. */
    private static final String REAPPLY_NARROWING = "decl:7f6a2bef-7b20-4026-8f49-ef96dff1fc0f";
    /** The Apply PositionReplacementExpansion that follows the second transformation. */
    private static final String REAPPLY_EXPANSION = "decl:29211bb5-fce1-4da0-8d4f-508b44e0042e";

    @Test void everyShippedBuildKeepsTheGraphResultsItApplies() throws Exception {
        File[] models = new File("../data/wikidata").listFiles();
        List<String> misordered = new ArrayList<>();
        for (File directory : models == null ? new File[0] : models) {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".model.json"));
            for (File file : files == null ? new File[0] : files) {
                GeneratedProjectModel model = new GeneratedProjectModelStore().load(file);
                ProjectBuild.orderProblems(model).values()
                        .forEach(problem -> misordered.add(model.name() + ": " + problem));
            }
        }
        assertEquals(List.of(), misordered);
    }

    @Test void generatingAgainDiscardsANarrowingThatIsNotAppliedAgain() throws Exception {
        GeneratedProjectModel model = without(REAPPLY_NARROWING);

        Map<String, String> problems = ProjectBuild.orderProblems(model);

        BuildOperation transformation = model.buildOperations().stream()
                .filter(value -> value.kind() == BuildOperation.Kind.RUN_TRANSFORMATION)
                .reduce((first, second) -> second).orElseThrow();
        String problem = problems.get(transformation.declarationId());
        assertTrue(problem != null && problem.contains("GraphConstraint")
                        && problem.contains("Generate project"),
                "the second filter would read the un-narrowed Position population: " + problems);
    }

    @Test void aTransformationDiscardsMembershipsAGraphAddedToItsOutput() throws Exception {
        GeneratedProjectModel model = without(REAPPLY_EXPANSION);

        Map<String, String> problems = ProjectBuild.orderProblems(model);

        BuildOperation publish = model.buildOperations().stream()
                .filter(value -> value.kind() == BuildOperation.Kind.PUBLISH_POPULATION)
                .findFirst().orElseThrow();
        String problem = problems.get(publish.declarationId());
        assertTrue(problem != null && problem.contains("PositionReplacementExpansion"),
                "publishing would drop the accepted predecessors: " + problems);
    }

    @Test void anAddedMembershipSurvivesGenerationAsPendingInput() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(HISTORICAL_POSITIONS);

        // Apply PositionReplacementExpansion (ADD) is followed by Generate and nothing about
        // the ADD graph is flagged there: Generate acquires its accepted identities.
        assertEquals(Map.of(), ProjectBuild.orderProblems(model));
    }

    @Test void aMisorderedBuildIsRefusedBeforeAnyStepRuns() throws Exception {
        GeneratedProjectModel model = without(REAPPLY_NARROWING);

        ProjectBuild.Report report = ProjectBuild.run(
                model, DomainStorage.in(root.toFile()), new QueryContext(), null);

        assertEquals(ProjectBuild.State.FAILED, report.outcome());
        assertTrue(report.steps().stream().noneMatch(
                        step -> step.action() == ProjectBuild.Action.RAN),
                "no Wikidata generation is spent on a build that cannot use it");
        assertTrue(report.steps().stream().anyMatch(step ->
                step.action() == ProjectBuild.Action.STOPPED
                        && step.detail().contains("GraphConstraint")));
    }

    @Test void publishingWithoutASaveIsRefusedBeforeAnythingRuns() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        wikidata.explore.model.PopulationSelection population =
                new wikidata.explore.model.PopulationSelection("Published");
        population.className(model.rootClass().className());
        model.addSelection(population);
        BuildOperation publish = new BuildOperation(
                BuildOperation.Kind.PUBLISH_POPULATION, population.declarationId());
        model.buildOperations(List.of(publish));

        ProjectBuild.Report report = ProjectBuild.run(
                model, DomainStorage.in(root.toFile()), new QueryContext(), null);

        assertEquals(ProjectBuild.State.FAILED, report.outcome());
        assertEquals(ProjectBuild.Action.STOPPED, report.steps().getFirst().action());
        assertTrue(report.steps().getFirst().detail().contains(
                "must be followed by Save project"));
    }

    /**
     * A publication is labelled with its class's current transformation signature, so its
     * members must come from that transformation in the same build. Publishing straight from
     * the saved snapshot after editing the filter would label the old filter's members as the
     * new filter's output, and an importer would accept them as current.
     */
    @Test void publishingWithoutRunningTheTransformationSinceGenerateIsRefused() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(HISTORICAL_POSITIONS);
        List<BuildOperation> operations = new ArrayList<>(model.buildOperations());
        int lastGenerate = -1;
        for (int i = 0; i < operations.size(); i++) {
            if (operations.get(i).kind() == BuildOperation.Kind.GENERATE_PROJECT) lastGenerate = i;
        }
        int after = lastGenerate;
        operations.removeIf(operation -> operation.kind()
                == BuildOperation.Kind.RUN_TRANSFORMATION
                && operations.indexOf(operation) > after);
        model.buildOperations(operations);
        BuildOperation publish = operations.stream()
                .filter(operation -> operation.kind() == BuildOperation.Kind.PUBLISH_POPULATION)
                .findFirst().orElseThrow();

        String problem = ProjectBuild.orderProblems(model).get(publish.declarationId());

        assertTrue(problem != null && problem.contains("without running its transformation"),
                String.valueOf(problem));
    }

    private static GeneratedProjectModel without(String operationId) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(HISTORICAL_POSITIONS);
        List<BuildOperation> operations = new ArrayList<>(model.buildOperations());
        assertTrue(operations.removeIf(value -> value.declarationId().equals(operationId)),
                "the shipped build has the step this test removes: " + operationId);
        model.buildOperations(operations);
        return model;
    }
}
