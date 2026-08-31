package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Maps Wikidata evidence (normally P31 values) to one modeled entity kind. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class EntityKindRule {
    private String className = "";
    private String classId = "";
    private String propertyPid = "P31";
    private final List<String> evidenceQids = new ArrayList<>();

    public EntityKindRule() {}
    public EntityKindRule(String className, List<String> evidenceQids) {
        className(className);
        evidenceQids(evidenceQids);
    }

    public String className() { return className; }
    public void className(String value) {
        className = clean(value);
        classId = "";
    }
    public String classId() { return DeclarationIds.clean(classId); }
    public void classId(String value) { classId = DeclarationIds.clean(value); }
    void classReference(String id, String name) {
        classId = DeclarationIds.clean(id);
        className = clean(name);
    }
    public String propertyPid() { return propertyPid; }
    public void propertyPid(String value) {
        String cleaned = clean(value);
        propertyPid = cleaned.isBlank() ? "P31" : cleaned;
    }
    public List<String> evidenceQids() { return evidenceQids; }
    public void evidenceQids(List<String> values) {
        evidenceQids.clear();
        if (values != null) values.stream().filter(wikidata.WikidataIds::isQid)
                .forEach(evidenceQids::add);
    }
    public boolean isConfigured() {
        return !className.isBlank() && wikidata.WikidataIds.isPid(propertyPid)
                && !evidenceQids.isEmpty();
    }

    /** Whether two rules address the same modeled kind and evidence property. */
    public boolean sameTarget(EntityKindRule other) {
        if (other == null || !propertyPid().equals(other.propertyPid())) return false;
        boolean bothIdentified = !classId().isBlank() && !other.classId().isBlank();
        return bothIdentified
                ? classId().equals(other.classId())
                : className().equalsIgnoreCase(other.className());
    }

    public EntityKindRule copy() {
        EntityKindRule copy = new EntityKindRule(className, evidenceQids);
        copy.classId = classId;
        copy.propertyPid(propertyPid);
        return copy;
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
