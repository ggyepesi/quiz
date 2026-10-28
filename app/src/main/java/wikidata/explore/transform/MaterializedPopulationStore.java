package wikidata.explore.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dataset.DomainStorage;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.TransformationClassSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage and staleness record for a transformation's output and for the populations
 * published from its class. Each file has one producer: RUN_TRANSFORMATION writes the
 * class-keyed output, PUBLISH_POPULATION the selection-keyed publication.
 */
public final class MaterializedPopulationStore {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private MaterializedPopulationStore() { }

    public record Metadata(String transformationSignature, String inputSnapshotSignature,
                           int members) { }

    public static String signature(GeneratedClassModel outputClass) {
        TransformationClassSource transformation = outputClass == null
                ? null : outputClass.transformationSource();
        if (transformation == null) return "";
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("input", transformation.inputClassId());
            value.put("steps", transformation.steps().stream().map(step -> Map.of(
                    "kind", step.kind().name(),
                    "field", step.fieldPath(),
                    "operator", step.operator().name(),
                    "value", String.valueOf(step.value()),
                    "value2", String.valueOf(step.value2()))).toList());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    JSON.writeValueAsBytes(value));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException("Cannot fingerprint transformation", impossible);
        }
    }

    /** Writes the transformation's own output: exactly what its steps kept. */
    public static List<String> save(GeneratedProjectModel model,
                                    GeneratedClassModel outputClass,
                                    List<WikidataDynamicObject> population,
                                    DomainStorage storage,
                                    String inputSnapshotSignature) throws Exception {
        return write(model, outputClass, population, inputSnapshotSignature,
                storage.materializedPopulationFile(model.name(), outputClass.declarationId()),
                storage.materializedPopulationMetadataFile(
                        model.name(), outputClass.declarationId()));
    }

    /**
     * Writes what {@code population} publishes: the class's members at publication, which
     * may include memberships later steps added to the transformation's output. Its
     * metadata records the transformation and snapshot it was drawn from, so an importer
     * can tell when it is stale; it never stands in for the transformation's own output.
     */
    public static List<String> publish(GeneratedProjectModel model,
                                       PopulationSelection population,
                                       GeneratedClassModel outputClass,
                                       List<WikidataDynamicObject> members,
                                       DomainStorage storage,
                                       String inputSnapshotSignature) throws Exception {
        return write(model, outputClass, members, inputSnapshotSignature,
                storage.publishedPopulationFile(model.name(), population.declarationId()),
                storage.publishedPopulationMetadataFile(
                        model.name(), population.declarationId()));
    }

    private static List<String> write(GeneratedProjectModel model,
                                      GeneratedClassModel outputClass,
                                      List<WikidataDynamicObject> members,
                                      String inputSnapshotSignature,
                                      File output, File metadata) throws Exception {
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(members, output, model);
        if (metadata.getParentFile() != null) metadata.getParentFile().mkdirs();
        JSON.writeValue(metadata, new Metadata(signature(outputClass),
                inputSnapshotSignature == null ? "" : inputSnapshotSignature,
                members == null ? 0 : members.size()));
        return List.of(output.getPath(), metadata.getPath());
    }

    public static Metadata metadata(GeneratedProjectModel model,
                                    GeneratedClassModel outputClass,
                                    DomainStorage storage) {
        return read(storage.materializedPopulationMetadataFile(
                model.name(), outputClass.declarationId()));
    }

    public static Metadata publication(GeneratedProjectModel model,
                                       PopulationSelection population,
                                       DomainStorage storage) {
        return read(storage.publishedPopulationMetadataFile(
                model.name(), population.declarationId()));
    }

    private static Metadata read(File file) {
        try {
            return file.isFile() ? JSON.readValue(file, Metadata.class) : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    public static List<WikidataDynamicObject> load(GeneratedProjectModel model,
                                                   GeneratedClassModel outputClass,
                                                   DomainStorage storage) throws Exception {
        return new WikidataDynamicObjectJsonStore().load(
                storage.materializedPopulationFile(
                        model.name(), outputClass.declarationId()));
    }

    public static List<WikidataDynamicObject> loadPublished(
            GeneratedProjectModel model, PopulationSelection population,
            DomainStorage storage) throws Exception {
        return new WikidataDynamicObjectJsonStore().load(
                storage.publishedPopulationFile(model.name(), population.declarationId()));
    }
}
