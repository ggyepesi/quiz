package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A named selection of canonical entities reached through an owning class's field. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RoleSelection extends Selection {

    private String ownerClassName = "";
    private String ownerClassId = "";
    private String fieldName = "";

    public RoleSelection() {
        super();
        kind(Kind.ROLE);
    }

    public RoleSelection(String name, String ownerClassName, String fieldName) {
        super(name, Kind.ROLE);
        ownerClassName(ownerClassName);
        fieldName(fieldName);
    }

    public String ownerClassName() { return ownerClassName; }
    public void ownerClassName(String value) {
        ownerClassName = value == null ? "" : value.trim();
        ownerClassId = "";
    }
    public String ownerClassId() { return DeclarationIds.clean(ownerClassId); }
    public void ownerClassId(String value) { ownerClassId = DeclarationIds.clean(value); }
    void ownerReference(String id, String name) {
        ownerClassId = DeclarationIds.clean(id);
        ownerClassName = name == null ? "" : name.trim();
    }

    public String fieldName() { return fieldName; }
    public void fieldName(String value) {
        fieldName = value == null ? "" : value.trim();
    }

    /** Stable selection identity. The label alone is not unique: two fields may
     * target the same class (Nomination.nominee and .director -> Person). */
    public String key() {
        return name() + " [" + ownerClassName + "." + fieldName + "]";
    }

    @Override public boolean isConfigured() {
        return !name().isBlank() && !ownerClassName.isBlank() && !fieldName.isBlank();
    }

    @Override public RoleSelection copy() {
        RoleSelection copy = new RoleSelection(name(), ownerClassName, fieldName);
        copyIdentityTo(copy);
        copy.ownerClassId = ownerClassId;
        return copy;
    }
}
