package domain.transform.app;

import dataset.DomainStorage;
import domain.DomainModel;
import domain.build.GraphDiscoveryResultStore;
import domain.config.FieldCardinality;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.curation.Curatable;
import domain.curation.CuratedInstance;
import domain.curation.CuratedInstanceSink;
import domain.curation.ManualCuration;
import domain.datasource.schema.FieldType;
import domain.runtime.WikidataDynamicObject;
import domain.runtime.WikidataDynamicObjectJsonStore;
import domain.runtime.SnapshotDomain;
import domain.workbench.execution.ui.ProjectInstancesPresentation;
import objectview.field.FieldRole;
import objectview.field.FieldSet;
import objectview.field.LinkedTextSequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import domain.persistence.DatasetRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quiz sources are saved products. Live/manual domains remain TransformApp inputs,
 * and a loadable-but-unserved result never leaks into the quiz selector. */
class DomainCatalogConfiguredTest {

    @TempDir Path temporary;

    @Test void reopeningCurationUsesTheSavedModelsClassHierarchyNotTheOldSnapshot()
            throws Exception {
        var snapshot = temporary.resolve("norse.snapshot.json").toFile();
        var modelFile = temporary.resolve("norse.model.json").toFile();
        var storage = DomainStorage.in(temporary.toFile());

        var project = new GeneratedProjectModel();
        project.name("Norse");
        project.rootClass(new GeneratedClassModel("NorseCharacter"));
        var deity = new GeneratedClassModel("NorseDeity");
        project.addClass(deity);
        var work = new GeneratedClassModel("NorseLiteraryWork");
        project.addClass(work);
        var event = new GeneratedClassModel("MythologicalEvent");
        event.addField("work", FieldType.ENTITY, FieldCardinality.COLLECTION);
        event.fields().getLast().entityClassName("NorseLiteraryWork");
        project.addClass(event);
        var story = new GeneratedClassModel("NorseStory");
        story.baseClassName("MythologicalEvent");
        story.addField("characters", FieldType.ENTITY, FieldCardinality.COLLECTION);
        story.fields().getLast().entityClassName("NorseCharacter");
        project.addClass(story);
        var loki = new WikidataDynamicObject("Q133147", "Loki");
        loki.type("NorseDeity");
        var edda = new WikidataDynamicObject("Q205882", "Prose Edda");
        edda.type("NorseLiteraryWork");
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(List.of(loki, edda), snapshot, project);

        // Save the edited subclass and field cardinality without rebuilding the instances.
        deity.baseClassName("NorseCharacter");
        event.fields().getLast().cardinality(FieldCardinality.SINGLE);
        new GeneratedProjectModelStore().save(project, modelFile);
        var opened = DomainCatalog.open(snapshot, modelFile, storage);
        var curated = new CuratedInstance("NorseStory", "text-passage:abc", "A story about Loki",
                List.of(
                        CuratedInstance.FieldValue.references("work",
                                List.of(new CuratedInstance.Reference(
                                        "NorseLiteraryWork", "Q205882"))),
                        CuratedInstance.FieldValue.references("characters",
                                List.of(new CuratedInstance.Reference("NorseCharacter", "Q133147")))),
                null);
        var staged = opened.capability(CuratedInstanceSink.class).materialize(List.of(curated));
        assertEquals(List.of(), staged.unresolved());
        assertStoryReferences(opened, staged.instances().getFirst());
        assertTrue(fieldNames(staged.instances().getFirst()).contains("characters"),
                "a staged story lists its class's fields, not only provenance");
        var sidecar = opened.capability(Curatable.class).curation();
        sidecar.putInstance(curated);
        sidecar.save();

        var reopened = DomainCatalog.open(snapshot, modelFile, storage);
        assertEquals("NorseCharacter", reopened.baseType("NorseDeity"));
        assertStoryReferences(reopened, instance(reopened, curated.id()));
        assertTrue(fieldNames(instance(reopened, curated.id())).contains("characters"),
                "a reopened curated story lists its class's fields, not only provenance");
    }

    private static void assertStoryReferences(DomainModel domain, WikidataDynamicObject story) {
        assertSame(instance(domain, "Q205882"), story.get("work"),
                "the inherited single field must hold the work itself, not a one-item list");
        assertEquals(List.of(instance(domain, "Q133147")), story.get("characters"),
                "a one-member collection must stay a list and resolve to the loaded subclass");
    }

