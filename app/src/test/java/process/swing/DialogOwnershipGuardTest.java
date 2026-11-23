package process.swing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dialogs choose modality and focus through the shared {@link Dialogs} lifecycle
 * (CLAUDE.md, "Own dialogs consistently"): a decision/review dialog is document-modal to
 * the workbench that opened it and raised when opened; an inspection-only window may be
 * modeless. A dialog constructed directly decides that locally, which is how one review
 * blocked every application while another left its own workbench editable behind it.
 *
 * <p>The allow-list is the direct constructions that existed when the rule was written.
 * It only shrinks: converting one to {@link Dialogs} requires lowering its count here, and
 * a new direct construction fails.</p>
 */
class DialogOwnershipGuardTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final String SHARED = "process/swing/Dialogs.java";

    private static final Map<String, Integer> REMAINING = Map.of(
            // A decision/review dialog: APPLICATION_MODAL in showDialog and MODELESS in
            // showModeless, both against the rule. Converting it changes its callers'
            // control flow, so it is a known violation, not an accepted exception.
            "domain/enrichment/ui/CategorizedReviewPanel.java", 1,
            "domain/transform/ui/TransformWorkbenchPanel.java", 6,
            "domain/workbench/modelbuilder/ui/ClassSourcePanel.java", 2,
            "domain/workbench/modelbuilder/ui/FieldSourcePanel.java", 1,
            "domain/workbench/modelbuilder/ui/PropertyDiscoveryPanel.java", 4,
            "domain/workbench/modelbuilder/ui/SubclassDiscoveryPanel.java", 1,
            "domain/workbench/ui/ExploreByExamplePanel.java", 3,
            "domain/workbench/ui/RelationRoutesInput.java", 1,
            "domain/workbench/ui/SelectionsButton.java", 1,
            "domain/workbench/ui/SourceDiscoveryPicker.java", 1);

    @Test void dialogsAreCreatedThroughTheSharedLifecycle() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = SOURCES.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/');
                if (relative.equals(SHARED)) continue;
                int count = occurrences(Files.readString(file), "new JDialog(");
                if (count > 0) found.put(relative, count);
            }
        }

        assertEquals(new TreeMap<>(REMAINING), found,
                "create dialogs through process.swing.Dialogs (reviewDialog for decisions); "
                        + "when converting an existing one, lower its count in REMAINING");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0;
             at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}
