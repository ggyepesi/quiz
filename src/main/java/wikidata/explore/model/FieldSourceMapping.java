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

    // For a field of a STATEMENT-reification class (see GeneratedClassModel.
    // statementSourceClass): when set, this field's value is this QUALIFIER of the
    // reified statement (e.g. P585 → year, P1686 → for work) rather than a direct
    // claim. Blank = not a qualifier field.
    private String qualifierPid = "";

    // COMPANION_MATCH only: which of THIS record's fields form the match key. The
    // outcome is true iff a companion statement (subject propertyPid [ps=value,
    // qualifierPid=role]) exists with the same (subject, value, role). The companion
    // SUBJECT is the entity in subjectField (blank = the reify "source"); e.g. an
    // Oscar win is on the NOMINEE (P166 category [P1686 for-work=film]), so
    // subjectField=nominee, matchValueField=category, matchRoleField=source. The
    // role is COALESCE(the qualifier, the subject) so a win with no for-work (on the
    // work itself, e.g. Best Picture) still keys back to the work.
    private String subjectField = "";
    private String matchValueField = "";
    private String matchRoleField = "";

    // Reify decomposition (statement classes): explicit overrides for the two
    // concerns the "single-ENTITY qualifier" inference used to bundle together.
    // null = the inferred default (keeps legacy models unchanged); TRUE/FALSE
    // overrides it — the derived recipe becomes authoritative + editable (#92).
    //   subjectDefault: when this qualifier is ABSENT on a statement, fill the field
    //     with the statement's SUBJECT (the reify source). Right for the nominee
    //     (the subject IS the nominee) and a dedup-bridge like forWork; WRONG for a
    //     plain third-party reference like edition — an absent ceremony must stay
    //     empty, not collapse to the film (the Whale phantom, #95).
    //   inDedupKey: whether this field is part of the reified record's identity key.
    // Field-scoped NON_NULL so the common "inferred" case adds nothing to the model.
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean subjectDefault = null;
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean inDedupKey = null;

    private RuleDirection direction = RuleDirection.ITEM_TO_ROOT;

    private boolean requireLabel = true;
    private String labelLanguage = "en";

    // "Notable only": require the entity to have an English Wikipedia article.
    // The sitelink is a selective entry that bounds a huge class (e.g. Q523
    // star, ~3M) to its notable members (~2886), so a root query can complete
    // and return famous entities instead of timing out on a full-class scan.
    private boolean requireSitelink = false;

    private int limit = 200;

    // Class-level ranking: which instances of the class to KEEP (top `limit`).
    // "" = none (order by label); "__sitelinks" = by Wikipedia sitelink count
    // (notability/importance); otherwise the NAME of a sortable field of the
    // class (its property is used, e.g. brightness/area/population). Replaces
    // per-field "sort children by".
    private String rankBy = "";
    private boolean rankDescending = true;

    private FieldProductionKind productionKind = FieldProductionKind.AUTO;

    private final Set<String> allowedQids = new LinkedHashSet<>();
    private final Set<String> excludedQids = new LinkedHashSet<>();

    // Extra type QIDs for class membership: an entity is a member if it is
    // instance-of the sourceQid OR any of these. Lets a class admit a specific
    // subclass without a (slow, over-broad) P279* path — e.g. Constellation =
    // {constellation Q8928, zodiacal constellation Q4193029} to include Aries &
    // Cancer, which are typed only as the subclass.
    private final Set<String> additionalTypeQids = new LinkedHashSet<>();

    // Type QIDs to EXCLUDE from membership: drop any entity that is instance-of
    // (P31) one of these, even if it matched the membership above — e.g. exclude
    // Roman deity (Q11688446) from a Greek-character class. Emitted as
    // FILTER NOT EXISTS { ?value wdt:P31 wd:Qexcluded }.
    private final Set<String> excludedTypeQids = new LinkedHashSet<>();

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

    public String qualifierPid() { return qualifierPid; }
    public void qualifierPid(String qualifierPid) {
        this.qualifierPid = qualifierPid == null ? "" : qualifierPid.trim();
    }
    public boolean isQualifier() {
        return qualifierPid != null && qualifierPid.trim().matches("(?i)P\\d+");
    }

    public String subjectField() { return subjectField; }
    public void subjectField(String v) { subjectField = v == null ? "" : v.trim(); }

    public String matchValueField() { return matchValueField; }
    public void matchValueField(String v) { matchValueField = v == null ? "" : v.trim(); }

    public String matchRoleField() { return matchRoleField; }
    public void matchRoleField(String v) { matchRoleField = v == null ? "" : v.trim(); }

    // null = inferred default; TRUE/FALSE = explicit override. See field comment.
    public Boolean subjectDefault() { return subjectDefault; }
    public void subjectDefault(Boolean v) { subjectDefault = v; }
    public Boolean inDedupKey() { return inDedupKey; }
    public void inDedupKey(Boolean v) { inDedupKey = v; }

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
    public Set<String> excludedTypeQids() { return excludedTypeQids; }

    public String rankBy() { return rankBy; }
    public void rankBy(String v) { rankBy = v == null ? "" : v.trim(); }
    public boolean rankDescending() { return rankDescending; }
    public void rankDescending(boolean v) { rankDescending = v; }
    public static final String RANK_BY_SITELINKS = "__sitelinks";

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
        qualifierPid = other.qualifierPid;
        subjectField = other.subjectField;
        matchValueField = other.matchValueField;
        matchRoleField = other.matchRoleField;
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
        excludedTypeQids.clear();
        excludedTypeQids.addAll(other.excludedTypeQids);
        rankBy = other.rankBy;
        rankDescending = other.rankDescending;
    }

    public FieldSourceType sourceType() {
        return sourceType;
    }

    public void sourceType(FieldSourceType sourceType) {
        this.sourceType =
                sourceType == null ? FieldSourceType.SPARQL : sourceType;
    }
}
