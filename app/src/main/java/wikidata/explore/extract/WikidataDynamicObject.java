package wikidata.explore.extract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import objectview.annotations.Hidden;
import objectview.field.DynamicFields;
import objectview.field.FieldSchema;
import quiz.source.SourceViewable;
import quiz.source.WikidataViewable;

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
        implements DynamicFields, quiz.source.Anchorable {

    // Stable identity of this carrier — NOT tied to any source. Usually a QID
    // today, but the carrier is source-neutral: provenance and the external
    // anchor live in the `source` descriptor, which can be re-anchored without
    // moving identity (so hashCode stays valid under pooling).
    @Hidden
    private String identifier = "";
    @Hidden
    private String name = "";

    // The provenance descriptor (Wikidata / manual / statement / …). Swapping it
    // re-anchors the object; it never changes identity. Rendered as a chip. Named
    // `anchor`, not `source`, to avoid colliding with the reify back-reference
    // field and structural schema fields that are already named `source`.
    @objectview.annotations.Provenance
    @JsonIgnore
    private SourceViewable anchor;

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
        // Historically this carrier was always a Wikidata entity; preserve that
        // by anchoring a Wikidata source when the id is a QID. A non-QID id (a
        // manual key) simply has no source yet — it can be re-anchored later.
        anchorWikidataIfQid();
    }

    /** Seeds/refreshes a Wikidata anchor when the identity is QID-shaped. */
    private void anchorWikidataIfQid() {
        if (identifier != null && identifier.matches("Q\\d+")) {
            anchor = new WikidataViewable(identifier, name);
        }
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

    @Override public SourceViewable anchor() { return anchor; }

    @Override public void anchor(SourceViewable anchor) { this.anchor = anchor; }

    /** The Wikidata QID iff this object's anchor is Wikidata; else "". */
    public String qid() {
        return anchor instanceof WikidataViewable w ? w.qid() : "";
    }

    public String wikidataUrl() {
        return anchor instanceof WikidataViewable w ? w.wikidataUrl() : "";
    }

    public String getQid() { return qid(); }

    public String getUrl() { return wikidataUrl(); }

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

    @Override public String getReferenceLabel() {
        if (referenceLabel != null && !referenceLabel.isBlank()) return referenceLabel;
        return getName();
    }

    public void referenceLabel(String referenceLabel) {
        this.referenceLabel = referenceLabel == null ? "" : referenceLabel;
    }

    public String displayLabel() { return getDisplayName(); }

    public void qid(String qid) {
        String id = normalizeQid(qid);
        this.identifier = id == null ? "" : id;
        anchorWikidataIfQid();
    }

    public void name(String name) {
        this.name = name == null || name.isBlank() ? identifier : name;
        anchorWikidataIfQid();
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
        return name + (identifier == null || identifier.isBlank()
                ? "" : " (" + identifier + ")");
    }
}
