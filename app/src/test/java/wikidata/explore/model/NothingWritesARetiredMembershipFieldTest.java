package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class's membership has one home, and the three fields it left are not it.
 *
 * <p>They still exist on {@link FieldSourceMapping} because a FIELD uses them — its own
 * property, and the QIDs its values may take. What must not come back is a class writing
 * or reading them as a membership: two editor actions kept doing exactly that after the
 * bound arrived, so "use this entity as the type" and "add these relation targets" set
 * fields nothing reads and left the class unbounded, with the log reporting success.
 *
 * <p>A guard test rather than a review habit, because the mistake is invisible: the
 * fields are still there, the setters still work, and the model simply does not change.
 */
class NothingWritesARetiredMembershipFieldTest {

    private static final List<String> RETIRED = List.of(
            "instanceMapping().sourceQid",
            "instanceMapping().propertyPid",
            "instanceMapping().additionalTypeQids",
            "sourceMapping().sourceQid",
            "sourceMapping().additionalTypeQids");

    @Test void noProductionCodeUsesAClassMappingAsItsMembership() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) root = Path.of("app/src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.stripLeading().startsWith("//")
                            || line.stripLeading().startsWith("*")) {
                        continue;
                    }
                    for (String retired : RETIRED) {
                        if (line.contains(retired)) {
                            offenders.add(file.getFileName() + ":" + (i + 1)
                                    + "  " + line.strip());
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "a class's membership is GeneratedClassModel.membership(); these read or "
                        + "write the fields it replaced:\n" + String.join("\n", offenders));
    }
}
