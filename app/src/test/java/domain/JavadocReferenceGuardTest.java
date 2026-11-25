package domain;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code {@link}} and {@code {@see}} in the app's sources names something that exists.
 *
 * <p>javac treats a doc comment as text, so the build and IntelliJ's Rebuild stayed green
 * while a package move left fourteen links naming types that were no longer imported,
 * renamed members and deleted overloads. IntelliJ's editor resolves them and showed the
 * files as broken. This runs javac's own reference check (doclint {@code reference}) over
 * the whole app source tree, analysis only, and fails with each unresolved link.</p>
 */
class JavadocReferenceGuardTest {

    @Test void everyJavadocReferenceInTheAppResolves() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK, not a JRE");
        List<Path> sources;
        try (var walk = Files.walk(Path.of("src/main/java"))) {
            sources = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(sources.size() > 500, "the app sources were found: " + sources.size());

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (com.sun.source.util.JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "-Xdoclint:reference", "-Xmaxerrs", "10000",
                            "-classpath", System.getProperty("java.class.path")),
                    null, files.getJavaFileObjectsFromPaths(sources));
            task.analyze();
        }

        List<String> unresolved = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> Path.of("src/main/java").toAbsolutePath()
                        .relativize(Path.of(diagnostic.getSource().toUri()))
                        + ":" + diagnostic.getLineNumber() + " "
                        + diagnostic.getMessage(Locale.ROOT))
                .toList();
        assertEquals(List.of(), unresolved,
                "Qualify the type, point the link at the current member, or make it plain "
                        + "text ({@code …}).");
    }
}