    /** Reported shape: the instances window showed every class's cards with only the
     * Java carrier's provenance fields, because each section was typed by the class
     * WikidataDynamicObject rather than by its model class's schema. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theInstancesWindowTypesEachClassSectionByItsClassSchema(boolean besideGraph)
            throws Exception {
        var snapshot = temporary.resolve("norse.snapshot.json").toFile();
        var modelFile = temporary.resolve("norse.model.json").toFile();
        var project = new GeneratedProjectModel();
        project.name("Norse");
        var story = new GeneratedClassModel("NorseStory");
        story.addField("text", FieldType.TEXT, FieldCardinality.SINGLE);
        project.rootClass(story);
        var saga = new WikidataDynamicObject("Q1", "Gylfaginning I");
        saga.type("NorseStory");
        saga.put("text", "King Gylfi ruled the land.");
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(List.of(saga), snapshot, project);
        new GeneratedProjectModelStore().save(project, modelFile);
        DomainModel opened = DomainCatalog.open(snapshot, modelFile, DomainStorage.in(temporary.toFile()));
        var viewer = new domain.workbench.execution.ui.QueryObjectResultPanel();
        var graph = new WikidataDynamicObject("Q2", "An annotated instance");
        graph.type("StoryGraph");
        var graphArtifacts = besideGraph ? List.of(new GraphDiscoveryResultStore.Artifact(
                project.name(), "StoryGraph", "NorseStory", List.of(graph), List.of(),
                new SnapshotDomain(List.of(graph)))) : List.<GraphDiscoveryResultStore.Artifact>of();

        javax.swing.SwingUtilities.invokeAndWait(() ->
                ProjectInstancesPresentation.showSaved(
                        viewer, project, opened, graphArtifacts));
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertEquals(besideGraph, viewer.displayedObjectsByType().containsKey("StoryGraph"),
                "exercise the graph-side layout rather than just the ordinary layout twice");

        var fields = new java.util.ArrayList<java.util.Set<String>>();
        javax.swing.SwingUtilities.invokeAndWait(() -> collectViewFields(viewer, fields));
        assertTrue(!fields.isEmpty() && fields.getFirst().contains("text"),
                "the NorseStory section offers its class field, not only provenance: " + fields);
        assertTrue(!fields.getFirst().contains("dynamicFields"),
                "the Java carrier's own fields are not the class's fields: " + fields);
        var names = new java.util.ArrayList<String>();
        javax.swing.SwingUtilities.invokeAndWait(() -> collectSectionNames(viewer, names));
        assertEquals("NorseStory", names.getFirst(),
                "the ordinary class section keeps its logical name in both layouts");
    }

    private static void collectSectionNames(java.awt.Container root, List<String> out) {
        for (var child : root.getComponents()) {
            if (child instanceof objectview.view.SearchableView view && view.search() != null) {
                out.add(view.search().sectionTypeName());
            }
            if (child instanceof java.awt.Container container) collectSectionNames(container, out);
        }
    }

    private static void collectViewFields(java.awt.Container root,
            List<java.util.Set<String>> out) {
        for (var child : root.getComponents()) {
            if (child instanceof objectview.view.SearchableView view && view.search() != null) {
                out.add(view.search().getViewConfig().getFields().keySet());
            }
            if (child instanceof java.awt.Container container) collectViewFields(container, out);
        }
    }

    private static List<String> fieldNames(WikidataDynamicObject instance) {
        return instance.dynamicFieldSchema() == null ? List.of()
                : instance.dynamicFieldSchema().fields().stream()
                        .map(objectview.field.FieldRef::name).toList();
    }

    private static WikidataDynamicObject instance(DomainModel domain, String id) {
        return (WikidataDynamicObject) domain.instances().stream()
                .filter(value -> id.equals(value.getIdentifier())).findFirst().orElseThrow();
    }

    @Test void savedStoriesUseOneStableSourceFieldWithTheExactSavedLinks() throws Exception {
        var snapshot = temporary.resolve("norse.snapshot.json").toFile();
        var modelFile = temporary.resolve("norse.model.json").toFile();
        var project = new GeneratedProjectModel();
        project.name("Norse");
        var story = new GeneratedClassModel("NorseStory");
        story.addField("text", FieldType.TEXT, FieldCardinality.SINGLE);
        project.rootClass(story);
        var first = new WikidataDynamicObject("story:first", "An earlier story");
        first.type("NorseStory");
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(List.of(first), snapshot, project);
        new GeneratedProjectModelStore().save(project, modelFile);

        var proseEdda = new domain.curation.ValueSource("Wikisource (English)", "Prose Edda",
                "", "https://en.wikisource.org/wiki/Prose_Edda#Gylfaginning");
        var another = new domain.curation.ValueSource("Other archive", "Record 17",
                "", "https://example.org/record/17");
        var sidecar = ManualCuration.forSnapshot(snapshot);
        sidecar.putInstance(new CuratedInstance("NorseStory", "story:edda", "From the Edda",
                List.of(), proseEdda));
        sidecar.putInstance(new CuratedInstance("NorseStory", "story:archive", "From an archive",
                List.of(), another));
        sidecar.save();

        var domain = DomainCatalog.open(snapshot, modelFile, DomainStorage.in(temporary.toFile()));
        var viewer = new domain.workbench.execution.ui.QueryObjectResultPanel();
        javax.swing.SwingUtilities.invokeAndWait(() ->
                ProjectInstancesPresentation.showSaved(
                        viewer, project, domain, List.of()));
        var fields = new java.util.ArrayList<java.util.Set<String>>();
        javax.swing.SwingUtilities.invokeAndWait(() -> collectViewFields(viewer, fields));
        var offered = fields.getFirst();
        assertTrue(offered.contains("text"), "ordinary declared fields stay visible");
        assertFalse(offered.contains("wikidataSource"), "unpopulated class provenance is omitted");
        assertFalse(offered.contains("wikidataStatements"));
        assertFalse(offered.contains("wikidataRelationRouteMatches"),
                "carrier-only evidence must not reappear as an empty field");
        assertTrue(domain.fieldSchema("NorseStory").field("wikidataSource") != null,
                "the presentation must not remove the field from the actual class schema");
        var field = WikidataDynamicObject.acceptedSourceField();
        assertTrue(offered.contains(field.name()), "keep the source even though the first card lacks it");
        assertEquals("Accepted source", field.label());
        assertTrue(offered.stream().noneMatch(name -> name.startsWith("@accepted-source:")),
                "provider names are values, not field keys");
        for (var entry : java.util.Map.of("story:edda", proseEdda, "story:archive", another).entrySet()) {
            var source = entry.getValue();
            var actual = FieldSet.of(instance(domain, entry.getKey()));
            assertEquals(FieldRole.PROVENANCE, actual.field(field.name()).role());
            assertEquals("Accepted source", actual.field(field.name()).label());
            var linked = assertInstanceOf(LinkedTextSequence.class, actual.read(field.name()));
            assertEquals(source.kind() + ": " + source.entityId(), linked.text());
            assertEquals(List.of(source.recordUrl()), linked.urls());
            assertFalse(instance(domain, entry.getKey()).dynamicFieldValues().containsKey(field.name()),
                    "the sidecar, not the runtime projection, owns the saved source");
        }
    }

    @Test void snapshotOnlyReplayAlsoReadsFieldCardinalityFromTheCurationDeclaration() throws Exception {
        var snapshot = temporary.resolve("norse.snapshot.json").toFile();
        var work = new WikidataDynamicObject("Q205882", "Prose Edda");
        work.type("NorseLiteraryWork");
        var original = new WikidataDynamicObject("story:first", "An earlier story");
        original.type("NorseStory");
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(List.of(work, original), snapshot);
        var curation = ManualCuration.forSnapshot(snapshot);
        curation.putFieldDeclaration("NorseStory", objectview.field.FieldRef.described(
                "work", objectview.field.FieldKind.REFERENCE, objectview.field.FieldKind.REFERENCE,
                "NorseLiteraryWork", true, false, "NorseLiteraryWork", false, false, false, false, "", true));
        curation.putInstance(new CuratedInstance("NorseStory", "story:curated", "A story",
                List.of(CuratedInstance.FieldValue.references("work", List.of(
                        new CuratedInstance.Reference("NorseLiteraryWork", "Q205882")))), null));
        curation.save();

        var opened = DomainCatalog.open(snapshot, null, DomainStorage.in(temporary.toFile()));
        assertSame(instance(opened, "Q205882"), instance(opened, "story:curated").get("work"));
    }

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
