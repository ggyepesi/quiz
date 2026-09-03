package wikidata.explore.model;

import wikidata.LabelledId;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mapping details for a visible class or field.
 *
 * <p>In the normal UI only a subset is shown. Direction, production kind and
 * filters belong under Advanced later.</p>
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
    // A qualifier is a property too, and was the only one with nowhere to keep its
    // name: a statement class could say its value role is "position held (P39)" while
    // every qualifier role could say only "P580".
    private String qualifierLabel = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private QualifierDateMode qualifierDateMode;

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

    /** INVERT only: the explicit forward reference on the referenced class. Blank is
     * retained for legacy models, where ModelInverts may infer an unambiguous field. */
    private String inverseField = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private MissingQualifierPolicy missingQualifierPolicy;

    /**
     * The semantic role this field plays when reified (#99). Marks the subject's
     * own identity ({@code IDENTITY} — e.g. nominee) apart from references to other
     * entities ({@code REFERENCE} — e.g. forWork) so self-referential phantom
     * detection only treats a REFERENCE role as evidence of denormalization.
     * Persisted only when non-default; null reads back as {@code REFERENCE}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private RoleKind roleKind;

    private RuleDirection direction = RuleDirection.ITEM_TO_ROOT;
    private boolean requireLabel = true;
    private String labelLanguage = wikidata.WikidataLanguageDefaults.CODE;
    // Blank = preserve every truthy claim value. A language code asks field
    // projection to interpret P407 qualifiers; parsing remains lossless.
    private String valueLanguage = "";
    // "Notable only": require the entity to have an English Wikipedia article.
    // The sitelink is a selective entry that bounds a huge class (e.g. Q523
    // star, ~3M) to its notable members (~2886), so a root query can complete
    // and return famous entities instead of timing out on a full-class scan.
    private boolean requireSitelink = false;
    private int limit = 200;

    private String rankBy = "";
    private boolean rankDescending = true;

    private FieldProductionKind productionKind = FieldProductionKind.AUTO;

    private final Set<String> allowedQids = new LinkedHashSet<>();
    private final Set<String> excludedQids = new LinkedHashSet<>();
    private final Set<String> additionalTypeQids = new LinkedHashSet<>();
    private final Set<String> excludedTypeQids = new LinkedHashSet<>();

    private FieldSourceType sourceType = FieldSourceType.SPARQL;

    public String sourceQid() {
        return sourceQid;
    }

    public void sourceQid(String sourceQid) {
        this.sourceQid = clean(sourceQid);
    }

    public String sourceLabel() {
        return sourceLabel;
    }

    public void sourceLabel(String sourceLabel) {
        this.sourceLabel = clean(sourceLabel);
    }

    public String propertyPid() {
        return propertyPid;
    }

    public void propertyPid(String propertyPid) {
        this.propertyPid = clean(propertyPid);
    }

    public String propertyLabel() {
        return propertyLabel;
    }

    public void propertyLabel(String propertyLabel) {
        this.propertyLabel = clean(propertyLabel);
    }

    public String qualifierPid() {
        return qualifierPid;
    }

    public String qualifierLabel() {
        return qualifierLabel == null ? "" : qualifierLabel;
    }

    public void qualifierLabel(String value) {
        this.qualifierLabel = clean(value);
    }

    /** "start time (P580)" when the name is known, otherwise just the PID. */
    public String displayQualifier() {
        String pid = qualifierPid();
        if (pid == null || pid.isBlank()) return "";
        return LabelledId.display(qualifierLabel(), pid);
    }

    public void qualifierPid(String qualifierPid) {
        this.qualifierPid = clean(qualifierPid);
    }

    public boolean isQualifier() {
        return qualifierPid.matches("(?i)P\\d+");
    }

    /** Null on disk means YEAR so existing models retain their old projection. */
    public QualifierDateMode qualifierDateMode() {
        return qualifierDateMode == null ? QualifierDateMode.YEAR : qualifierDateMode;
    }

    public void qualifierDateMode(QualifierDateMode value) {
        qualifierDateMode = value == null || value == QualifierDateMode.YEAR
                ? null : value;
    }

    public String subjectField() {
        return subjectField;
    }

    public void subjectField(String value) {
        subjectField = clean(value);
    }

    public String matchValueField() {
        return matchValueField;
    }

    public void matchValueField(String value) {
        matchValueField = clean(value);
    }

    public String matchRoleField() {
        return matchRoleField;
    }

    public void matchRoleField(String value) {
        matchRoleField = clean(value);
    }

    public String inverseField() { return inverseField; }

    public void inverseField(String value) { inverseField = clean(value); }

    public MissingQualifierPolicy missingQualifierPolicy() {
        return missingQualifierPolicy;
    }

    public void missingQualifierPolicy(MissingQualifierPolicy value) {
        missingQualifierPolicy = value;
    }

    public RoleKind roleKind() {
        return roleKind == null ? RoleKind.REFERENCE : roleKind;
    }

    public void roleKind(RoleKind value) {
        roleKind = value == RoleKind.REFERENCE ? null : value;
    }

    public RuleDirection direction() {
        return direction;
    }

    public void direction(RuleDirection direction) {
        this.direction = direction == null
                ? RuleDirection.ITEM_TO_ROOT
                : direction;
    }

    public boolean requireLabel() {
        return requireLabel;
    }

    public void requireLabel(boolean requireLabel) {
        this.requireLabel = requireLabel;
    }

    public boolean requireSitelink() {
        return requireSitelink;
    }

    public void requireSitelink(boolean requireSitelink) {
        this.requireSitelink = requireSitelink;
    }

    public String labelLanguage() {
        return labelLanguage;
    }

    public void labelLanguage(String labelLanguage) {
        this.labelLanguage = labelLanguage == null || labelLanguage.isBlank()
                ? wikidata.WikidataLanguageDefaults.CODE
                : labelLanguage.trim();
    }

    public String valueLanguage() { return valueLanguage; }

    public void valueLanguage(String value) { valueLanguage = clean(value); }

    public int limit() {
        return limit;
    }

    public void limit(int limit) {
        this.limit = Math.max(1, limit);
    }

    public FieldProductionKind productionKind() {
        return productionKind;
    }

    public void productionKind(FieldProductionKind productionKind) {
        this.productionKind = productionKind == null
                ? FieldProductionKind.AUTO
                : productionKind;
    }

    public Set<String> allowedQids() {
        return allowedQids;
    }

    public Set<String> excludedQids() {
        return excludedQids;
    }

    public Set<String> additionalTypeQids() {
        return additionalTypeQids;
    }

    public Set<String> excludedTypeQids() {
        return excludedTypeQids;
    }

    public String rankBy() {
        return rankBy;
    }

    public void rankBy(String value) {
        rankBy = clean(value);
    }

    public boolean rankDescending() {
        return rankDescending;
    }

    public void rankDescending(boolean value) {
        rankDescending = value;
    }

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
        return LabelledId.display(propertyLabel, propertyPid);
    }

    public FieldSourceMapping copy() {
        FieldSourceMapping copy = new FieldSourceMapping();
        copy.copyFrom(this);
        return copy;
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
        qualifierLabel = other.qualifierLabel;
        qualifierDateMode = other.qualifierDateMode;

        subjectField = other.subjectField;
        matchValueField = other.matchValueField;
        matchRoleField = other.matchRoleField;
        inverseField = other.inverseField;

        missingQualifierPolicy = other.missingQualifierPolicy;
        roleKind = other.roleKind;
        direction = other.direction;
        requireLabel = other.requireLabel;
        requireSitelink = other.requireSitelink;
        labelLanguage = other.labelLanguage;
        valueLanguage = other.valueLanguage;
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
        this.sourceType = sourceType == null
                ? FieldSourceType.SPARQL
                : sourceType;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
