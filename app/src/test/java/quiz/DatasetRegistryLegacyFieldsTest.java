package quiz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry on disk outlives the fields this class happens to declare, and it is
 * rewritten by the app rather than kept in version control — so a machine can hold one
 * written months and several field-sets ago.
 *
 * <p>{@code instanceCount} was dropped because it had two writers, no readers and three
 * different meanings across ten entries: five recorded the entity total, three the roots,
 * one the number of GROUPS (the US presidents dataset read 6 for 45 presidents), and one
 * matched nothing in its snapshot at all. Dropping a field must not stop an existing file
 * loading, or removing one means editing every machine's registry by hand before the app
 * will start.
 */
class DatasetRegistryLegacyFieldsTest {

    @Test void aRegistryWrittenWithFieldsThatNoLongerExistStillLoads(@TempDir Path dir)
            throws Exception {
        File file = dir.resolve("datasets.json").toFile();
        Files.writeString(file.toPath(), """
                {
                  "datasets" : [ {
                    "name" : "\uD83C\uDDFA\uD83C\uDDF8 USPresidents",
                    "key" : "uspresidents",
                    "rootClass" : "President",
                    "snapshotPath" : "data/wikidata/transform/uspresidents.snapshot.json",
                    "types" : [ "President" ],
                    "instanceCount" : 6,
                    "savedAt" : "2026-08-12T19:07:00",
                    "someFieldFromAFutureVersion" : true
                  } ]
                }
                """);

        DatasetRegistry registry = DatasetRegistry.load(file);

        assertEquals(1, registry.datasets().size(),
                "an unknown property is ignored, not a reason to lose the registry");
        DatasetRegistry.Dataset only = registry.datasets().getFirst();
        assertEquals("President", only.rootClass());
        assertTrue(only.snapshotPath().endsWith("uspresidents.snapshot.json"));
    }
}
