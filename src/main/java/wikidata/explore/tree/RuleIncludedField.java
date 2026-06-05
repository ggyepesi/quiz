package wikidata.explore.tree;

public class RuleIncludedField {

    public enum FieldKind {
        AUTO,
        MEDIA,
        ENTITY
    }

    private String fieldName;
    private String propertyPid;
    private String propertyLabel;
    private FieldKind kind = FieldKind.AUTO;
    private boolean optional = true;
    private boolean collection = false;

    public boolean collection() {
        return collection;
    }

    public void collection(boolean collection) {
        this.collection = collection;
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
        String label = propertyLabel == null || propertyLabel.isBlank()
                ? propertyPid
                : propertyLabel + " (" + propertyPid + ")";
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
