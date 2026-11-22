package domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Forces the package ownership and executable conventions documented in CLAUDE.md. */
class PackageOwnershipGuardTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^package\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final List<String> RETIRED = List.of(
            "datasource", "discovery", "commons.discovery", "pageviews.discovery",
            "wikipedia.discovery", "workbench", "quiz.datasource", "quiz.enrichment",
            "quiz.source", "quiz.curation", "quiz.transform", "quiz.group",
            "wikidata.explore.model", "wikidata.explore.compiled",
            "wikidata.explore.extract", "wikidata.explore.transform",
            "wikidata.explore.rule", "wikidata.explore.codegen",
            "wikidata.explore.generation", "wikidata.explore.build",
            "wikidata.explore.workbench", "wikidata.explore.advisor",
            "wikidata.explore.analysis", "wikidata.explore.query.core",
            "wikidata.explore.query.template", "wikidata.explore.query.swing",
            "wikidata.explore.query.workflow");

    @Test void movedConceptsCannotReturnToTheirOldOwners() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Source source : sources()) {
            for (String retired : RETIRED) {
                if (source.packageName().equals(retired)
                        || source.packageName().startsWith(retired + ".")) {
                    violations.add(source.relative() + " declares " + source.packageName());
                }
            }
        }
        assertEquals(List.of(), violations, "retired package owners must stay empty");
    }

    @Test void domainSourcesMatchTheirDirectoriesAndDoNotDependOnQuiz() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Source source : sources()) {
            if (!source.packageName().equals("domain")
                    && !source.packageName().startsWith("domain.")) continue;
            String expected = source.relative().getParent().toString()
                    .replace(java.io.File.separatorChar, '.');
            if (!expected.equals(source.packageName())) {
                violations.add(source.relative() + " declares " + source.packageName()
                        + " instead of " + expected);
            }
            if (source.text().contains("import quiz.")
                    || Pattern.compile("\\bquiz\\.[A-Z]").matcher(source.text()).find()) {
                violations.add(source.relative() + " depends on a quiz-owned type");
            }
        }
        assertEquals(List.of(), violations,
                "domain owns its runtime and operations; quiz is a consumer");
    }

    /** Two demos under wikidata.explore once built on ModelBuilder UI and quiz OCR; a demo
     *  belongs to the owner of what it demonstrates, not to the protocol client it calls. */
    @Test void lowLevelRootsKnowNeitherDomainWorkbenchUiNorQuiz() throws IOException {
        List<String> roots = List.of("wikidata", "wikipedia", "batch", "process", "work");
        Pattern forbidden = Pattern.compile(
                "\\b(domain(\\.[a-z]+)*\\.(ui|app)\\.|domain\\.workbench\\.|quiz\\.)");
        List<String> violations = new ArrayList<>();
        for (Source source : sources()) {
            String name = source.packageName();
            if (roots.stream().noneMatch(root -> name.equals(root) || name.startsWith(root + "."))) {
                continue;
            }
            if (forbidden.matcher(source.text()).find()) {
                violations.add(source.relative() + " depends on domain workbench UI or quiz");
            }
        }
        assertEquals(List.of(), violations,
                "low-level clients expose protocols; UI and quiz depend on them, never back");
    }

    @Test void domainEntryPointsUseOneConvention() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Source source : sources()) {
            if (!source.packageName().equals("domain")
                    && !source.packageName().startsWith("domain.")) continue;
            if (!source.text().contains("static void main(")) continue;
            String file = source.relative().getFileName().toString();
            boolean valid = source.packageName().endsWith(".app") && file.endsWith("App.java")
                    || source.packageName().endsWith(".cli") && file.endsWith("Main.java")
                    || source.packageName().contains(".demo")
                            && (file.endsWith("Demo.java") || file.endsWith("Main.java"));
            if (!valid) violations.add(source.relative() + " is in " + source.packageName());
        }
        assertEquals(List.of(), violations,
                "desktop apps use .app/*App, commands use .cli/*Main, demos use .demo");
    }

    @Test void quizEntryPointsUseOneConvention() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Source source : sources()) {
            if (!source.packageName().equals("quiz")
                    && !source.packageName().startsWith("quiz.")) continue;
            if (!source.text().contains("static void main(")) continue;
            String file = source.relative().getFileName().toString();
            boolean valid = source.packageName().endsWith(".app") && file.endsWith("App.java")
                    || source.packageName().endsWith(".cli") && file.endsWith("Main.java")
                    || source.packageName().contains(".demo")
                            && (file.endsWith("Demo.java") || file.endsWith("Main.java"));
            if (!valid) violations.add(source.relative() + " is in " + source.packageName());
        }
        assertEquals(List.of(), violations,
                "quiz desktop apps use .app/*App, commands use .cli/*Main, demos use .demo");
    }

    private static List<Source> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(PackageOwnershipGuardTest::read).toList();
        }
    }

    private static Source read(Path file) {
        try {
            String text = Files.readString(file);
            Matcher matcher = PACKAGE.matcher(text);
            if (!matcher.find()) throw new AssertionError("No package in " + file);
            return new Source(SOURCES.relativize(file), matcher.group(1), text);
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private record Source(Path relative, String packageName, String text) { }
}
