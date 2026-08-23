package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.io.File;
import java.io.IOException;

/**
 * Persists a {@link GeneratedProjectModel} (the editable class/field config) to
 * JSON, so a model edited in the workbench can be saved alongside its generated
 * instances and rule tree.
 *
 * <p>Field-only visibility mirrors {@code RuleTreeSerializer}, so the model's
 * fluent accessors aren't mistaken for getters and each value is written once.</p>
 */
public final class GeneratedProjectModelStore {

    private final ObjectMapper mapper;

    public GeneratedProjectModelStore() {
        mapper = new ObjectMapper();
        mapper.setVisibility(
                PropertyAccessor.ALL,
                JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(
                PropertyAccessor.FIELD,
                JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTypingAsProperty(
                BasicPolymorphicTypeValidator.builder()
                                             .allowIfSubType("wikidata.explore")
                                             .allowIfSubType("java.util")
                                             .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                "@class");
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(
            GeneratedProjectModel model,
            File file) throws IOException {

        if (model == null) {
            throw new IllegalArgumentException(
                    "model must not be null");
        }

        GeneratedProjectModelValidator.ValidationResult validation =
                GeneratedProjectModelValidator.validate(model);

        if (!validation.valid()) {
            throw new IOException(
                    "Cannot save invalid model:"
                            + System.lineSeparator()
                            + validation.format());
        }

        PopulationSourceBindings.synchronize(model);

        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        mapper.writeValue(file, model);
    }

    public String toJson(
            GeneratedProjectModel model) throws IOException {

        if (model == null) {
            throw new IllegalArgumentException(
                    "model must not be null");
        }

        GeneratedProjectModelValidator.ValidationResult validation =
                GeneratedProjectModelValidator.validate(model);

        if (!validation.valid()) {
            throw new IOException(
                    "Cannot serialize invalid model:"
                            + System.lineSeparator()
                            + validation.format());
        }

        PopulationSourceBindings.synchronize(model);

        return mapper.writeValueAsString(model);
    }

    public GeneratedProjectModel load(
            File file) throws IOException {

        GeneratedProjectModel model = mapper.readValue(file, GeneratedProjectModel.class);
        OwnedClassSemantics.migrateLegacy(model);
        PopulationSourceBindings.synchronize(model);
        return model;
    }
}
