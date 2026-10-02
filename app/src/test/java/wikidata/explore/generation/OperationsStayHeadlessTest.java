package wikidata.explore.generation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A project build operation runs without a window (directive 22): Swing, AWT, the
 * ModelBuilder workbench and TransformApp's UI are not reachable from its source.
 *
 * <p>Save lived inside the ModelBuilder frame, so no build could save a project without
 * opening one. This guard holds the classes that now make up the save operation. It reads
 * the whole source rather than only the imports, because classes are often written fully
 * qualified here, and an import scan would see none of them. It does not follow what
 * those classes call in turn; a forbidden dependency reached only transitively is not
 * caught here.
 */
class OperationsStayHeadlessTest {

    private static final Path SOURCE = Path.of("src/main/java/wikidata/explore/generation");
    private static final List<String> OPERATIONS = List.of(
            "ProjectSave", "DomainSave", "DomainCounts", "GraphDiscoveryResultStore",
            "GenerationRuns", "GraphApplication");
    private static final Pattern UI = Pattern.compile(
            "\\b(javax\\.swing|java\\.awt|wikidata\\.explore\\.workbench|quiz\\.transform\\.ui"
                    + "|process\\.swing)\\.(?:\\*|\\w[\\w.]*)");
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    @Test void saveOperationsReachNoUserInterface() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (String operation : OPERATIONS) {
            String code = COMMENT.matcher(
                    Files.readString(SOURCE.resolve(operation + ".java"))).replaceAll("");
            Matcher matcher = UI.matcher(code);
            while (matcher.find()) offenders.add(operation + " uses " + matcher.group());
        }

        assertEquals(Set.of(), offenders,
                "a build operation must run with no UI present; the UI calls it, not the reverse");
    }
}
