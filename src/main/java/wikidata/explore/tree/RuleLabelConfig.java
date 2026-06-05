package wikidata.explore.tree;

/**
 * Label configuration for a RuleNode.
 *
 * requireLabel:
 *   true  -> result must have a label
 *   false -> label is optional
 *
 * language:
 *   "" or "any" -> any language
 *   "en", "hu", "de", ... -> specific language
 */
public class RuleLabelConfig {

    private boolean requireLabel = true;
    private String language = "en";

    public RuleLabelConfig() {
    }

    public RuleLabelConfig(boolean requireLabel, String language) {
        this.requireLabel = requireLabel;
        this.language = normalizeLanguage(language);
    }

    public boolean requireLabel() {
        return requireLabel;
    }

    public void requireLabel(boolean requireLabel) {
        this.requireLabel = requireLabel;
    }

    public String language() {
        return language;
    }

    public void language(String language) {
        this.language = normalizeLanguage(language);
    }

    public boolean anyLanguage() {
        return language == null
                || language.isBlank()
                || "any".equalsIgnoreCase(language);
    }

    public static String normalizeLanguage(String language) {
        if (language == null) {
            return "en";
        }

        language = language.trim();

        if (language.isBlank()) {
            return "any";
        }

        return language.toLowerCase();
    }

    @Override
    public String toString() {
        return requireLabel
                ? "Require label: " + language
                : "Optional label: " + language;
    }
}
