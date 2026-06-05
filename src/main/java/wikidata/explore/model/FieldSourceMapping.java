package wikidata.explore.model;

import wikidata.explore.tree.RuleDirection;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mapping details for a visible class or field.
 *
 * In the normal UI only a subset is shown. Direction, production kind and
 * filters belong under Advanced later.
 */
public class FieldSourceMapping {

    private String sourceQid = "";
    private String sourceLabel = "";

    private String propertyPid = "";
    private String propertyLabel = "";

    private RuleDirection direction = RuleDirection.ITEM_TO_ROOT;

    private boolean requireLabel = true;
    private String labelLanguage = "en";

    private int limit = 200;

    private FieldProductionKind productionKind = FieldProductionKind.AUTO;

    private final Set<String> allowedQids = new LinkedHashSet<>();
    private final Set<String> excludedQids = new LinkedHashSet<>();

    public String sourceQid() { return sourceQid; }
    public void sourceQid(String sourceQid) {
        this.sourceQid = sourceQid == null ? "" : sourceQid.trim();
    }

    public String sourceLabel() { return sourceLabel; }
    public void sourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel == null ? "" : sourceLabel.trim();
    }

    public String propertyPid() { return propertyPid; }
    public void propertyPid(String propertyPid) {
        this.propertyPid = propertyPid == null ? "" : propertyPid.trim();
    }

    public String propertyLabel() { return propertyLabel; }
    public void propertyLabel(String propertyLabel) {
        this.propertyLabel = propertyLabel == null ? "" : propertyLabel.trim();
    }

    public RuleDirection direction() { return direction; }
    public void direction(RuleDirection direction) {
        this.direction = direction == null ? RuleDirection.ITEM_TO_ROOT : direction;
    }

    public boolean requireLabel() { return requireLabel; }
    public void requireLabel(boolean requireLabel) {
        this.requireLabel = requireLabel;
    }

    public String labelLanguage() { return labelLanguage; }
    public void labelLanguage(String labelLanguage) {
        this.labelLanguage =
                labelLanguage == null || labelLanguage.isBlank()
                        ? "en"
                        : labelLanguage.trim();
    }

    public int limit() { return limit; }
    public void limit(int limit) {
        this.limit = Math.max(1, limit);
    }

    public FieldProductionKind productionKind() { return productionKind; }
    public void productionKind(FieldProductionKind productionKind) {
        this.productionKind =
                productionKind == null ? FieldProductionKind.AUTO : productionKind;
    }

    public Set<String> allowedQids() { return allowedQids; }
    public Set<String> excludedQids() { return excludedQids; }

    public String displaySource() {
        if (sourceQid.isBlank() && sourceLabel.isBlank()) {
            return "(not selected)";
        }
        return sourceLabel.isBlank()
                ? sourceQid
                : sourceLabel + " (" + sourceQid + ")";
    }

    public String displayProperty() {
        if (propertyPid.isBlank() && propertyLabel.isBlank()) {
            return "(not selected)";
        }
        return propertyLabel.isBlank()
                ? propertyPid
                : propertyLabel + " (" + propertyPid + ")";
    }
}
