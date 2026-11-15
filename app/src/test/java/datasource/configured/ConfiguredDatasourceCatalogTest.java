package datasource.configured;

import datasource.api.BindingScope;
import datasource.api.SourceValueKind;
import datasource.api.discovery.RelatedRecordDiscoveryOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The checked-in Met file is executable configuration, not a source-specific class. */
class ConfiguredDatasourceCatalogTest {

    /** Surefire runs in the app module; the application runs from the repository. */
    static final Path CHECKED_IN = Path.of("..", "data", "datasources");

    @Test void checkedInMetConfigurationBecomesAnOrdinaryRegistryOperation() {
        var installed = ConfiguredDatasourceCatalog.read(CHECKED_IN);

        assertEquals(java.util.List.of(), installed.problems());
        var met = installed.providers().stream()
                .filter(provider -> provider.id().equals("met-collection"))
                .findFirst().orElseThrow();
        RelatedRecordDiscoveryOperation operation = assertInstanceOf(
                RelatedRecordDiscoveryOperation.class, met.operations().getFirst());
        assertEquals("MuseumArtifact", operation.recordTypeName());
        assertEquals(10, operation.maximumSeeds());
        assertEquals(20, operation.maximumRecordsPerSeed());
        assertEquals(BindingScope.DOCUMENT_EVIDENCE, operation.scope());
        assertTrue(operation.recordFields().stream()
                .anyMatch(field -> field.name().equals("metSource")));
        assertTrue(met.getClass().getSimpleName().startsWith("Configured"),
                "Met is data loaded by the generic provider, not a Met Java provider");
    }

    /** One invalid file used to throw out of Datasources.standard(), and so out of model
     *  validation, snapshot loading and generation. It now costs only itself, by name. */
    @Test void anInvalidFileCostsOnlyItselfAndIsNamed(@TempDir Path directory)
            throws Exception {
        Files.copy(CHECKED_IN.resolve("met-collection.datasource.json"),
                directory.resolve("met-collection.datasource.json"));
        Path broken = directory.resolve("broken.datasource.json");
        Files.writeString(broken, "{\"id\":\"broken\",\"operations\":[]}");

        var installed = ConfiguredDatasourceCatalog.read(directory);

        assertEquals(java.util.List.of("met-collection"), installed.providers().stream()
                .map(datasource.api.DatasourceProvider::id).toList());
        assertEquals(1, installed.problems().size());
        String problem = installed.problems().getFirst();
        assertTrue(problem.contains(broken.toAbsolutePath().normalize().toString()), problem);
        assertTrue(problem.contains("declares no operations"), problem);
    }

    @Test void aMissingDirectoryIsStatedNotSilentlyEmpty(@TempDir Path parent) {
        Path missing = parent.resolve("datasources");

        var installed = ConfiguredDatasourceCatalog.read(missing);

        assertTrue(installed.providers().isEmpty());
        assertEquals(java.util.List.of("No configured datasources are installed: "
                + missing.toAbsolutePath().normalize() + " is not a directory."),
                installed.problems());
    }

    @Test void theStandardDirectoryIsBesideTheApplicationsOtherData() {
        assertEquals(Path.of(aux.Constants.dataDirectory, "datasources"),
                ConfiguredDatasourceCatalog.standardDirectory());
    }

    @Test void configuredFieldsCannotPretendToBeUnspecifiedEntityReferences() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ConfiguredDatasourceDefinition.Field(
                        "creator", "Creator", "artist", SourceValueKind.ENTITY_REFERENCE,
                        false));

        assertTrue(failure.getMessage().contains("unsupported configured kind"));
    }
}
