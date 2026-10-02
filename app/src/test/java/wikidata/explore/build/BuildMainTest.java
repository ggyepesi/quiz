package wikidata.explore.build;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command-line build says plainly what it read and what it would do, and its exit code
 * says how it ended — a script can tell a build waiting for a person from a failed one.
 */
class BuildMainTest {

    @TempDir Path root;

    @Test void thePlanNamesTheModelReadAndEveryStep() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = saved(storage, List.of(
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = BuildMain.run(new String[] {model.name(), "--plan"}, storage,
                () -> { throw new AssertionError("a plan reaches no network"); },
                new PrintStream(out, true, StandardCharsets.UTF_8));

        String printed = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(printed.contains("Reading model " + storage.modelFile(model.name()).getPath()),
                printed);
        assertTrue(printed.contains("Generate project: MISSING"), printed);
        assertTrue(printed.contains("Save project: STALE — because Generate project runs"), printed);
    }

    @Test void aProjectWithNoBuildSaysWhereToAuthorIt() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = saved(storage, List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = BuildMain.run(new String[] {model.name()}, storage, () -> null,
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("project overview (Build)"));
    }

    @Test void usageAndAnUnknownProjectAreUsageProblems() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream());

        assertEquals(2, BuildMain.run(new String[0], storage, () -> null, quiet));
        assertEquals(2, BuildMain.run(new String[] {"Nowhere"}, storage, () -> null, quiet));
    }

    @Test void theExitCodeSaysHowTheBuildEnded() {
        assertEquals(0, BuildMain.exitCode(ProjectBuild.State.CURRENT));
        assertEquals(3, BuildMain.exitCode(ProjectBuild.State.AWAITING_DECISION));
        assertEquals(4, BuildMain.exitCode(ProjectBuild.State.INCOMPLETE));
        assertEquals(1, BuildMain.exitCode(ProjectBuild.State.FAILED));
    }

    private static GeneratedProjectModel saved(DomainStorage storage,
                                               List<BuildOperation> build) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Stars");
        model.rootClass().className("Star");
        model.buildOperations(build);
        new GeneratedProjectModelStore().save(model, storage.modelFile(model.name()));
        return model;
    }
}
