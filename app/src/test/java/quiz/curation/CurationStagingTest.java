package quiz.curation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurationStagingTest {

    @Test void pendingEditsAreSharedButDetachedUntilApply(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sample.curation.json");
        ManualCuration durable = new ManualCuration(file.toFile());
        CurationStaging firstPanel = CurationStaging.forCuration(durable);
        CurationStaging secondPanel = CurationStaging.forCuration(durable);
        assertSame(firstPanel, secondPanel);

        firstPanel.stage(new Correction(
                "Country", "manual-1", "capital", "Example City",
                Correction.MANUAL, null, CorrectionPolicy.REPLACE, null));
        secondPanel.stage(new IdentityLink(
                "Country", "manual-1", "Wikidata", "Q1",
                "https://www.wikidata.org/wiki/Q1", "Universe", "manual"));

        assertEquals(2, firstPanel.size());
        assertTrue(durable.corrections().isEmpty());
        assertTrue(durable.identityLinks().isEmpty());

        // Even an unrelated save cannot leak the detached records onto disk.
        durable.save();
        ManualCuration beforeApply = new ManualCuration(file.toFile()).load();
        assertTrue(beforeApply.corrections().isEmpty());
        assertTrue(beforeApply.identityLinks().isEmpty());

        secondPanel.apply();
        ManualCuration applied = new ManualCuration(file.toFile()).load();
        assertEquals(1, applied.corrections().size());
        assertEquals(1, applied.identityLinks().size());
        assertEquals(0, firstPanel.size());
    }

    @Test void identityApplyLeavesFieldCorrectionsStaged(@TempDir Path dir) throws Exception {
        ManualCuration durable = new ManualCuration(
                dir.resolve("identity-only.curation.json").toFile());
        CurationStaging staging = CurationStaging.forCuration(durable);
        staging.stage(new Correction(
                "Movie", "local-1", "year", "1999", Correction.MANUAL,
                null, CorrectionPolicy.REPLACE, null));
        staging.stage(new IdentityLink(
                "Movie", "local-1", "Wikidata", "Q1",
                "https://www.wikidata.org/wiki/Q1", "Movie", "manual"));

        staging.applyIdentityLinks();

        ManualCuration saved = new ManualCuration(durable.file()).load();
        assertEquals(1, saved.identityLinks().size());
        assertTrue(saved.corrections().isEmpty());
        assertEquals(1, staging.corrections().size());
        assertTrue(staging.identityLinks().isEmpty());
    }
}
