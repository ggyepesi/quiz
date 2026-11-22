package domain.datasource.configured;

import com.fasterxml.jackson.databind.ObjectMapper;
import domain.datasource.api.DatasourceProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Loads installed configured datasource providers from one explicit directory.
 *
 * <p>The registry is composed for every validation, snapshot graph and generation, so
 * {@code Datasources.configured()} reads the installed directory once per process; a file
 * changed on disk is installed by a restart. Reading never throws: a missing directory,
 * an invalid file or a provider id that is already taken is a stated problem that names
 * the exact path, and costs only that provider, never the other configured providers,
 * the code-backed sources or the model editors that compose the registry.
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

    /** The checked-in directory beside every other data folder of the application. */
    public static Path standardDirectory() {
        return Path.of(aux.Constants.dataDirectory, "datasources");
    }

    public static Installed read(Path directory) {
        return read(directory, java.util.Set.of());
    }

    /** @param takenIds provider ids composed beside this directory, which it may not reuse */
    public static Installed read(Path directory, java.util.Set<String> takenIds) {
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
        java.util.Set<String> taken = new java.util.HashSet<>(takenIds);
        for (Path file : files) {
            try {
                ConfiguredDatasourceDefinition definition =
                        JSON.readValue(file.toFile(), ConfiguredDatasourceDefinition.class);
                if (!taken.add(definition.id())) {
                    problems.add("Configured datasource " + file.toAbsolutePath().normalize()
                            + " was not installed: provider id '" + definition.id()
                            + "' is already used by another domain.datasource.");
                    continue;
                }
                providers.add(new ConfiguredRestDatasourceProvider(definition));
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
