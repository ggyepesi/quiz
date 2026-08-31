package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final ModelModuleResolver modules;

    public GeneratedProjectModelStore() {
        this(ModelModuleStore.standard());
    }

    public GeneratedProjectModelStore(ModelModuleResolver modules) {
        mapper = modelMapper();
        this.modules = modules;
    }

    /** One serialization contract for domain models and shared model modules. */
    static ObjectMapper modelMapper() {
        ObjectMapper mapper = new ObjectMapper();
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
        return mapper;
    }

    public void save(
            GeneratedProjectModel model,
            File file) throws IOException {

        if (model == null) {
            throw new IllegalArgumentException(
                    "model must not be null");
        }

        model.ensureDeclarationIdentities();
        GeneratedProjectModelValidator.ValidationResult validation =
                validateResolved(model);

        if (!validation.valid()) {
            throw new IOException(
                    "Cannot save invalid model:"
                            + System.lineSeparator()
                            + validation.format());
        }

        PopulationSourceBindings.synchronize(model);
        ClassSourceBindings.synchronize(model);
        FieldSourceBindings.synchronizeForSave(model);
        model.ensureDeclarationIdentities();

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

        model.ensureDeclarationIdentities();
        GeneratedProjectModelValidator.ValidationResult validation =
                validateResolved(model);

        if (!validation.valid()) {
            throw new IOException(
                    "Cannot serialize invalid model:"
                            + System.lineSeparator()
                            + validation.format());
        }

        PopulationSourceBindings.synchronize(model);
        ClassSourceBindings.synchronize(model);
        FieldSourceBindings.synchronizeForSave(model);
        model.ensureDeclarationIdentities();

        return mapper.writeValueAsString(model);
    }

    private GeneratedProjectModelValidator.ValidationResult validateResolved(
            GeneratedProjectModel model) throws IOException {
        try {
            return GeneratedProjectModelValidator.validate(
                    ModelImportResolver.resolve(model, modules));
        } catch (RuntimeException unresolved) {
            throw new IOException("Cannot resolve model imports: "
                    + unresolved.getMessage(), unresolved);
        }
    }

    public GeneratedProjectModel load(
            File file) throws IOException {

        JsonNode tree = mapper.readTree(file);
        migrateRemovedCanonicalKind(tree);
        GeneratedProjectModel model = mapper.treeToValue(tree, GeneratedProjectModel.class);
        OwnedClassSemantics.migrateLegacy(model);
        model.ensureDeclarationIdentities();
        model.reconcileSourceBindingTargets();
        PopulationSourceBindings.synchronize(model);
        ClassSourceBindings.synchronize(model);
        FieldSourceBindings.migrateOnLoad(model);
        model.ensureDeclarationIdentities();
        return model;
    }

    /**
     * One-way file migration for the discriminator removed from CanonicalSpec.
     * Class construction is authoritative now, so the old value carries no state
     * forward; removing it before binding lets old user-saved models load without
     * retaining a dead compatibility property on the Java model.
     */
    private static void migrateRemovedCanonicalKind(JsonNode node) {
        if (node == null) return;
        if (node instanceof ObjectNode object) {
            JsonNode canonical = object.get("canonical");
            if (canonical instanceof ObjectNode canonicalObject) {
                canonicalObject.remove("kind");
            }
        }
        node.forEach(GeneratedProjectModelStore::migrateRemovedCanonicalKind);
    }
}
