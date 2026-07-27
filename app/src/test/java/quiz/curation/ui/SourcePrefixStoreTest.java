package quiz.curation.ui;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourcePrefixStoreTest {

    @Test
    void remembersCustomKindAndPrefix() throws Exception {
        Preferences node = Preferences.userRoot().node(
                "quiz-test/source-prefixes/" + UUID.randomUUID());
        try {
            SourcePrefixStore store = new SourcePrefixStore(node);
            store.remember("Museum archive", "https://museum.example/people/");

            assertTrue(store.kinds().contains("Museum archive"));
            assertEquals("https://museum.example/people/", store.prefix("Museum archive"));
            assertEquals("https://museum.example/people/42",
                    SourcePrefixStore.build(store.prefix("Museum archive"), "42"));
        } finally {
            node.removeNode();
        }
    }

    @Test
    void infersPrefixAndValidatesOnlyWebUrls() {
        assertEquals("https://example.org/record/",
                SourcePrefixStore.inferPrefix("https://example.org/record/A12", "A12"));
        assertNull(SourcePrefixStore.validationError("https://example.org/record/A12"));
        assertTrue(SourcePrefixStore.validationError("file:///tmp/A12").contains("http"));
    }
}
