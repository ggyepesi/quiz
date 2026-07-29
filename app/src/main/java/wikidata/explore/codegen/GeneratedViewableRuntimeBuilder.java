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
public class GeneratedViewableRuntimeBuilder {

    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final Path OUTPUT_ROOT = createOutputRoot();

    public GeneratedViewableRuntime build(GeneratedClassModel model) throws Exception {
        Compiled c = compileOne(model, null);
        return new GeneratedViewableRuntime(
                model, c.qcn, c.source, c.compiledClass, c.loader);
    }

    /**
     * Compiles every class in the project (each independently — entity fields
     * are typed as {@code objectview.Viewable}, so there are no cross-class
     * dependencies), keyed by class name. The root class drives the previews.
     */
    public GeneratedViewableRuntime build(GeneratedProjectModel project) throws Exception {
        int generation = GENERATIONS.incrementAndGet();
        String packageName =
                GeneratedViewableSourceGenerator.GENERATED_PACKAGE + ".g" + generation;

        GeneratedViewableSourceGenerator gen =
                new GeneratedViewableSourceGenerator(packageName);
        RuntimeJavaCompiler compiler =
                new RuntimeJavaCompiler(OUTPUT_ROOT.resolve("g" + generation).toFile());

        // Generate every class into the SAME package and compile them in one
        // pass, so typed cross-references (e.g. Character <-> Episode) resolve.
        // That is what lets ViewableFieldPaths recurse into a referenced class's
        // fields for nested search/sort/config, and keeps cross-refs typed (not
        // raw) when mapped.
        Map<String, String> sources = new LinkedHashMap<>();           // qcn -> source
        Map<String, GeneratedClassModel> modelByQcn = new LinkedHashMap<>();
        for (GeneratedClassModel cls : project.classes()) {
            String qcn = gen.qualifiedClassName(cls);
            sources.put(qcn, gen.sourceFor(cls, project));
            modelByQcn.put(qcn, cls);
        }

        RuntimeJavaCompiler.CompiledClasses compiled = compiler.compileAll(sources);

        Map<String, GeneratedViewableRuntime.ClassRuntime> byType = new LinkedHashMap<>();
        for (Map.Entry<String, GeneratedClassModel> e : modelByQcn.entrySet()) {
            byType.put(e.getValue().className(),
                    new GeneratedViewableRuntime.ClassRuntime(
                            e.getValue(),
                            compiled.classes().get(e.getKey()),
                            compiled.loader()));
        }

        GeneratedClassModel root = project.rootClass();
        String rootQcn = gen.qualifiedClassName(root);
        return new GeneratedViewableRuntime(
                root, rootQcn, sources.get(rootQcn),
                compiled.classes().get(rootQcn), compiled.loader(), byType);
    }

    private record Compiled(String qcn, String source,
                            Class<?> compiledClass,
                            java.net.URLClassLoader loader) {
    }

    private Compiled compileOne(GeneratedClassModel model, GeneratedProjectModel project)
            throws Exception {
        int generation = GENERATIONS.incrementAndGet();

        String packageName =
                GeneratedViewableSourceGenerator.GENERATED_PACKAGE
                        + ".g" + generation;

        GeneratedViewableSourceGenerator sourceGenerator =
                new GeneratedViewableSourceGenerator(packageName);

        RuntimeJavaCompiler compiler =
                new RuntimeJavaCompiler(
                        OUTPUT_ROOT.resolve("g" + generation).toFile());

        String qcn = sourceGenerator.qualifiedClassName(model);
        String source = sourceGenerator.sourceFor(model, project);

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
