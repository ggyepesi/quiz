package quiz.curation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A merge carries the Wikidata identity, since identity lives in the curation now. */
class ManualCurationMergeIdentityTest {

    private static ManualCuration curation(Path dir) {
        return new ManualCuration(dir.resolve("x.curation.json").toFile());
    }

    private static IdentityLink wikidata(String type, String targetId, String qid) {
        return new IdentityLink(type, targetId, "Wikidata", qid,
                "https://www.wikidata.org/wiki/" + qid, qid, "test");
    }

    private static String qidFor(ManualCuration c, String targetId) {
        return c.identityLinks().stream()
                .filter(l -> l.targetId().equals(targetId))
                .map(IdentityLink::sourceId).findFirst().orElse(null);
    }

    @Test void primaryKeepsItsOwnIdentityAndTheLoserLinkIsDropped(@TempDir Path dir) {
        ManualCuration c = curation(dir);
        c.putIdentityLink(wikidata("Country", "france", "Q142"));
        c.putIdentityLink(wikidata("Country", "france-dup", "Q9999"));

        c.putMerge("Country", "france", "france-dup", Map.of());

        assertEquals("Q142", qidFor(c, "france"), "primary wins when both had an identity");
        assertNull(qidFor(c, "france-dup"), "the loser's link is dropped");
    }

    @Test void survivorInheritsTheSecondaryIdentityWhenItHadNone(@TempDir Path dir) {
        ManualCuration c = curation(dir);
        c.putIdentityLink(wikidata("Country", "tanzania-dup", "Q924"));

        c.putMerge("Country", "tanzania", "tanzania-dup", Map.of());

        assertEquals("Q924", qidFor(c, "tanzania"),
                "the survivor inherits the secondary's identity");
        assertNull(qidFor(c, "tanzania-dup"), "the loser's link is dropped");
    }

    @Test void neitherHadIdentityLeavesNoDanglingLink(@TempDir Path dir) {
        ManualCuration c = curation(dir);

        c.putMerge("Country", "a", "b", Map.of());

        assertNull(qidFor(c, "a"));
        assertNull(qidFor(c, "b"));
        assertTrue(c.identityLinks().isEmpty());
    }
}
