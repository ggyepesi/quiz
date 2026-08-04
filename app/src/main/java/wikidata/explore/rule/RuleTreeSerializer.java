package wikidata.explore.rule;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
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
        RuleTreeConfig config = mapper.readValue(file, RuleTreeConfig.class);
        validateConfigAfterLoad(config, file);

        return config;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void validateConfigForSave(RuleTreeConfig config) {
        if (config == null)
            throw new IllegalArgumentException("config must not be null");
        if (config.root() == null)
            throw new IllegalArgumentException("config.root must not be null");
        if (config.version() != RuleTreeConfig.CURRENT_VERSION)
            throw new IllegalArgumentException("Rule-tree version " + config.version()
                    + " is not supported; regenerate it with version "
                    + RuleTreeConfig.CURRENT_VERSION);
    }

    private static void validateConfigAfterLoad(
            RuleTreeConfig config, File file) {

        if (config == null)
            throw new IllegalStateException(
                    "Could not load rule tree config from " + file);
        if (config.root() == null)
            throw new IllegalStateException(
                    "Rule tree config has no root node: " + file);
        if (config.version() != RuleTreeConfig.CURRENT_VERSION)
            throw new IllegalStateException("Rule-tree version " + config.version()
                    + " is not supported; regenerate " + file + " with version "
                    + RuleTreeConfig.CURRENT_VERSION);
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
