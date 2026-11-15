package datasource.configured;

import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.api.DatasourceProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Loads installed configured datasource providers from one explicit directory.
 *
 * <p>The registry is composed for every validation, snapshot graph and generation, so the
 * installed directory is read once per process; a file changed on disk is installed by a
 * restart. Reading never throws: a missing directory or an invalid file is a stated
 * problem that names the exact path, and costs only the configured providers it affects,
 * never the code-backed sources or the model editors that compose the registry.
 */
public final class ConfiguredDatasourceCatalog {
    private static final String SUFFIX = ".datasource.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfiguredDatasourceCatalog() { }

    /** What one directory installs, and every reason a configured provider is missing. */
    public record Installed(Path directory, List<DatasourceProvider> providers,
            List<String> problems) {
        public Installed {
            providers = List.copyOf(providers);
            problems = List.copyOf(problems);
        }
    }

    private static final class Standard {
        static final Installed INSTALLED = read(standardDirectory());
    }

    /** The checked-in directory beside every other data folder of the application. */
    public static Path standardDirectory() {
        return Path.of(aux.Constants.dataDirectory, "datasources");
    }

    /** The standard directory, read once for this process. */
    public static Installed installed() {
        return Standard.INSTALLED;
    }

    public static Installed read(Path directory) {
        Path shown = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            return new Installed(directory, List.of(), List.of(
                    "No configured datasources are installed: " + shown
                            + " is not a directory."));
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException failure) {
            return new Installed(directory, List.of(), List.of(
                    "Could not list configured datasources in " + shown + ": "
                            + failure.getMessage()));
        }
        List<DatasourceProvider> providers = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            try {
                providers.add(new ConfiguredRestDatasourceProvider(
                        JSON.readValue(file.toFile(), ConfiguredDatasourceDefinition.class)));
            } catch (Exception failure) {
                problems.add("Configured datasource " + file.toAbsolutePath().normalize()
                        + " was not installed: " + rootMessage(failure));
            }
        }
        return new Installed(directory, providers, problems);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName()
                : root.getMessage();
    }
}
