package wikidata.explore.codegen;

import wikidata.explore.model.GeneratedClassModel;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Map;

/**
 * A compiled generation. {@code model}/{@code generatedClass}/{@code source}
 * describe the ROOT class (for previews and the result panel); {@code byType}
 * holds a compiled class per model class (keyed by class name), so a multi-class
 * project (e.g. Constellation + Star) maps each object to its own type rather
 * than forcing children into the root class.
 */
public record GeneratedViewableRuntime(GeneratedClassModel model,
                                       String qualifiedClassName, String source,
                                       Class<?> generatedClass,
                                       URLClassLoader classLoader,
                                       Map<String, ClassRuntime> byType)
        implements AutoCloseable {

    /** The compiled class + its model for one generated class. */
    public record ClassRuntime(GeneratedClassModel model,
                               Class<?> generatedClass,
                               URLClassLoader classLoader) {
    }

    /** Single-class generation (referenced classes aren't compiled). */
    public GeneratedViewableRuntime(GeneratedClassModel model,
                                    String qualifiedClassName, String source,
                                    Class<?> generatedClass,
                                    URLClassLoader classLoader) {
        this(model, qualifiedClassName, source, generatedClass, classLoader,
                Map.of(model.className(),
                        new ClassRuntime(model, generatedClass, classLoader)));
    }

    /** The compiled class for a model class name (e.g. "Star"), or null. */
    public ClassRuntime forType(String className) {
        return className == null || byType == null ? null : byType.get(className);
    }

    /**
     * Closing only blocks further class lookups through the loaders;
     * already-loaded classes and their instances keep working.
     */
    @Override
    public void close() {
        if (byType != null) {
            for (ClassRuntime cr : byType.values()) {
                closeLoader(cr.classLoader());
            }
        } else {
            closeLoader(classLoader);
        }
    }

    private static void closeLoader(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException ignored) {
        }
    }
}
