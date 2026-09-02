package wikidata.explore.query.swing;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunInspectorFrameTest {

    @Test void theOpenDialogStartsInTheCurrentSnapshotDirectory() throws Exception {
        Path directory = Files.createTempDirectory("run-inspector");

        assertEquals(directory.toAbsolutePath().normalize(),
                RunInspectorFrame.usableDirectory(directory));
    }
}
