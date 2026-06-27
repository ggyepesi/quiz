package wikidata.explore.codegen;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RuntimeJavaCompiler {

    /** The loader owns the compiled class; close it when the generation is superseded. */
    public record CompiledClass(Class<?> compiledClass, URLClassLoader loader) {}

    /** Several classes compiled in ONE pass (so they may reference each other by
     *  simple name in the same package), sharing one loader. Keyed by qcn. */
    public record CompiledClasses(Map<String, Class<?>> classes, URLClassLoader loader) {}

    private final File rootDir;

    public RuntimeJavaCompiler() {
        this(new File(System.getProperty("java.io.tmpdir"), "wikidata-generated-classes"));
    }

    public RuntimeJavaCompiler(File rootDir) {
        this.rootDir = rootDir;
    }

    public CompiledClass compile(String qualifiedClassName, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler found. Run with a JDK.");
        }

        File sourceFile = sourceFileFor(qualifiedClassName);
        File parent = sourceFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.writeString(sourceFile.toPath(), source, StandardCharsets.UTF_8);

        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        var units = fm.getJavaFileObjectsFromFiles(List.of(sourceFile));
        List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-d", rootDir.getAbsolutePath());

        StringBuilder diagnostics = new StringBuilder();
        boolean ok = Boolean.TRUE.equals(compiler.getTask(
                null,
                fm,
                d -> diagnostics.append(d).append("\n"),
                options,
                null,
                units).call());
        fm.close();

        if (!ok) {
            throw new IllegalStateException("Generated class compilation failed:\n"
                    + diagnostics + "\nSource:\n" + source);
        }

        URLClassLoader loader = new URLClassLoader(
                new URL[]{rootDir.toURI().toURL()},
                Thread.currentThread().getContextClassLoader());

        return new CompiledClass(
                Class.forName(qualifiedClassName, true, loader),
                loader);
    }

    /**
     * Compiles several classes together in one task so they can reference each
     * other (typed cross-references in the same package), under one shared
     * loader. {@code sources} maps qualified class name to source.
     */
    public CompiledClasses compileAll(Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler found. Run with a JDK.");
        }

        List<File> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            File sourceFile = sourceFileFor(e.getKey());
            File parent = sourceFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.writeString(sourceFile.toPath(), e.getValue(), StandardCharsets.UTF_8);
            files.add(sourceFile);
        }

        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        var units = fm.getJavaFileObjectsFromFiles(files);
        List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-d", rootDir.getAbsolutePath());

        StringBuilder diagnostics = new StringBuilder();
        boolean ok = Boolean.TRUE.equals(compiler.getTask(
                null,
                fm,
                d -> diagnostics.append(d).append("\n"),
                options,
                null,
                units).call());
        fm.close();

        if (!ok) {
            throw new IllegalStateException("Generated classes compilation failed:\n"
                    + diagnostics);
        }

        URLClassLoader loader = new URLClassLoader(
                new URL[]{rootDir.toURI().toURL()},
                Thread.currentThread().getContextClassLoader());

        Map<String, Class<?>> classes = new LinkedHashMap<>();
        for (String qcn : sources.keySet()) {
            classes.put(qcn, Class.forName(qcn, true, loader));
        }
        return new CompiledClasses(classes, loader);
    }

    private File sourceFileFor(String qualifiedClassName) {
        return new File(rootDir, qualifiedClassName.replace('.', File.separatorChar) + ".java");
    }
}
