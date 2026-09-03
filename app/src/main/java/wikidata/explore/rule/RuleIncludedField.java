package wikidata.explore.rule;

import wikidata.WikidataIds;

import wikidata.explore.model.RuleDirection;

public class RuleIncludedField {

    public enum FieldKind {
        AUTO,
        MEDIA,
        ENTITY,
        /** A time-valued field. The truthy leg drops the calendar and the precision
         *  a value states, so a date is read from the statement's value node
         *  instead — which is a different query, and this is how it is told apart. */
        DATE
    }

    private String fieldName;
    private String propertyPid;
    private String propertyLabel;
    private FieldKind kind = FieldKind.AUTO;
    private boolean optional = true;
    private boolean collection = false;
    // ROOT_TO_ITEM = outgoing (?value wdt:P ?x); ITEM_TO_ROOT = incoming
    // (?x wdt:P ?value), e.g. "episode in" / "facet of" pointing AT this entity.
    private RuleDirection direction = RuleDirection.ROOT_TO_ITEM;

    public boolean collection() {
        return collection;
    }

    public void collection(boolean collection) {
        this.collection = collection;
    }

    public RuleDirection direction() {
        return direction == null ? RuleDirection.ROOT_TO_ITEM : direction;
    }

    public void direction(RuleDirection direction) {
        this.direction = direction == null ? RuleDirection.ROOT_TO_ITEM : direction;
    }

    public boolean incoming() {
        return direction() == RuleDirection.ITEM_TO_ROOT;
    }

    // Optional type constraint on the field's VALUE (the other end): keep only
    // values that are instance-of (membershipPid) the referenced class's type
    // (membershipQid). Blank = no constraint.
    private String membershipPid = "";
    private String membershipQid = "";
    public String membershipPid() { return membershipPid == null ? "" : membershipPid; }
    public void   membershipPid(String p) { this.membershipPid = cleanPid(p); }
    public String membershipQid() { return membershipQid == null ? "" : membershipQid; }
    public void   membershipQid(String q) { this.membershipQid = q == null ? "" : q.trim(); }
    public boolean hasMembership() {
        return !membershipQid().isBlank() && WikidataIds.isQid(membershipQid());
    }

    public RuleIncludedField() {
    }

    public RuleIncludedField(
            String fieldName,
            String propertyPid,
            String propertyLabel,
            FieldKind kind,
            boolean optional) {

        this.fieldName = fieldName == null ? "" : fieldName.trim();
        this.propertyPid = cleanPid(propertyPid);
        this.propertyLabel = propertyLabel == null ? "" : propertyLabel.trim();
        this.kind = kind == null ? FieldKind.AUTO : kind;
        this.optional = optional;
    }

    public static RuleIncludedField imageP18() {
        return new RuleIncludedField(
                "image",
                "P18",
                "image",
                FieldKind.MEDIA,
                true);
    }

    public String fieldName() {
        return fieldName;
    }

    public void fieldName(String fieldName) {
        this.fieldName = fieldName == null ? "" : fieldName.trim();
    }

    public String propertyPid() {
        return propertyPid;
    }

    public void propertyPid(String propertyPid) {
        this.propertyPid = cleanPid(propertyPid);
    }

    public String propertyLabel() {
        return propertyLabel;
    }

    public void propertyLabel(String propertyLabel) {
        this.propertyLabel = propertyLabel == null ? "" : propertyLabel.trim();
    }

    public FieldKind kind() {
        return kind;
    }

    public void kind(FieldKind kind) {
        this.kind = kind == null ? FieldKind.AUTO : kind;
    }

    public boolean optional() {
        return optional;
    }

    public void optional(boolean optional) {
        this.optional = optional;
    }

    public boolean isMediaField() {
        return kind == FieldKind.MEDIA
                || "P18".equals(propertyPid)
                || "P242".equals(propertyPid);
    }

    public boolean isEntityField() {
        return kind == FieldKind.ENTITY;
    }

    public String displayName() {
        String label = wikidata.LabelledId.display(propertyLabel, propertyPid);
        return fieldName + " ← " + label;
    }

    @Override
    public String toString() {
        return displayName();
    }

    private static String cleanPid(String pid) {
        if (pid == null) {
            return "";
        }
        pid = pid.trim();
        if (pid.startsWith("wdt:")) {
            pid = pid.substring(4);
        }
        if (pid.startsWith("wd:")) {
            pid = pid.substring(3);
        }
        return pid.trim();
    }
}
