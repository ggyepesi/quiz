package wikidata.explore.model;


import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mapping details for a visible class or field.
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

    // "Notable only": require the entity to have an English Wikipedia article.
    // The sitelink is a selective entry that bounds a huge class (e.g. Q523
    // star, ~3M) to its notable members (~2886), so a root query can complete
    // and return famous entities instead of timing out on a full-class scan.
    private boolean requireSitelink = false;

    private int limit = 200;

    private FieldProductionKind productionKind = FieldProductionKind.AUTO;

    private final Set<String> allowedQids = new LinkedHashSet<>();
    private final Set<String> excludedQids = new LinkedHashSet<>();

    // Extra type QIDs for class membership: an entity is a member if it is
    // instance-of the sourceQid OR any of these. Lets a class admit a specific
    // subclass without a (slow, over-broad) P279* path — e.g. Constellation =
    // {constellation Q8928, zodiacal constellation Q4193029} to include Aries &
    // Cancer, which are typed only as the subclass.
    private final Set<String> additionalTypeQids = new LinkedHashSet<>();

    private FieldSourceType sourceType = FieldSourceType.SPARQL;

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

    public boolean requireSitelink() { return requireSitelink; }
    public void requireSitelink(boolean requireSitelink) {
        this.requireSitelink = requireSitelink;
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
    public Set<String> additionalTypeQids() { return additionalTypeQids; }

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

    public FieldSourceMapping copy() {
        FieldSourceMapping c = new FieldSourceMapping();
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(FieldSourceMapping other) {
        if (other == null) {
            return;
        }

        sourceQid = other.sourceQid;
        sourceLabel = other.sourceLabel;
        propertyPid = other.propertyPid;
        propertyLabel = other.propertyLabel;
        direction = other.direction;
        requireLabel = other.requireLabel;
        requireSitelink = other.requireSitelink;
        labelLanguage = other.labelLanguage;
        limit = other.limit;
        productionKind = other.productionKind;
        sourceType = other.sourceType;

        allowedQids.clear();
        allowedQids.addAll(other.allowedQids);
        excludedQids.clear();
        excludedQids.addAll(other.excludedQids);
        additionalTypeQids.clear();
        additionalTypeQids.addAll(other.additionalTypeQids);
    }

    public FieldSourceType sourceType() {
        return sourceType;
    }

    public void sourceType(FieldSourceType sourceType) {
        this.sourceType =
                sourceType == null ? FieldSourceType.SPARQL : sourceType;
    }
}
