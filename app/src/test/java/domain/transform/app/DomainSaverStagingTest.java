package domain.transform.app;

import domain.curation.CuratedInstance;
import domain.curation.CurationStaging;
import domain.curation.ManualCuration;
import domain.runtime.SnapshotDomain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save domain used to serialize the live preview of a staged source-derived instance
 * without saving its typed curation declaration. After reload the reference survived as
 * a generic object and disappeared from its configured group. The durable curation
 * boundary must therefore be crossed before the snapshot boundary.
 */
class DomainSaverStagingTest {

    @Test void saveDomainRefusesAStagedInstanceUntilCurationIsSaved(
            @TempDir Path directory) {
        ManualCuration curation = new ManualCuration(
                directory.resolve("assessors.curation.json").toFile());
        CuratableDomain domain = new CuratableDomain(
                new SnapshotDomain(List.of()), curation);
        CurationStaging.forCuration(curation).stage(new CuratedInstance(
                "AssessorOfMaat", "wikipedia-table-row:1", "First assessor",
                List.of(), null));

        DomainSaver saver = new DomainSaver();
        String disclosure = saver.describeSave("Egyptian Gods", List.of(), domain);
        assertTrue(disclosure.contains("Not ready to save: accepted changes are still "
                        + "pending because they were not saved"),
                disclosure);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> saver.save("Egyptian Gods", List.of(), domain));
        assertTrue(refused.getMessage().contains("Use \"Save pending changes\""),
                refused.getMessage());
        assertFalse(curation.file().isFile(),
                "the refused domain save must not cross the curation boundary");
    }
}
