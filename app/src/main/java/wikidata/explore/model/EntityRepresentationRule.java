package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Explicitly lets one role class use a class whose admission evidence matches. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class EntityRepresentationRule {
    private String roleClassName = "";
    private String roleClassId = "";
    private String representationClassName = "";
    private String representationClassId = "";

    public EntityRepresentationRule() { }

    public EntityRepresentationRule(String roleClassName, String representationClassName) {
        roleClassName(roleClassName);
        representationClassName(representationClassName);
    }

    public String roleClassName() { return clean(roleClassName); }
    public void roleClassName(String value) { roleClassName = clean(value); roleClassId = ""; }
    public String roleClassId() { return DeclarationIds.clean(roleClassId); }
    public void roleClassId(String value) { roleClassId = DeclarationIds.clean(value); }
    void roleClassReference(String id, String name) {
        roleClassId = DeclarationIds.clean(id);
        roleClassName = clean(name);
    }

    public String representationClassName() { return clean(representationClassName); }
    public void representationClassName(String value) {
        representationClassName = clean(value);
        representationClassId = "";
    }
    public String representationClassId() { return DeclarationIds.clean(representationClassId); }
    public void representationClassId(String value) {
        representationClassId = DeclarationIds.clean(value);
    }
    void representationClassReference(String id, String name) {
        representationClassId = DeclarationIds.clean(id);
        representationClassName = clean(name);
    }

    public boolean isConfigured() {
        return !roleClassName().isBlank() && !representationClassName().isBlank();
    }

    public EntityRepresentationRule copy() {
        EntityRepresentationRule copy = new EntityRepresentationRule(
                roleClassName, representationClassName);
        copy.roleClassId = roleClassId;
        copy.representationClassId = representationClassId;
        return copy;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
