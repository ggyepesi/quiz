package wikidata.explore.codegen;

import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Each build gets its own generation: a versioned package
 * (wikidata.generated.gN), its own output directory and its own
 * classloader. Regenerated classes therefore never collide with classes
 * from earlier runs, and a superseded runtime can be closed.
 */
public class GeneratedQuizableRuntimeBuilder {

    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final Path OUTPUT_ROOT = createOutputRoot();

    public GeneratedQuizableRuntime build(GeneratedClassModel model) throws Exception {
        Compiled c = compileOne(model);
        return new GeneratedQuizableRuntime(
                model, c.qcn, c.source, c.compiledClass, c.loader);
    }

    /**
     * Compiles every class in the project (each independently — entity fields
     * are typed as {@code quiz.Quizable}, so there are no cross-class
     * dependencies), keyed by class name. The root class drives the previews.
     */
    public GeneratedQuizableRuntime build(GeneratedProjectModel project) throws Exception {
        GeneratedClassModel root = project.rootClass();

        Map<String, GeneratedQuizableRuntime.ClassRuntime> byType = new LinkedHashMap<>();
        Compiled rootCompiled = null;

        for (GeneratedClassModel cls : project.classes()) {
            Compiled c = compileOne(cls);
            byType.put(cls.className(),
                    new GeneratedQuizableRuntime.ClassRuntime(
                            cls, c.compiledClass, c.loader));
            if (cls.className().equals(root.className())) {
                rootCompiled = c;
            }
        }
        if (rootCompiled == null) {
            rootCompiled = compileOne(root);
            byType.put(root.className(),
                    new GeneratedQuizableRuntime.ClassRuntime(
                            root, rootCompiled.compiledClass, rootCompiled.loader));
        }

        return new GeneratedQuizableRuntime(
                root, rootCompiled.qcn, rootCompiled.source,
                rootCompiled.compiledClass, rootCompiled.loader, byType);
    }

    private record Compiled(String qcn, String source,
                            Class<?> compiledClass,
                            java.net.URLClassLoader loader) {
    }

    private Compiled compileOne(GeneratedClassModel model) throws Exception {
        int generation = GENERATIONS.incrementAndGet();

        String packageName =
                GeneratedQuizableSourceGenerator.GENERATED_PACKAGE
                        + ".g" + generation;

        GeneratedQuizableSourceGenerator sourceGenerator =
                new GeneratedQuizableSourceGenerator(packageName);

        RuntimeJavaCompiler compiler =
                new RuntimeJavaCompiler(
                        OUTPUT_ROOT.resolve("g" + generation).toFile());

        String qcn = sourceGenerator.qualifiedClassName(model);
        String source = sourceGenerator.sourceFor(model);

        RuntimeJavaCompiler.CompiledClass compiled =
                compiler.compile(qcn, source);

        return new Compiled(qcn, source,
                compiled.compiledClass(), compiled.loader());
    }

    private static Path createOutputRoot() {
        try {
            return Files.createTempDirectory("wikidata-generated-");
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot create output directory for generated classes", e);
        }
    }
}
