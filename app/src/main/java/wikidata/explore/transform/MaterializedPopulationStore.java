package wikidata.explore.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dataset.DomainStorage;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.TransformationClassSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Storage and staleness record for one declared materialized population output. */
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

    public static List<String> save(GeneratedProjectModel model,
                                    GeneratedClassModel outputClass,
                                    List<WikidataDynamicObject> population,
                                    DomainStorage storage,
                                    String inputSnapshotSignature) throws Exception {
        File output = storage.materializedPopulationFile(
                model.name(), outputClass.declarationId());
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                population, output, model);
        File metadata = storage.materializedPopulationMetadataFile(
                model.name(), outputClass.declarationId());
        if (metadata.getParentFile() != null) metadata.getParentFile().mkdirs();
        JSON.writeValue(metadata, new Metadata(signature(outputClass),
                inputSnapshotSignature == null ? "" : inputSnapshotSignature,
                population == null ? 0 : population.size()));
        return List.of(output.getPath(), metadata.getPath());
    }

    public static Metadata metadata(GeneratedProjectModel model,
                                    GeneratedClassModel outputClass,
                                    DomainStorage storage) {
        try {
            File file = storage.materializedPopulationMetadataFile(
                    model.name(), outputClass.declarationId());
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
}
