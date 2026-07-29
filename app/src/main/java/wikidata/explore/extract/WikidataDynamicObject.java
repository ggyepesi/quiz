package wikidata.explore.extract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import objectview.annotations.Hidden;
import objectview.annotations.Link;
import objectview.annotations.Provenance;
import objectview.field.DynamicFields;
import objectview.ViewableAdapter;
import quiz.source.Source;
import quiz.source.WikidataSource;

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
// Tolerate extra fields on read: derived getters (getUrl→"url", getIdentifier,
// getDisplayName, …) get serialized but aren't settable, so an older/foreign
// JSON (e.g. a saved OscarNomination cache) carries "url" that we must skip
// rather than fail on. Only "name"/"qid" round-trip.
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class WikidataDynamicObject extends ViewableAdapter implements DynamicFields {
    // Identity + provenance. Hidden from the card (@Hidden) because
    // they're surfaced together as one collapsed "source" chip below — the raw
    // QID and wiki URL no longer clutter every card's top level. The QID is
    // still the canonical key (equals/hashCode, snapshots, web serving); these
    // annotations only affect Viewable rendering, not Jackson persistence.
    @Hidden
    private String qid;
    // Identity/display name — the card TITLE, not a field row. Like qid it is
    // re-injected once as an identity field by getConfigurableFields; without
    // @Hidden it also leaks into getAllFields, so `name` showed up TWICE
    // in sort/search/viewconfig (and as a redundant field row).
    @Hidden
    private String name;

    @Hidden
    @Link
    private String wikidataUrl;

    // The dynamic data. NOTE: NOT @Hidden — Card renders this
    // map's entries AS the object's fields (see isDynamicContainer there); hiding
    // it would hide all the data. Its unwanted "dynamicFields" titled border is
    // dropped in Card instead.
    private final Map<String, Object> dynamicFields =
            new LinkedHashMap<>();

    // Web/runtime only (not persisted): the domain type this object is served
    // under, since all generated objects share this one Java class. @Hidden
    // so the class STAMP doesn't render as a `type` field (distinct from the P31
    // `type` data field a domain may declare).
    @Hidden
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String type;

    // The OBJECT-identity type key ⟨typeKey, qid⟩ — the stable logical class name. It
    // distinguishes objects that merely share a name across types (a State "France" vs a
    // ViewableGroup "France"); the QID stays an entity LINK, not the object identity.
    // Falls back to typeName() when unset.
    @Hidden
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String typeKey;

    // Runtime marker (not a persisted field — implied by the inline representation):
    // this object is a VALUE inlined in its parent, not a pooled, qid-keyed entity.
    @Hidden
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean valueObject;

    // A pooled structural object (currently an authored ViewableGroup): references
    // can point to it and its fields can render, but it is not a selectable domain
    // member type merely because it is reachable from a member.
    @Hidden
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean structuralObject;

    // Hidden ancestry metadata for an authored group. It is persisted by
    // WikidataDynamicObjectJsonStore outside dynamicFields so cards show the
    // structural parent/children/members fields, not a `path (n)` implementation
    // detail.
    @Hidden
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<String> structuralPath = List.of();

    // Provenance grouped as one nested Viewable: renders as a collapsed
    // "source: Wikidata" chip that expands to the QID + link. Declared LAST so
    // it renders as an unobtrusive footer below the real fields (reflection
    // preserves declaration order). Derived from the QID, so it is rebuilt by
    // the constructor / qid setter and never persisted (the snapshot store
    // rebuilds objects through the constructor). @Provenance drives both: render
    // as a collapsed chip (Card) and exclude from entity-type grouping
    // (QueryObjectResultPanel).
    @Provenance
    @JsonIgnore
    private transient Source source;

    // Reify provenance: per-field origin (qualifier vs subject-fallback vs
    // missing), recorded by the reify step and read by later passes to detect
    // degenerate/self-referential atoms generically. Sidecar metadata only —
    // @Hidden + @JsonIgnore + transient keep it out of rendering, field listing,
    // and serialization (it is never a data field).
    @Hidden
    @JsonIgnore
    private transient Map<String, FieldOrigin> fieldOrigins;

    public WikidataDynamicObject() {
        this("", "");
    }

    public WikidataDynamicObject(String qid, String name) {
        this.qid = qid == null ? "" : qid;
        this.name = name == null || name.isBlank() ? this.qid : name;
        // Only a real entity QID (Q123) gets a Wikidata link/source. A synthetic
        // object keyed by a statement GUID (Q123-<guid>, e.g. a reified Nomination)
        // is NOT a Wikidata page — a link built from its key 404s.
        this.wikidataUrl = this.qid.matches("Q\\d+")
                ? "https://www.wikidata.org/wiki/" + this.qid
                : "";
        rebuildSource();
    }

    // (Re)builds the grouped provenance from the current QID. Null unless the QID
    // is a real entity (so a blank shell or a statement-keyed synthetic renders no
    // source chip / link).
    private void rebuildSource() {
        this.source = wikidataUrl == null || wikidataUrl.isBlank()
                ? null
                : new WikidataSource(qid, wikidataUrl);
    }

    public Source source() {
        return source;
    }

    // One interned instance per QID, replacing the legacy
    // WikidataEntity.canonical. Seeds a wikidata link so a bare reference's
    // card isn't empty (the DynamicFields renderer skips declared fields).
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

    /** Alias for {@link #qid()} (legacy WikidataEntity API). Not a Jackson
     *  property — the snapshot mapper is field-only, so this getter is ignored
     *  and the qid field round-trips. (An @JsonIgnore here would wrongly ignore
     *  the field too.) */
    public String getQid() {
        return qid;
    }

    /** Alias for {@link #wikidataUrl()} (legacy WikidataEntity API). */
    public String getUrl() {
        return wikidataUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WikidataDynamicObject w)) {
            return false;
        }
        return qid != null && !qid.isBlank()
                && qid.equals(w.qid)
                && java.util.Objects.equals(typeKey(), w.typeKey());
    }

    @Override
    public int hashCode() {
        return qid == null || qid.isBlank()
                ? System.identityHashCode(this)
                : java.util.Objects.hash(typeKey(), qid);
    }

    @Override
    public String getIdentifier() { return qid; }

    @Override
    public String getDisplayName() { return name == null || name.isBlank() ? qid : name; }

    public String displayLabel() { return getDisplayName(); }

    public String qid() {
        return qid;
    }

    public void qid(String qid) {
        this.qid = normalizeQid(qid);
        this.wikidataUrl = this.qid.isBlank()
                ? ""
                : "https://www.wikidata.org/wiki/" + this.qid;
        rebuildSource();
    }

    public void name(String name) {
        this.name = name == null ? "" : name;
    }

    public String wikidataUrl() {
        return wikidataUrl;
    }

    public Map<String, Object> dynamicFields() {
        return dynamicFields;
    }

    @Override
    public Map<String, Object> dynamicFieldValues() {
        return dynamicFields;
    }

    public void type(String type) {
        this.type = type;
    }

    /** The object-identity type key: the explicit key if set, else the logical type name
     *  (a generated class's name is already unique within its model). */
    public String typeKey() {
        return typeKey != null && !typeKey.isBlank() ? typeKey : typeName();
    }

    public void typeKey(String typeKey) {
        this.typeKey = typeKey;
    }

    /** A VALUE object: inlined in its parent, not pooled/keyed by qid. */
    public boolean isValueObject() {
        return valueObject;
    }

    public void valueObject(boolean valueObject) {
        this.valueObject = valueObject;
    }

    public boolean isStructuralObject() {
        return structuralObject;
    }

    public void structuralObject(boolean structuralObject) {
        this.structuralObject = structuralObject;
    }

    public List<String> structuralPath() {
        return structuralPath;
    }

    public void structuralPath(java.util.Collection<?> path) {
        if (path == null || path.isEmpty()) {
            structuralPath = List.of();
            return;
        }
        List<String> copy = new ArrayList<>();
        for (Object segment : path) {
            if (segment != null && !segment.toString().isBlank()) {
                copy.add(segment.toString());
            }
        }
        structuralPath = List.copyOf(copy);
    }

    /** True when a domain class was stamped ({@link #typeName()} would otherwise
     *  fall back to the Java class name — an unstamped reference, not a member). */
    public boolean hasTypeStamp() {
        return type != null && !type.isBlank();
    }

    @Override
    public String typeName() {
        return type == null || type.isBlank() ? getClass().getSimpleName() : type;
    }

    public Object get(String fieldName) {
        return dynamicFields.get(fieldName);
    }

    /** Removes a field entirely (put ignores nulls, so this is the way to clear). */
    public void remove(String fieldName) {
        if (fieldName != null) {
            dynamicFields.remove(fieldName);
        }
    }

    public void put(String fieldName, Object value) {
        if (fieldName == null || fieldName.isBlank() || value == null) {
            return;
        }
        dynamicFields.put(fieldName, value);
    }

    /** Records where a field's value came from during reification (sidecar
     *  provenance, not a data field). See {@link FieldOrigin}. */
    public void recordOrigin(String fieldName, FieldOrigin origin) {
        if (fieldName == null || fieldName.isBlank() || origin == null) {
            return;
        }
        if (fieldOrigins == null) {
            fieldOrigins = new java.util.HashMap<>();
        }
        fieldOrigins.put(fieldName, origin);
    }

    /** The recorded origin of a field, or {@code null} if none was recorded. */
    public FieldOrigin origin(String fieldName) {
        return fieldOrigins == null ? null : fieldOrigins.get(fieldName);
    }

    /** All recorded field origins (empty if none). */
    public Map<String, FieldOrigin> fieldOrigins() {
        return fieldOrigins == null ? Map.of() : fieldOrigins;
    }

    public void merge(String fieldName, Object value) {
        if (fieldName == null || fieldName.isBlank() || value == null) {
            return;
        }

        Object existing = dynamicFields.get(fieldName);

        if (existing == null) {
            dynamicFields.put(fieldName, value);
            return;
        }

        if (sameValue(existing, value)) return;

        if (existing instanceof List<?> existingList) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) existingList;
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
        for (Object item : list) {
            if (sameValue(item, value)) return;
        }
        list.add(value);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        if (a instanceof WikidataDynamicObject wa
                && b instanceof WikidataDynamicObject wb) {
            return safe(wa.qid()).equals(safe(wb.qid()));
        }

        if (a instanceof WikidataMediaValue ma
                && b instanceof WikidataMediaValue mb) {
            return safe(ma.url()).equals(safe(mb.url()))
                    && safe(ma.label()).equals(safe(mb.label()));
        }

        return a.equals(b);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String toString() {
        return name + (qid == null || qid.isBlank() ? "" : " (" + qid + ")");
    }

}
