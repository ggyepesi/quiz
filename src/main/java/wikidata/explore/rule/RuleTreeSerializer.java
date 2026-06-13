package wikidata.explore.rule;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.io.File;
import java.io.IOException;

/**
 * Saves and loads a RuleNode tree configuration to/from JSON.
 *
 * Format:
 *
 *   RuleTreeConfig { version, name, description, root }
 *
 * Backward compatibility:
 *   - Old files containing a bare RuleNode are detected and wrapped.
 *   - v1 fields (requireEnglishLabel boolean, includeImage boolean) are
 *     migrated to RuleLabelConfig / RuleIncludedField via RuleNode's
 *     migration helpers after deserialization.
 */
public class RuleTreeSerializer {

    private final ObjectMapper mapper;

    public RuleTreeSerializer() {
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
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                "@class");

        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    public void save(RuleNode rootNode, File file) throws IOException {
        save(RuleTreeConfig.of(rootNode), file);
    }

    public void save(RuleNode rootNode, String filename) throws IOException {
        save(rootNode, new File(filename));
    }

    public void save(RuleTreeConfig config, String filename) throws IOException {
        save(config, new File(filename));
    }

    public void save(RuleTreeConfig config, File file) throws IOException {
        validateConfigForSave(config);
        ensureParentDirectory(file);

        // Build a snapshot config so mutation before writeValue doesn't matter
        RuleTreeConfig snapshot = new RuleTreeConfig(
                config.version(),
                config.name(),
                config.description(),
                config.root());

        mapper.writeValue(file, snapshot);
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    public RuleNode load(File file) throws IOException {
        return loadConfig(file).root();
    }

    public RuleNode load(String filename) throws IOException {
        return load(new File(filename));
    }

    public RuleTreeConfig loadConfig(String filename) throws IOException {
        return loadConfig(new File(filename));
    }

    public RuleTreeConfig loadConfig(File file) throws IOException {
        JsonNode rootJson = mapper.readTree(file);

        RuleTreeConfig config;

        if (looksLikeWrappedConfig(rootJson)) {
            config = mapper.treeToValue(rootJson, RuleTreeConfig.class);
        } else {
            // Backward compatibility: bare RuleNode written by the original
            // serializer before RuleTreeConfig was introduced.
            RuleNode root = mapper.treeToValue(rootJson, RuleNode.class);
            config = RuleTreeConfig.of(root);
        }

        migrateIfNeeded(config);
        validateConfigAfterLoad(config, file);

        return config;
    }

    // ------------------------------------------------------------------
    // Migration
    // ------------------------------------------------------------------

    private void migrateIfNeeded(RuleTreeConfig config) {
        if (config.version() <= 0) {
            config.version(RuleTreeConfig.CURRENT_VERSION);
        }

        // Migrate legacy boolean fields on every RuleNode in the tree
        if (config.root() != null) {
            migrateNodeTree(config.root());
        }

        if (config.version() > RuleTreeConfig.CURRENT_VERSION) {
            throw new IllegalStateException(
                    "Config version " + config.version()
                    + " is newer than supported version "
                    + RuleTreeConfig.CURRENT_VERSION);
        }

        // Future version migrations go here:
        // if (config.version() == 1) { migrateV1ToV2(config); config.version(2); }
    }

    /**
     * Recursively migrates legacy boolean fields in every RuleNode
     * in the tree (requireEnglishLabel → RuleLabelConfig,
     * includeImage → RuleIncludedField).
     */
    private static void migrateNodeTree(RuleNode node) {
        if (node == null) return;

        node.migrateRequireEnglishLabel();
        node.migrateIncludeImage();

        for (RuleEdge edge : node.edges()) {
            if (edge != null && edge.childNode() != null) {
                migrateNodeTree(edge.childNode());
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * A JSON object is a wrapped RuleTreeConfig if it has both "version"
     * and "root" fields. Using "version" as the discriminator prevents
     * false positives from RuleNodes that have a child edge named "root".
     */
    private static boolean looksLikeWrappedConfig(JsonNode rootJson) {
        return rootJson != null
                && rootJson.isObject()
                && rootJson.has("version")
                && rootJson.has("root");
    }

    private static void validateConfigForSave(RuleTreeConfig config) {
        if (config == null)
            throw new IllegalArgumentException("config must not be null");
        if (config.root() == null)
            throw new IllegalArgumentException("config.root must not be null");
        if (config.version() <= 0)
            config.version(RuleTreeConfig.CURRENT_VERSION);
    }

    private static void validateConfigAfterLoad(
            RuleTreeConfig config, File file) {

        if (config == null)
            throw new IllegalStateException(
                    "Could not load rule tree config from " + file);
        if (config.root() == null)
            throw new IllegalStateException(
                    "Rule tree config has no root node: " + file);
    }

    private static void ensureParentDirectory(File file) throws IOException {
        if (file == null)
            throw new IllegalArgumentException("file must not be null");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IOException(
                    "Could not create directory: " + parent.getAbsolutePath());
    }
}
