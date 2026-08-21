package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityAliasSnapshotTest {

    @Test void aliasesSurviveTheFlattenedSnapshotBoundary(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "One");
        person.type("Person");
        person.aliases(List.of("First alias", "Second alias"));

        var file = dir.resolve("snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(person), file);

        assertEquals(List.of("First alias", "Second alias"),
                store.load(file).getFirst().aliases());
    }

    @Test void categoryEvidenceSurvivesWithoutBecomingADomainField(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject movie = new WikidataDynamicObject("Q157058", "Blood Diamond");
        movie.type("Movie");
        var document = new datasource.evidence.SourceDocument(
                "Wikipedia (English)", "Blood Diamond", "Blood Diamond",
                "https://en.wikipedia.org/wiki/Blood_Diamond", "987654",
                new datasource.evidence.ContentDigest("sha256", "abc"),
                "2026-08-21T12:00:00Z");
        movie.categoryMemberships(List.of(
                new datasource.evidence.CategoryMembership(
                        "Films set in Sierra Leone", document),
                new datasource.evidence.CategoryMembership(
                        "Films set in 1999", document)));

        var file = dir.resolve("snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(movie), file);
        WikidataDynamicObject loaded = store.load(file).getFirst();

        assertEquals("Films set in Sierra Leone",
                loaded.categoryMemberships().getFirst().category());
        org.junit.jupiter.api.Assertions.assertTrue(loaded.categoryMemberships().getFirst()
                .document().versionId().startsWith("revision:987654;"));
        org.junit.jupiter.api.Assertions.assertEquals(null, loaded.get("categoryMemberships"),
                "source facts stay outside the rendered field graph");
        String json = Files.readString(file.toPath());
        assertEquals(1, occurrences(json, "\"wikipediaCategoryDocument\""),
                "one page document is shared by all of its category memberships");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"categoryMemberships\":"),
                "the snapshot must not fall back to the document-per-category shape");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }
}
