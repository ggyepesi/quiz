package domain.transform.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import domain.persistence.DatasetRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quiz sources are saved products. Live/manual domains remain TransformApp inputs,
 * and a loadable-but-unserved result never leaks into the quiz selector. */
class DomainCatalogConfiguredTest {

    @TempDir Path temporary;

    @Test void configuredCatalogContainsOnlyServedSnapshotsThatExist() throws Exception {
        DatasetRegistry registry = new DatasetRegistry();
        registry.datasets().add(dataset("History", true,
                Files.createFile(temporary.resolve("history.snapshot.json"))));
        registry.datasets().add(dataset("Graph annotations", false,
                Files.createFile(temporary.resolve("annotations.snapshot.json"))));
        registry.datasets().add(dataset("Missing snapshot", true,
                temporary.resolve("missing.snapshot.json")));
        DatasetRegistry.Dataset model = dataset("Historical Positions", false,
                Files.createFile(temporary.resolve("positions.snapshot.json")));
        model.modelPath(temporary.resolve("positions.model.json").toString());
        registry.datasets().add(model);
        registry.datasets().add(dataset("Historical Positions", true,
                Files.createFile(temporary.resolve("old-transform-export.snapshot.json"))));

        var entries = DomainCatalog.registered(registry, true);

        assertEquals(java.util.List.of("History"),
                entries.stream().map(domain.transform.ui.DomainEntry::name).toList());
        assertEquals("generated", entries.getFirst().source());
    }

    @Test void savingModelWorkingDataDoesNotPublishAQuizDomain() {
        assertTrue(DomainSaver.serves(
                domain.config.GeneratedProjectModel.ProjectKind.DOMAIN));
        assertFalse(DomainSaver.serves(
                domain.config.GeneratedProjectModel.ProjectKind.MODEL));
    }

    @Test void reopeningASnapshotKeepsItsFacetGroupRule() throws Exception {
        domain.transform.DynamicViewable paris = city("Paris", "Europe");
        domain.transform.DynamicViewable tokyo = city("Tokyo", "Asia");
        domain.DomainModel source = new domain.DomainModel() {
            @Override public java.util.List<String> types() {
                return java.util.List.of("City");
            }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return domain.DomainSchemas.flatSchema(java.util.List.of(
                        new domain.DomainField("City", "region", false, false)));
            }
            @Override public java.util.Collection<? extends objectview.Viewable> instances() {
                return java.util.List.of(paris, tokyo);
            }
            @Override public Class<? extends objectview.Viewable> universe() {
                return objectview.Viewable.class;
            }
        };
        domain.transform.ui.TransformController editor =
                new domain.transform.ui.TransformController(source, null);
        domain.transform.EditableGroup root = (domain.transform.EditableGroup)
                editor.groupRoot("City");
        editor.addFacetGroup("City", root, "Regions",
                new domain.DomainField("City", "region", false, false));

        var converted = ViewableToWdo.convertDomain(
                editor.domain().memberRoots(),
                editor.domain().groupRootBindings(), editor.domain());
        Path snapshot = temporary.resolve("cities.snapshot.json");
        new domain.runtime.WikidataDynamicObjectJsonStore()
                .saveWithGroupRootBindings(
                        converted.memberRoots(),
                        converted.groupRootBindings().stream()
                                .map(binding -> new domain.runtime
                                        .WikidataDynamicObjectJsonStore.GroupRootBinding(
                                                binding.memberType(), binding.root()))
                                .toList(),
                        snapshot.toFile(), editor.domain());

        domain.config.GeneratedProjectModel model =
                new domain.config.GeneratedProjectModel();
        model.name("Cities");
        domain.config.GeneratedClassModel city =
                new domain.config.GeneratedClassModel("City");
        city.addField("region", domain.datasource.schema.FieldType.STRING,
                domain.config.FieldCardinality.SINGLE);
        model.rootClass(city);
        Path modelFile = temporary.resolve("cities.model.json");
        new domain.config.GeneratedProjectModelStore()
                .save(model, modelFile.toFile());

        DatasetRegistry registry = new DatasetRegistry();
        DatasetRegistry.Dataset saved = dataset("Cities", true, snapshot);
        saved.modelPath(modelFile.toString());
        registry.datasets().add(saved);
        domain.DomainModel reopened = DomainCatalog.registered(registry, false)
                .getFirst().opener().open();
        domain.transform.ui.TransformController reopenedEditor =
                new domain.transform.ui.TransformController(reopened, null);
        objectview.group.ViewableGroup<?> reopenedRoot =
                reopenedEditor.groupRoot("City");

        assertEquals(java.util.List.of("Regions"), reopenedRoot.getChildren().stream()
                .map(group -> String.valueOf(group.fields().read("groupName"))).toList());
        assertInstanceOf(domain.transform.FacetGroup.class,
                reopenedRoot.getChildren().iterator().next());
        assertEquals("region", ((domain.transform.FacetGroup)
                reopenedRoot.getChildren().iterator().next()).field());
    }

    private static domain.transform.DynamicViewable city(String name, String region) {
        domain.transform.DynamicViewable value =
                new domain.transform.DynamicViewable(name, name);
        value.type("City");
        value.put("region", region);
        return value;
    }

    private static DatasetRegistry.Dataset dataset(
            String name, boolean served, Path snapshot) {
        DatasetRegistry.Dataset value = new DatasetRegistry.Dataset();
        value.name(name);
        value.key(name.toLowerCase().replace(' ', '-'));
        value.snapshotPath(snapshot.toString());
        value.served(served);
        return value;
    }
}
