package wikidata.explore.tree;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class RuntimeJavaCompiler {
    private final File rootDir;

    public RuntimeJavaCompiler() {
        this(new File(System.getProperty("java.io.tmpdir"), "wikidata-generated-classes"));
    }

    public RuntimeJavaCompiler(File rootDir) {
        this.rootDir = rootDir;
    }

    public Class<?> compile(String qualifiedClassName, String source) throws Exception {
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

        return Class.forName(qualifiedClassName, true, loader);
    }

    private File sourceFileFor(String qualifiedClassName) {
        return new File(rootDir, qualifiedClassName.replace('.', File.separatorChar) + ".java");
    }
}
