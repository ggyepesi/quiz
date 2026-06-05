package wikidata.explore.tree;

/**
 * Versioned wrapper around a serialized RuleNode tree.
 *
 * Storing the root node directly works today, but a wrapper gives us a stable
 * place for future metadata and migrations:
 *
 *   version
 *   name
 *   description
 *   createdBy
 *   root
 */
public class RuleTreeConfig {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private String name = "";
    private String description = "";
    private RuleNode root;

    public RuleTreeConfig() {
    }

    public RuleTreeConfig(RuleNode root) {
        this.root = root;
    }

    public RuleTreeConfig(
            int version,
            String name,
            String description,
            RuleNode root) {

        this.version = version;
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.root = root;
    }

    public static RuleTreeConfig of(RuleNode root) {
        return new RuleTreeConfig(root);
    }

    public int version() {
        return version;
    }

    public void version(int version) {
        this.version = version;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name == null ? "" : name;
    }

    public String description() {
        return description;
    }

    public void description(String description) {
        this.description = description == null ? "" : description;
    }

    public RuleNode root() {
        return root;
    }

    public void root(RuleNode root) {
        this.root = root;
    }

    @Override
    public String toString() {
        return name == null || name.isBlank()
                ? "RuleTreeConfig v" + version
                : name + " (v" + version + ")";
    }
}
