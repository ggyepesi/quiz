package wikidata.explore.query.swing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowLogWindowFilenameTest {
    @TempDir Path directory;

    @Test void suggestsTheFirstDomainSpecificLog() {
        assertEquals(directory.resolve("query-log-oscarnominations-1.txt"),
                WorkflowLogWindow.suggestedLogPath(directory, "OscarNominations"));
    }

    @Test void advancesPastTextAndCompanionArtifactsWithoutFillingOldGaps()
            throws Exception {
        Files.createFile(directory.resolve("query-log-movies-1.txt"));
        Files.createFile(directory.resolve("query-log-movies-3.run.json"));
        Files.createFile(directory.resolve("query-log-other-99.txt"));

        assertEquals(directory.resolve("query-log-movies-4.txt"),
                WorkflowLogWindow.suggestedLogPath(directory, "Movies"));
    }

    @Test void sanitizesAConfiguredDomainName() {
        assertEquals(directory.resolve("query-log-history-people-1.txt"),
                WorkflowLogWindow.suggestedLogPath(directory, " History / People "));
    }

    // --- the domain is stated, not recovered from the display title ---------

    @Test void aDomainWhoseNameContainsTheSeparatorSurvives() {
        // The title used to be split on " — " to recover the domain, which a domain
        // carrying that separator in its own name cannot survive.
        WorkflowLogWindow window = new WorkflowLogWindow();
        window.registerPipeline("Generate domain — Rulers — Hungary", pipeline(),
                "Rulers — Hungary", directory);

        assertEquals("Rulers — Hungary", window.destination().domain());
        assertEquals(directory.resolve("query-log-rulers-hungary-1.txt"),
                WorkflowLogWindow.suggestedLogPath(
                        directory, window.destination().domain()));
    }

    @Test void nameAndFolderComeFromTheSameRun() {
        // Scanning for them separately could name a log after the newest run while
        // writing it into an older run's folder.
        WorkflowLogWindow window = new WorkflowLogWindow();
        window.registerPipeline("Generate domain — Movies", pipeline(),
                "Movies", directory.resolve("movies"));
        window.registerPipeline("Enrich — Constellations", pipeline(),
                "Constellations", null);

        WorkflowLogWindow.RegisteredPipeline destination = window.destination();
        assertEquals("Constellations", destination.domain(),
                "the most recent run that says where it belongs");
        assertNull(destination.snapshotDirectory(),
                "and its folder, rather than the previous run's");
    }

    @Test void aRunThatNamesNoDomainIsNotADestination() {
        WorkflowLogWindow window = new WorkflowLogWindow();
        window.registerPipeline("Ad-hoc query", pipeline());

        assertNull(window.destination());
        assertEquals(directory.resolve("query-log-domain-1.txt"),
                WorkflowLogWindow.suggestedLogPath(directory, ""),
                "an unnamed run still gets a usable filename");
    }

    @Test void terminalStatusRefreshesEvenWhenTheReaderScrolledUp() {
        assertFalse(WorkflowLogWindow.refreshFully(false, work.LogStatus.RUNNING, false));
        assertTrue(WorkflowLogWindow.refreshFully(false, work.LogStatus.OK, false));
        assertTrue(WorkflowLogWindow.refreshFully(false, work.LogStatus.FAILED, false));
        assertTrue(WorkflowLogWindow.refreshFully(true, work.LogStatus.RUNNING, false));
        assertTrue(WorkflowLogWindow.refreshFully(
                false, work.LogStatus.RUNNING, true),
                "a completed child refreshes while its workflow continues");
    }

    private static process.ProcessWorkflowPipeline pipeline() {
        return new process.ProcessWorkflowPipeline(java.util.List.of());
    }
}
