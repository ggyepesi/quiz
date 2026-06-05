package wikidata.explore.tree;

import com.fasterxml.jackson.annotation.JsonProperty;
import wikidata.explore.filter.WikidataValueFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RuleNode {

    // -----------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------

    private String name;
    private String itemVar;

    // -----------------------------------------------------------------
    // Source / property
    // -----------------------------------------------------------------

    private String sourceQid    = "";
    private String sourceLabel  = "";
    private String propertyPid  = "";
    private String propertyLabel = "";
    private RuleDirection direction = RuleDirection.ITEM_TO_ROOT;

    // -----------------------------------------------------------------
    // Label config (replaces the old requireEnglishLabel boolean)
    // -----------------------------------------------------------------

    private RuleLabelConfig labelConfig = new RuleLabelConfig();

    /**
     * Kept for Jackson backward-compatibility with v1 JSON files that
     * serialised a plain {@code requireEnglishLabel} boolean.
     * {@link RuleTreeSerializer} calls
     * {@link #migrateRequireEnglishLabel} during load if this is non-null.
     *
     * @deprecated use {@link #labelConfig()} instead.
     */
    @Deprecated
    @JsonProperty("requireEnglishLabel")
    private Boolean requireEnglishLabelLegacy = null;

    // -----------------------------------------------------------------
    // Included direct fields (replaces the old includeImage boolean)
    // -----------------------------------------------------------------

    private final List<RuleIncludedField> includedFields = new ArrayList<>();

    /**
     * Kept for Jackson backward-compatibility with v1 JSON files that
     * serialised a plain {@code includeImage} boolean.
     * {@link RuleTreeSerializer} calls
     * {@link #migrateIncludeImage} during load if this is non-null.
     *
     * @deprecated use {@link #includedFields()} instead.
     */
    @Deprecated
    @JsonProperty("includeImage")
    private Boolean includeImageLegacy = null;

    // -----------------------------------------------------------------
    // Filters / exclusions / edges
    // -----------------------------------------------------------------

    private int limit = 200;

    private final Set<String>  includedQids = new LinkedHashSet<>();
    private final Set<String>  excludedQids = new LinkedHashSet<>();

    private final List<PredicateObjectExclusion> excludedPredicateObjects =
            new ArrayList<>();

    private final List<WikidataValueFilter> valueFilters =
            new ArrayList<>();

    private final List<RuleEdge> edges = new ArrayList<>();

    // -----------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------

    public RuleNode(String name, String itemVar) {
        this.name    = name;
        this.itemVar = itemVar;
    }

    // -----------------------------------------------------------------
    // Migration helpers (called by RuleTreeSerializer after load)
    // -----------------------------------------------------------------

    /**
     * If an old JSON file contained {@code "requireEnglishLabel": true/false},
     * Jackson will have populated {@link #requireEnglishLabelLegacy}.
     * This method migrates that value into {@link #labelConfig} and clears
     * the legacy field so it isn't saved back out.
     */
    public void migrateRequireEnglishLabel() {
        if (requireEnglishLabelLegacy == null) {
            return;
        }

        boolean require = requireEnglishLabelLegacy;
        labelConfig = new RuleLabelConfig(require, "en");
        requireEnglishLabelLegacy = null;
    }

    /**
     * If an old JSON file contained {@code "includeImage": true},
     * Jackson will have populated {@link #includeImageLegacy}.
     * This method adds the standard P18 included field and clears
     * the legacy field.
     */
    public void migrateIncludeImage() {
        if (includeImageLegacy == null) {
            return;
        }

        if (includeImageLegacy
                && includedFields.stream()
                                 .noneMatch(f -> "P18".equals(f.propertyPid()))) {
            includedFields.add(RuleIncludedField.imageP18());
        }

        includeImageLegacy = null;
    }

    // -----------------------------------------------------------------
    // Accessors — identity
    // -----------------------------------------------------------------

    public String name() { return name; }
    public void   name(String name) { this.name = name; }

    public String itemVar() { return itemVar; }
    public void   itemVar(String itemVar) { this.itemVar = itemVar; }

    // -----------------------------------------------------------------
    // Accessors — source / property
    // -----------------------------------------------------------------

    public String sourceQid()  { return sourceQid; }
    public void   sourceQid(String sourceQid)
        { this.sourceQid = cleanQid(sourceQid); }

    public String sourceLabel() { return sourceLabel; }
    public void   sourceLabel(String sourceLabel)
        { this.sourceLabel = sourceLabel == null ? "" : sourceLabel.trim(); }

    public String propertyPid()  { return propertyPid; }
    public void   propertyPid(String propertyPid)
        { this.propertyPid = cleanPid(propertyPid); }

    public String propertyLabel() { return propertyLabel; }
    public void   propertyLabel(String propertyLabel)
        { this.propertyLabel = propertyLabel == null ? "" : propertyLabel.trim(); }

    public RuleDirection direction() { return direction; }
    public void direction(RuleDirection direction)
        { this.direction = direction == null ? RuleDirection.ITEM_TO_ROOT : direction; }

    // -----------------------------------------------------------------
    // Accessors — label config
    // -----------------------------------------------------------------

    public RuleLabelConfig labelConfig() { return labelConfig; }
    public void labelConfig(RuleLabelConfig labelConfig)
        { this.labelConfig = labelConfig == null ? new RuleLabelConfig() : labelConfig; }

    /**
     * Convenience forwarder so call sites that only care about
     * English-or-not don't need to unwrap the config.
     */
    public boolean requireEnglishLabel() {
        return labelConfig != null
                && labelConfig.requireLabel()
                && "en".equalsIgnoreCase(labelConfig.language());
    }

    /**
     * Convenience setter — equivalent to setting a new RuleLabelConfig
     * with the given flag and language="en". Kept so existing editor
     * code compiles without change during the migration period.
     */
    public void requireEnglishLabel(boolean require) {
        labelConfig = new RuleLabelConfig(require, "en");
    }

    // -----------------------------------------------------------------
    // Accessors — included fields
    // -----------------------------------------------------------------

    public List<RuleIncludedField> includedFields() { return includedFields; }

    public RuleNode addIncludedField(RuleIncludedField field) {
        if (field != null) includedFields.add(field);
        return this;
    }

    /**
     * Convenience — returns true if any included field targets P18.
     * Replaces the old {@code includeImage()} boolean.
     */
    public boolean includeImage() {
        return includedFields.stream()
                             .anyMatch(f -> "P18".equals(f.propertyPid()));
    }

    /**
     * Convenience setter for backward compatibility with old call sites.
     * Adds or removes the standard P18 image field.
     */
    public void includeImage(boolean include) {
        boolean already = includeImage();
        if (include && !already) {
            includedFields.add(RuleIncludedField.imageP18());
        } else if (!include && already) {
            includedFields.removeIf(f -> "P18".equals(f.propertyPid()));
        }
    }

    // -----------------------------------------------------------------
    // Accessors — limit / QIDs / exclusions / filters / edges
    // -----------------------------------------------------------------

    public int  limit() { return limit; }
    public void limit(int limit) { this.limit = Math.max(1, limit); }

    public Set<String>  includedQids() { return includedQids; }
    public Set<String>  excludedQids()  { return excludedQids; }

    public List<PredicateObjectExclusion> excludedPredicateObjects()
        { return excludedPredicateObjects; }

    public List<WikidataValueFilter> valueFilters() { return valueFilters; }
    public List<RuleEdge>            edges()         { return edges; }

    public RuleNode addIncludedQid(String qid) {
        qid = cleanQid(qid);
        if (!qid.isBlank()) includedQids.add(qid);
        return this;
    }

    public RuleNode addExcludedQid(String qid) {
        qid = cleanQid(qid);
        if (!qid.isBlank()) excludedQids.add(qid);
        return this;
    }

    public RuleNode addValueFilter(WikidataValueFilter filter) {
        if (filter != null) valueFilters.add(filter);
        return this;
    }

    public RuleNode addEdge(RuleEdge edge) {
        if (edge != null) edges.add(edge);
        return this;
    }

    // -----------------------------------------------------------------
    // QID / exclusion text helpers
    // -----------------------------------------------------------------

    public String includedQidsText()          { return qidsText(includedQids); }
    public void   includedQidsText(String t)  { includedQids.clear();  includedQids.addAll(parseQids(t)); }

    public String excludedQidsText()          { return qidsText(excludedQids); }
    public void   excludedQidsText(String t)  { excludedQids.clear();  excludedQids.addAll(parseQids(t)); }

    public String excludedPredicateObjectsText()
        { return predicateObjectExclusionsText(excludedPredicateObjects); }

    public void excludedPredicateObjectsText(String text) {
        excludedPredicateObjects.clear();
        excludedPredicateObjects.addAll(parsePredicateObjectExclusions(text));
    }

    // -----------------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------------

    public String directionPreviewText() {
        return direction.previewText(
                name,
                propertyLabel == null || propertyLabel.isBlank()
                        ? propertyPid : propertyLabel,
                sourceLabel == null || sourceLabel.isBlank()
                        ? sourceQid : sourceLabel);
    }

    public String relationPhrase() {
        String left = sourceLabel == null || sourceLabel.isBlank()
                ? sourceQid : sourceLabel;
        String prop = propertyLabel == null || propertyLabel.isBlank()
                ? propertyPid : propertyLabel;
        return direction == RuleDirection.ITEM_TO_ROOT
                ? blankTo(name, "item") + " " + prop + " " + left
                : left + " " + prop + " " + blankTo(name, "item");
    }

    public String displayName() {
        StringBuilder sb = new StringBuilder();
        sb.append(name == null || name.isBlank() ? "Node" : name);
        if (propertyPid != null && !propertyPid.isBlank())
            sb.append("  [").append(relationPhrase()).append("]");
        if (!includedQids.isEmpty())
            sb.append("  include ").append(includedQids.size())
              .append(" QID").append(includedQids.size() == 1 ? "" : "s");
        if (!excludedQids.isEmpty())
            sb.append("  exclude ").append(excludedQids.size())
              .append(" QID").append(excludedQids.size() == 1 ? "" : "s");
        if (!includedFields.isEmpty())
            sb.append("  +").append(includedFields.size()).append(" field")
              .append(includedFields.size() == 1 ? "" : "s");
        return sb.toString();
    }

    @Override public String toString() { return displayName(); }

    // -----------------------------------------------------------------
    // Static utilities
    // -----------------------------------------------------------------

    public static String cleanQid(String qid) {
        if (qid == null) return "";
        qid = qid.trim();
        if (qid.startsWith("wd:")) qid = qid.substring(3);
        return qid.trim();
    }

    public static String cleanPid(String pid) {
        if (pid == null) return "";
        pid = pid.trim();
        if (pid.startsWith("wdt:")) pid = pid.substring(4);
        return pid.trim();
    }

    public static Set<String> parseQids(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        for (String token : text.split("[,;\\s]+")) {
            String qid = cleanQid(token);
            if (qid.matches("Q\\d+")) out.add(qid);
        }
        return out;
    }

    public static String qidsText(Set<String> qids) {
        return qids == null || qids.isEmpty() ? "" : String.join(" ", qids);
    }

    public static List<PredicateObjectExclusion>
    parsePredicateObjectExclusions(String text) {
        List<PredicateObjectExclusion> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split("[,;\\s]+")) {
            String[] pieces = part.split("[:=]");
            if (pieces.length != 2) continue;
            String pid = cleanPid(pieces[0]);
            String qid = cleanQid(pieces[1]);
            if (pid.matches("P\\d+") && qid.matches("Q\\d+"))
                out.add(new PredicateObjectExclusion(pid, qid));
        }
        return out;
    }

    public static String predicateObjectExclusionsText(
            List<PredicateObjectExclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PredicateObjectExclusion e : exclusions) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(e.predicatePid()).append(":").append(e.objectQid());
        }
        return sb.toString();
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    public record PredicateObjectExclusion(String predicatePid,
                                           String objectQid) {}
}
