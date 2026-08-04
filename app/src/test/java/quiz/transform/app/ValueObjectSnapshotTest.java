package quiz.transform.app;

import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;
import quiz.ValueObject;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@link ValueObject} is serialized INLINE in its parent, not a pooled qid-keyed
 * entity — so two DISTINCT values with a colliding identifier stay distinct (the Nobel
 * Motivation bug, where a constant id merged every motivation into one and cross-
 * contaminated its topics).
 */
class ValueObjectSnapshotTest {

    /** A value with a deliberately COLLIDING constant id — the failure mode we fix. */
    static final class Note extends ViewableAdapter implements ValueObject {
        private final String text;
        Note(String text) { this.text = text; }
        @Override public String getIdentifier() { return "note"; }   // same for all!
        @Override public String getDisplayName() { return text; }
    }

    static final class Item extends ViewableAdapter {
        private final String key;
        private final Note note;
        Item(String key, Note note) { this.key = key; this.note = note; }
        @Override public String getIdentifier() { return key; }
        @Override public String getDisplayName() { return key; }
    }

    @Test void valueObjectsInlineAndDoNotMerge() throws Exception {
        List<WikidataDynamicObject> pool = ViewableToWdo.pool(List.of(
                new Item("A", new Note("first note")),
                new Item("B", new Note("second note"))));

        File f = File.createTempFile("value-object", ".json");
        f.deleteOnExit();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(pool, f);

        List<WikidataDynamicObject> loaded = store.loadAll(f);

        // The value is inlined — never a pooled "Note" entity.
        assertTrue(loaded.stream().noneMatch(o -> "Note".equals(o.typeName())),
                "value objects must not be pooled as entities");

        Map<String, WikidataDynamicObject> items = loaded.stream()
                .filter(o -> "Item".equals(o.typeName()))
                .collect(Collectors.toMap(WikidataDynamicObject::getIdentifier, o -> o));
        assertEquals(2, items.size());

        // Each item keeps ITS OWN note — not one merged value shared by both.
        WikidataDynamicObject noteA = (WikidataDynamicObject) items.get("A").get("note");
        WikidataDynamicObject noteB = (WikidataDynamicObject) items.get("B").get("note");
        assertTrue(noteA.isValueObject());
        assertEquals("first note", noteA.get("text"));
        assertEquals("second note", noteB.get("text"));
    }
}
