package wikidata.explore.codegen;

import wikidata.explore.extract.WikidataMediaValue;
import wikidata.explore.extract.WikidataDynamicObject;
import quiz.Quizable;
import objectview.ImagePane;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.Canonicalizer;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class GeneratedQuizableMapper {
    private final GeneratedQuizableRuntime runtime;
    private final Map<WikidataDynamicObject, Object> generatedByDynamic =
            new IdentityHashMap<>();
    // Real entities (Q\d+) are identified by QID: the same entity arriving as
    // several WDO copies (a root + inline-field references) maps to ONE typed
    // instance (see mapObject). Statement atoms (Q\d+-<guid>) keep per-atom identity.
    private final Map<String, Object> generatedByQid = new java.util.HashMap<>();

    public GeneratedQuizableMapper(GeneratedQuizableRuntime runtime) {
        this.runtime = runtime;
    }

    public List<Quizable> mapRoots(List<WikidataDynamicObject> roots) throws Exception {
        List<Quizable> out = new ArrayList<>();
        if (roots == null) return out;
        // Distinct instances: two roots that are the same entity (same qid, e.g. a
        // work + its inline-reference copy) map to ONE instance — add it once.
        java.util.Set<Object> added =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (WikidataDynamicObject root : roots) {
            Object mapped = mapObject(root);
            if (mapped instanceof Quizable q && added.add(q)) {
                out.add(q);
            }
        }
        return out;
    }

    private Object mapObject(WikidataDynamicObject source) throws Exception {
        return mapObject(source, null);
    }

    private Object mapObject(WikidataDynamicObject source, String preferredType)
            throws Exception {
        if (source == null) return null;

        boolean typedRequested = preferredType != null && !preferredType.isBlank()
                && runtime.forType(preferredType) != null;

        Object existing = generatedByDynamic.get(source);
        if (existing != null
                && (!typedRequested || !(existing instanceof WikidataDynamicObject))) {
            return existing;
        }
        // If we reach here with a cached entry, it's a RAW bare-reference object
        // (the source's own type has no generated class, so an earlier untyped map
        // — e.g. a root pass — cached it raw), but now a typed reference needs the
        // declared class. Re-map to that class and replace the cache, so the typed
        // field gets a typed instance instead of dropping it on the type-mismatch
        // (this is what made P2453-only nominees like a co-writer never render).

        // Choose the target class: prefer the referencing field's declared class
        // (its "Of class") over the object's stamped typeName, so a reference
        // whose target wasn't stamped (typeName "WikidataDynamicObject") still
        // maps to the typed class the field declares — keeping cross-references
        // typed instead of raw. Falls back to the stamped type for roots.
        String type = typedRequested ? preferredType : source.typeName();

        // Map each object to its generated class (e.g. a constellation's child
        // stars -> Star). An object whose type has no generated class (a true
        // bare leaf reference) is kept as-is (renders as a link).
        GeneratedQuizableRuntime.ClassRuntime cr = runtime.forType(type);
        if (cr == null) {
            generatedByDynamic.put(source, source);
            return source;
        }

        // QID identity for real entities: the same entity arriving as several WDO
        // copies (a root + inline-field references) maps to ONE typed instance —
        // merging any fields this copy adds. This is what collapses the same
        // category / nominee referenced from many places instead of duplicating it.
        String entityQid = source.qid();
        boolean realEntity = entityQid != null && entityQid.matches("Q\\d+");
        if (realEntity) {
            Object byQid = generatedByQid.get(entityQid);
            if (byQid != null && !(byQid instanceof WikidataDynamicObject)) {
                generatedByDynamic.put(source, byQid);
                applyFields(cr, byQid, source, true);   // fill any missing fields
                return byQid;
            }
        }

        Object target = cr.generatedClass().getDeclaredConstructor().newInstance();
        generatedByDynamic.put(source, target);
        if (realEntity) {
            generatedByQid.put(entityQid, target);
        }

        setIfExists(target, "qid", source.qid());
        setIfExists(target, "wikidataUrl", source.wikidataUrl());
        setIfExists(target, "name", source.getDisplayName());
        // Group QID/URL into the collapsed provenance chip, same as the dynamic
        // object — so typed roots and dynamic references render alike. Only a REAL
        // entity QID (Q123) gets a source chip; a synthetic keyed by a statement
        // GUID (Q123-<guid>, e.g. a reified Nomination) isn't a Wikidata page, so a
        // chip would just show the GUID + an empty url (mirrors the guard in
        // WikidataDynamicObject, which this path bypasses by building its own Source).
        if (source.qid() != null && source.qid().matches("Q\\d+")) {
            setIfExists(target, "source",
                    new quiz.source.WikidataSource(source.qid(), source.wikidataUrl()));
        }

        applyFields(cr, target, source, false);

        // Canonicalization: a DERIVED class's displayName comes from its spec (a
        // field or template), not the loaded label — so reified atoms show e.g. the
        // nominee, never the poisoned/loaded name. Only an EXPLICIT spec applies,
        // so legacy models are unchanged until reconfigured. Reads the mapped target
        // value (a proper Quizable), falling back to the raw source value.
        final Object mappedTarget = target;
        Canonicalizer.FieldReader reader = fieldName -> {
            Field jf = findField(cr.generatedClass(),
                    GeneratedQuizableSourceGenerator.sanitizeFieldName(fieldName));
            if (jf != null) {
                try {
                    jf.setAccessible(true);
                    Object v = jf.get(mappedTarget);
                    if (v != null) {
                        return v;
                    }
                } catch (IllegalAccessException ignored) {
                    // fall back to the source value
                }
            }
            return source.get(fieldName);
        };
        String override = canonicalDisplayNameOverride(
                cr.model(), reader, source.getDisplayName());
        if (override != null) {
            setIfExists(target, "name", override);
        }

        return target;
    }

    /**
     * Copies {@code source}'s field values into {@code target} per the class model.
     * With {@code onlyIfNull} it fills only fields not yet set — used to MERGE a
     * second WDO copy of the same entity into its existing typed instance without
     * clobbering already-mapped values.
     */
    private void applyFields(GeneratedQuizableRuntime.ClassRuntime cr, Object target,
                             WikidataDynamicObject source, boolean onlyIfNull)
            throws Exception {
        for (GeneratedFieldModel fieldModel : cr.model().fields()) {
            if (fieldModel == null || fieldModel.isNameField()) continue;

            String targetFieldName =
                    GeneratedQuizableSourceGenerator.sanitizeFieldName(fieldModel.name());
            Field javaField = findField(cr.generatedClass(), targetFieldName);
            if (javaField == null) continue;
            javaField.setAccessible(true);

            boolean collection =
                    fieldModel.cardinality() == FieldCardinality.COLLECTION;
            Object existing = javaField.get(target);

            // On MERGE (onlyIfNull), a scalar that's already set stays. A
            // collection, however, is UNIONED: the same entity arrives as several
            // WDO copies, each carrying only its own subset (e.g. one category per
            // nomination), so first-wins would drop the rest of the targets.
            if (onlyIfNull && existing != null && !collection) continue;

            Object raw = source.get(fieldModel.name());
            if (raw == null) continue;

            Object mapped = mapFieldValue(fieldModel, raw);
            if (mapped == null) continue;

            if (onlyIfNull && collection
                    && existing instanceof List<?> && mapped instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Object> existingList = (List<Object>) existing;
                for (Object item : (List<?>) mapped) {
                    if (!existingList.contains(item)) existingList.add(item);
                }
                continue;
            }

            try {
                javaField.set(target, mapped);
            } catch (IllegalArgumentException typeMismatch) {
                // The extracted value doesn't fit the generated field's type
                // (e.g. an "unknown value"/genid for an entity field that
                // came through as text). Skip it rather than abort the run.
            }
        }
    }

    /** The displayName to force onto a materialized object from its class's
     *  {@link CanonicalSpec}, or null to leave the default (the loaded label). Only
     *  an EXPLICIT spec on a DERIVED class with a FIELD/TEMPLATE displayName
     *  overrides; entities and legacy (spec-less) models are untouched. */
    static String canonicalDisplayNameOverride(
            GeneratedClassModel model,
            Canonicalizer.FieldReader reader,
            String fallbackLabel) {

        if (model == null || !model.hasCanonical()) {
            return null;
        }
        CanonicalSpec spec = model.canonical();
        if (!spec.isDerived()
                || spec.displayNameMode() == CanonicalSpec.DisplayNameMode.LABEL) {
            return null;
        }
        String dn = Canonicalizer.displayName(spec, reader, fallbackLabel);
        return dn == null || dn.isBlank() ? null : dn;
    }

    private Object mapFieldValue(GeneratedFieldModel fieldModel, Object raw) throws Exception {
        if (fieldModel.cardinality() == FieldCardinality.COLLECTION) {
            List<Object> list = new ArrayList<>();
            if (raw instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    Object mapped = mapSingleValue(fieldModel, item);
                    if (mapped != null) list.add(mapped);
                }
            } else {
                Object mapped = mapSingleValue(fieldModel, raw);
                if (mapped != null) list.add(mapped);
            }
            return list;
        }

        // Single-valued field, but Wikidata can return several values for a
        // property (e.g. a constellation "named after" multiple figures), which
        // the extractor merges into a List. The generated field holds one
        // value, so map the first mappable element and drop the rest rather
        // than crashing on a List -> scalar assignment.
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Object mapped = mapSingleValue(fieldModel, item);
                if (mapped != null) {
                    return mapped;
                }
            }
            return null;
        }
        return mapSingleValue(fieldModel, raw);
    }

    private Object mapSingleValue(GeneratedFieldModel fieldModel, Object raw) throws Exception {
        if (raw == null) return null;
        if (effectiveType(fieldModel) == FieldType.IMAGE) {
            if (raw instanceof ImagePane) {
                return raw;
            }

            if (raw instanceof WikidataMediaValue media) {
                return toImagePane(media);
            }

            return null;
        }

        if (fieldModel.type() == FieldType.ENTITY) {
            // An entity field must hold a Quizable. A non-entity value (e.g. a
            // P61 "unknown value"/genid that arrived as text) can't go into a
            // quiz.Quizable field — drop it rather than crash the run.
            if (raw instanceof WikidataDynamicObject dyn) {
                return mapObject(dyn, fieldModel.entityClassName());
            }
            return raw instanceof Quizable ? raw : null;
        }
        if (raw instanceof WikidataDynamicObject dyn) {
            return mapObject(dyn, fieldModel.entityClassName());
        }
        if (fieldModel.type() == FieldType.NUMBER) {
            String unit = fieldModel.unit();   // resolved once per field
            if (raw instanceof quiz.Quantity q) return q;
            if (raw instanceof Number n) return new quiz.Quantity(n.doubleValue(), unit);
            try { return new quiz.Quantity(Double.parseDouble(String.valueOf(raw)), unit); }
            catch (Exception ignored) { return null; }
        }
        if (effectiveType(fieldModel) == FieldType.DATE) {
            return formatWikidataDate(String.valueOf(raw));
        }

        return raw;
    }

    // Wikidata times are [+-]YYYY-MM-DDThh:mm:ssZ; the truthy value loses the
    // precision, so collapse the common year-precision form (…-01-01T…) to just
    // the year ("1875", "5000 BC") and otherwise drop the time-of-day.
    private static final java.util.regex.Pattern WD_TIME =
            java.util.regex.Pattern.compile("^([+-]?)0*(\\d+)-(\\d{2})-(\\d{2})T");

    static String formatWikidataDate(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = WD_TIME.matcher(s);
        if (!m.find()) {
            return s;
        }
        boolean bc = "-".equals(m.group(1));
        String year = m.group(2);
        String mm = m.group(3);
        String dd = m.group(4);
        String body = (mm.equals("01") && dd.equals("01"))
                ? year                                   // year precision
                : year + "-" + mm + (dd.equals("01") ? "" : "-" + dd);
        return bc ? body + " BC" : body;
    }

    private FieldType effectiveType(GeneratedFieldModel field) {
        String pid =
                field.mapping() == null ? "" : field.mapping().propertyPid();

        if ("P18".equals(pid) || "P242".equals(pid)) {
            return FieldType.IMAGE;
        }

        return field.type();
    }

    private ImagePane toImagePane(WikidataMediaValue media) {
        try {
            return new ImagePane(
                    media.label(),
                    media.url(),
                    null,
                    false,
                    media.svg(),
                    false);   // loadThumbnailImmediately = false

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setIfExists(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        if (f == null) return;
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }
}
