package wikidata.explore.tree;

import wikidata.explore.model.GeneratedClassModel;

import java.io.IOException;
import java.net.URLClassLoader;

public record GeneratedQuizableRuntime(GeneratedClassModel model,
                                       String qualifiedClassName, String source,
                                       Class<?> generatedClass,
                                       URLClassLoader classLoader)
        implements AutoCloseable {

    /**
     * Closing only blocks further class lookups through the loader;
     * already-loaded classes and their instances keep working.
     */
    @Override
    public void close() {
        if (classLoader == null) {
            return;
        }

        try {
            classLoader.close();
        } catch (IOException ignored) {
        }
    }
}
