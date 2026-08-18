package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
}
