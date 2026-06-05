package aux;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

public class ResourceFinder {

    public record ResourceEntry(String parentName, String resourcePath) {}

    public static List<ResourceEntry> findResources(String basePath, String fileName) throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL url = cl.getResource(basePath);

        if (url == null) {
            throw new IllegalArgumentException("Resource folder not found: " + basePath);
        }

        if ("file".equals(url.getProtocol())) {
            return findFromFileSystem(url, basePath, fileName);
        }

        if ("jar".equals(url.getProtocol())) {
            return findFromJar(url, basePath, fileName);
        }

        throw new UnsupportedOperationException("Unsupported protocol: " + url.getProtocol());
    }

    public static URL toURL(String resourcePath) {
        return Thread.currentThread()
                .getContextClassLoader()
                .getResource(resourcePath);
    }

    public static boolean resourceExists(String resourcePath) {
        return toURL(resourcePath) != null;
    }

    private static List<ResourceEntry> findFromFileSystem(URL url, String basePath, String fileName) throws Exception {
        Path root = Paths.get(url.toURI());
        List<ResourceEntry> result = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(p -> {
                        String parent = p.getParent().getFileName().toString();
                        Path relative = root.relativize(p);
                        String resourcePath = basePath + "/" +
                                relative.toString().replace(FileSystems.getDefault().getSeparator(), "/");

                        result.add(new ResourceEntry(parent, resourcePath));
                    });
        }

        return result;
    }

    public static void debugResource(String resourcePath) {
        System.out.println("RESOURCE DEBUG");
        System.out.println("  requested = [" + resourcePath + "]");
        System.out.println("  url       = " + toURL(resourcePath));
    }

    private static List<ResourceEntry> findFromJar(URL url, String basePath, String fileName) throws IOException {
        String spec = url.getFile();
        String jarPath = spec.substring(0, spec.indexOf("!"));

        if (jarPath.startsWith("file:")) {
            jarPath = jarPath.substring(5);
        }

        jarPath = URI.create("file:" + jarPath).getPath();

        List<ResourceEntry> result = new ArrayList<>();

        try (JarFile jar = new JarFile(jarPath)) {
            jar.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith(basePath + "/"))
                    .filter(name -> name.endsWith("/" + fileName))
                    .forEach(name -> {
                        String[] parts = name.split("/");
                        String parent = parts[parts.length - 2];
                        result.add(new ResourceEntry(parent, name));
                    });
        }

        return result;
    }
}