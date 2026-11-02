package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project replacement is also the lifecycle boundary for every project-owned view. */
class ProjectWindowLifecycleTest {

    @Test void everyProjectReplacementAndAuxiliaryWindowUsesOneLifecycleBoundary()
            throws Exception {
        String source = Files.readString(sourceFile());
        String close = source.substring(
                source.indexOf("private void closeProjectWindows()"),
                source.indexOf("private static void closeWindow"));

        var windows = Pattern.compile("private JFrame (\\w+);").matcher(source);
        while (windows.find()) {
            String field = windows.group(1);
            assertTrue(close.contains("closeWindow(" + field + ")")
                            || close.contains("discardNameCollisionInspection()")
                            && field.equals("nameCollisionsWindow"),
                    field + " must close at the project boundary");
        }
        assertTrue(close.contains("getOwnedWindows()"),
                "modeless plan/running/results dialogs are project-owned too");
        assertTrue(close.contains("logWindow.resetForProjectSwitch()"),
                "query logs must close and forget the old project's rows");
        assertEquals(1, occurrences(source, "projectModel.copyContentsFrom("),
                "every project replacement must go through replaceOpenProject");

        String synchronizedBuild = source.substring(
                source.indexOf("private void synchronizeCompletedBuild("),
                source.indexOf("/** The build's report as result tabs"));
        assertTrue(source.contains("synchronizeCompletedBuild(modelFile, read)"),
                "a successful build must synchronize without waiting for Apply");
        assertTrue(source.contains("readBuiltProject(modelFile, context::message)"),
                "the built files are read as the build's own last phase, in its worker");
        assertTrue(synchronizedBuild.contains("installOpenProject(built.model(), built.ledger())"));
        assertTrue(synchronizedBuild.contains(
                "installSavedInstances(built.snapshot(), built.loaded(), false)"));
        String install = synchronizedBuild.substring(
                synchronizedBuild.indexOf("private void synchronizeCompletedBuild("));
        assertFalse(install.contains("ProjectLoad.load(") || install.contains("Store().load("),
                "installing on the event thread must not read the model or snapshot");
        assertTrue(synchronizedBuild.contains("markSaved(modelFile)"),
                "the synchronized editor must agree that it matches the saved build");
    }

    private static int occurrences(String value, String sought) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(sought, at)) >= 0; at += sought.length()) {
            count++;
        }
        return count;
    }

    private static Path sourceFile() {
        Path module = Path.of("src/main/java/wikidata/explore/workbench/ModelBuilderFrame.java");
        return Files.isRegularFile(module) ? module : Path.of(
                "app/src/main/java/wikidata/explore/workbench/ModelBuilderFrame.java");
    }
}
