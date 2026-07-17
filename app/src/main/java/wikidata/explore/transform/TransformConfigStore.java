package wikidata.explore.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;

/** Saves/loads a {@link TransformConfig} as {@code <domain>.transform.json}. */
public final class TransformConfigStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false);

    private TransformConfigStore() {
    }

    public static String toJson(TransformConfig config) {
        try {
            return MAPPER.writeValueAsString(
                    config == null ? new TransformConfig() : config);
        } catch (Exception e) {
            throw new RuntimeException("Cannot serialize transform config", e);
        }
    }

    public static TransformConfig fromJson(String json) {
        try {
            return json == null || json.isBlank()
                    ? new TransformConfig()
                    : MAPPER.readValue(json, TransformConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Invalid transform JSON: " + e.getMessage(), e);
        }
    }

    public static void save(File file, TransformConfig config) {
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            MAPPER.writeValue(file, config == null ? new TransformConfig() : config);
        } catch (Exception e) {
            throw new RuntimeException("Cannot save transform config: " + file, e);
        }
    }

    public static TransformConfig load(File file) {
        if (file == null || !file.isFile()) {
            return new TransformConfig();
        }
        try {
            return MAPPER.readValue(file, TransformConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Cannot load transform config: " + file, e);
        }
    }
}
