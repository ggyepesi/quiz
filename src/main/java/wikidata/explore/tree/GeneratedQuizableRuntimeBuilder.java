package wikidata.explore.tree;

import wikidata.explore.model.GeneratedClassModel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        return new GeneratedQuizableRuntime(
                model,
                qcn,
                source,
                compiled.compiledClass(),
                compiled.loader());
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
