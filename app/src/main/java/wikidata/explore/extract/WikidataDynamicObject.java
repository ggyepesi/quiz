package wikidata.explore.extract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import objectview.annotations.Hidden;
import objectview.field.DynamicFields;
import objectview.field.FieldSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic Wikidata-backed object.
 *
 * Core semantic rule:
 *   one ⟨logical type, identifier⟩ -> one WikidataDynamicObject
 *
 * Repeated field values are merged:
 *   0 values  -> absent
 *   1 value   -> scalar
 *   2+ values -> List
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class WikidataDynamicObject extends objectview.ViewableAdapter
        implements DynamicFields {

    // The stable logical identity — assigned at creation (a qid for a Wikidata
    // entity, a local key for a manual instance). The instance holds only results;
    // where it came from (its source) is curation history, never a field here.
    @Hidden
    private String identifier = "";
    @Hidden
    private String name = "";

    @Hidden
    @JsonIgnore
    private String referenceLabel;

    private final Map<String, Object> dynamicFields = new LinkedHashMap<>();

    @Hidden
    @JsonIgnore
    private String type;

    @Hidden
    @JsonIgnore
    private final java.util.Set<String> directClasses = new java.util.LinkedHashSet<>();

    @Hidden
    @JsonIgnore
    private String typeKey;

    @Hidden
    @JsonIgnore
    private boolean valueObject;

    @Hidden
    @JsonIgnore
    private transient FieldSchema dynamicFieldSchema;

    @Hidden
    @JsonIgnore
    private transient Map<String, FieldOrigin> fieldOrigins;

    public WikidataDynamicObject() { }

    public WikidataDynamicObject(String qid, String name) {
        String id = normalizeQid(qid);
        this.identifier = id == null ? "" : id;
        this.name = name == null || name.isBlank() ? this.identifier : name;
    }

    private static final Map<String, WikidataDynamicObject> CACHE =
            new ConcurrentHashMap<>();

    public static WikidataDynamicObject canonical(String name, String qid) {
        if (qid == null || qid.isBlank()) {
            throw new IllegalArgumentException("null/blank id");
        }
        return CACHE.computeIfAbsent(qid, k -> {
            WikidataDynamicObject o = new WikidataDynamicObject(k, name);
            o.put("wikidata", o.wikidataUrl());
            return o;
        });
    }

    @Override public String getIdentifier() {
        return identifier == null || identifier.isBlank() ? name : identifier;
    }

    @Override public String getDisplayName() {
        return name == null || name.isBlank() ? identifier : name;
    }

    // Identity is the stable `identifier`, never the source, so value-equality is safe.
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WikidataDynamicObject w)) return false;
        return identifier != null && !identifier.isBlank()
                && identifier.equals(w.identifier)
                && java.util.Objects.equals(typeKey(), w.typeKey());
    }

    @Override public int hashCode() {
        return identifier == null || identifier.isBlank()
                ? System.identityHashCode(this)
                : java.util.Objects.hash(typeKey(), identifier);
    }

    /** The Wikidata QID when this object's stable identity IS a QID; else "".
     *  For a Wikidata entity the identity and the source key coincide, so the qid
     *  is read straight from identity — it is never a stored source descriptor. */
    public String qid() {
        return identifier != null && identifier.matches("Q\\d+") ? identifier : "";
    }

    public String wikidataUrl() {
        String qid = qid();
        return qid.isEmpty() ? "" : "https://www.wikidata.org/wiki/" + qid;
    }

    public String getQid() { return qid(); }

    public String getUrl() { return wikidataUrl(); }

    @Override public String getReferenceLabel() {
        if (referenceLabel != null && !referenceLabel.isBlank()) return referenceLabel;
        return getName();
    }

    public void referenceLabel(String referenceLabel) {
        this.referenceLabel = referenceLabel == null ? "" : referenceLabel;
    }

    public String displayLabel() { return getDisplayName(); }

    public void name(String name) {
        // Presentation only. Identity (the qid) is stable and set at construction.
        this.name = name == null || name.isBlank() ? identifier : name;
    }

    public Map<String, Object> dynamicFields() { return dynamicFields; }

    @Override public Map<String, Object> dynamicFieldValues() { return dynamicFields; }

    @Override public FieldSchema dynamicFieldSchema() { return dynamicFieldSchema; }

    public void dynamicFieldSchema(FieldSchema schema) { this.dynamicFieldSchema = schema; }

    public void type(String type) {
        this.type = type;
        if (type != null && !type.isBlank()) directClasses.add(type);
    }

    public void assignClass(String className) {
        if (className != null && !className.isBlank()) {
            directClasses.add(className);
            if (type == null || type.isBlank()) type = className;
        }
    }

    @Override public void absorbClasses(
            objectview.Viewable source,
            java.util.function.Function<String, String> baseType) {
        if (source == null) return;
        source.directClassNames().forEach(this::assignClass);

        String stableBase = identityTypeName();
        String concrete = !java.util.Objects.equals(typeName(), stableBase)
                ? typeName()
                : java.util.Objects.equals(source.identityTypeName(), stableBase)
                        && !java.util.Objects.equals(source.typeName(), stableBase)
                        ? source.typeName() : null;
        if (concrete != null && directClasses.contains(concrete)) {
            directClasses.remove(stableBase);
            type = concrete;
        }
        if (baseType == null || directClasses.size() < 2) return;

        java.util.LinkedHashSet<String> inherited = new java.util.LinkedHashSet<>();
        for (String candidate : directClasses) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String ancestor = baseType.apply(candidate);
                 ancestor != null && seen.add(ancestor);
                 ancestor = baseType.apply(ancestor)) {
                if (directClasses.contains(ancestor)) inherited.add(ancestor);
            }
        }
        directClasses.removeAll(inherited);
        String deepest = null;
        int deepestDepth = -1;
        for (String candidate : directClasses) {
            int depth = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String current = candidate; current != null && seen.add(current);
                 current = baseType.apply(current)) depth++;
            if (depth > deepestDepth) {
                deepest = candidate;
                deepestDepth = depth;
            }
        }
        if (deepest != null) type = deepest;
    }

    public void assignSubclass(String className, String baseClassName) {
        if ((typeKey == null || typeKey.isBlank()) && type != null && !type.isBlank()) {
            typeKey = type;
        }
        if (baseClassName != null) directClasses.remove(baseClassName);
        assignClass(className);
        if (className != null && !className.isBlank()) type = className;
    }

    public void directClasses(java.util.Collection<String> classNames) {
        directClasses.clear();
        if (classNames != null) {
            classNames.stream().filter(java.util.Objects::nonNull)
                    .map(String::trim).filter(name -> !name.isBlank())
                    .forEach(directClasses::add);
        }
        if (directClasses.isEmpty() && type != null && !type.isBlank()) {
            directClasses.add(type);
        }
    }

    @Override public java.util.Set<String> directClassNames() {
        if (!directClasses.isEmpty()) return java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(directClasses));
        String fallback = typeName();
        return fallback == null || fallback.isBlank()
                ? java.util.Set.of() : java.util.Set.of(fallback);
    }

    public String typeKey() {
        return typeKey != null && !typeKey.isBlank() ? typeKey : typeName();
    }

    @Override public String identityTypeName() { return typeKey(); }

    public void typeKey(String typeKey) { this.typeKey = typeKey; }

    public boolean isValueObject() { return valueObject; }

    public void valueObject(boolean valueObject) { this.valueObject = valueObject; }

    public boolean hasTypeStamp() { return type != null && !type.isBlank(); }

    @Override public String typeName() {
        return type == null || type.isBlank() ? getClass().getSimpleName() : type;
    }

    public Object get(String fieldName) { return dynamicFields.get(fieldName); }

    public void remove(String fieldName) {
        if (fieldName != null) dynamicFields.remove(fieldName);
    }

    public void put(String fieldName, Object value) {
        if (fieldName == null || fieldName.isBlank() || value == null) return;
        dynamicFields.put(fieldName, value);
    }

    public void recordOrigin(String fieldName, FieldOrigin origin) {
        if (fieldName == null || fieldName.isBlank() || origin == null) return;
        if (fieldOrigins == null) fieldOrigins = new java.util.HashMap<>();
        fieldOrigins.put(fieldName, origin);
    }

    public FieldOrigin origin(String fieldName) {
        return fieldOrigins == null ? null : fieldOrigins.get(fieldName);
    }

    public Map<String, FieldOrigin> fieldOrigins() {
        return fieldOrigins == null ? Map.of() : fieldOrigins;
    }

    public void merge(String fieldName, Object value) {
        if (fieldName == null || fieldName.isBlank() || value == null) return;
        Object existing = dynamicFields.get(fieldName);
        if (existing == null) {
            dynamicFields.put(fieldName, value);
            return;
        }
        if (sameValue(existing, value)) return;
        if (existing instanceof List<?> existingList) {
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) existingList;
            addIfMissing(list, value);
            return;
        }
        List<Object> list = new ArrayList<>();
        list.add(existing);
        addIfMissing(list, value);
        dynamicFields.put(fieldName, list);
    }

    private static String normalizeQid(String qid) {
        return qid == null ? null : qid.strip().trim();
    }

    private static void addIfMissing(List<Object> list, Object value) {
        for (Object item : list) if (sameValue(item, value)) return;
        list.add(value);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof WikidataDynamicObject wa
                && b instanceof WikidataDynamicObject wb) {
            return safe(wa.getIdentifier()).equals(safe(wb.getIdentifier()));
        }
        if (a instanceof WikidataMediaValue ma
                && b instanceof WikidataMediaValue mb) {
            return safe(ma.url()).equals(safe(mb.url()))
                    && safe(ma.label()).equals(safe(mb.label()));
        }
        return a.equals(b);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    @Override public String toString() {
        String id = getIdentifier();
        return name + (id == null || id.isBlank() ? "" : " (" + id + ")");
    }
}
