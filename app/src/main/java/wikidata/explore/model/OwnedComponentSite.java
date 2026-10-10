package wikidata.explore.model;

/** Stable runtime description of the field that produced an owned component. */
public record OwnedComponentSite(
        String targetClass, String ownerClass, String ownerField) {

    public OwnedComponentSite {
        targetClass = clean(targetClass);
        ownerClass = clean(ownerClass);
        ownerField = clean(ownerField);
    }

    public boolean configured() {
        return !targetClass.isBlank() && !ownerClass.isBlank() && !ownerField.isBlank();
    }

    public String key() {
        return targetClass + "@" + ownerClass + "." + ownerField;
    }

    public static OwnedComponentSite parse(String key) {
        String value = clean(key);
        int at = value.indexOf('@');
        int dot = value.indexOf('.', at + 1);
        if (at <= 0 || dot <= at + 1 || dot == value.length() - 1) return null;
        OwnedComponentSite site = new OwnedComponentSite(
                value.substring(0, at), value.substring(at + 1, dot),
                value.substring(dot + 1));
        return site.configured() ? site : null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
