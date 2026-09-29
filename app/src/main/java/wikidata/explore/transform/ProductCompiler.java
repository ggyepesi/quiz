package wikidata.explore.transform;

import objectview.Viewable;
import quiz.transform.app.ProductClass;
import quiz.transform.app.ProductDomain;
import quiz.transform.app.ProductField;
import quiz.transform.app.ProductSchema;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a declared model + loaded snapshot pool into a {@link ProductDomain}
 * once, at transform-context entry — THE bridge where wikidata conventions are
 * resolved so nothing downstream has to know them:
 *
 * <ul>
 *   <li>reference fields whose target class the model doesn't declare (e.g.
 *       {@code forWork} -&gt; {@code ForWork}) collapse to display-name strings;
 *   <li>bare references (unstamped, no substance — e.g. the {@code type} values)
 *       collapse too (via {@link BareReferenceCollapse});
 *   <li>each field's shape and label come from the model declaration; instance
 *       samples never redefine schema;
 *   <li>legacy embedded {@code wikidata} links and an undeclared statement
 *       {@code source} reify back-ref are removed; a declared field with that name
 *       remains ordinary domain data; datasource provenance is
 *       supplied by the same declared instance-field mechanism as ModelBuilder.
 * </ul>
 *
 * The result is a typed {@link ProductSchema}; QID never surfaces as a field.
 */
public final class ProductCompiler {

    private ProductCompiler() {}

    public static ProductDomain compile(GeneratedProjectModel model,
                                        List<WikidataDynamicObject> pool) {
        return compile(model, pool, Map.of());
    }

    public static ProductDomain compile(
            GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            Map<String, ? extends List<? extends Viewable>> persistedRoleSelections) {
        // Capture semantic field roles while references are still canonical objects;
        // convention resolution below may collapse non-member references to labels.
        Map<String, List<Viewable>> roleSelections = new LinkedHashMap<>();
        Map<String, List<Viewable>> derivedRoleSelections =
                RoleSelections.materialize(model, pool);
        // Persisted membership describes the exact saved pool and wins while the role
        // definitions are unchanged. A changed/added/removed model role changes the key
        // set (keys are owner.field based), so re-materialize instead of silently using
        // stale snapshot semantics until the next regeneration.
        boolean definitionsMatch = persistedRoleSelections != null
                && !persistedRoleSelections.isEmpty()
                && persistedRoleSelections.keySet().equals(derivedRoleSelections.keySet());
        if (definitionsMatch) {
            persistedRoleSelections.forEach((name, members) ->
                    roleSelections.put(name, List.copyOf(members)));
        } else {
            roleSelections.putAll(derivedRoleSelections);
        }
        // 1. Drop Wikimedia-meta noise (e.g. "Wikimedia list article", a Wikinews
        //    article) from references before collapse — an entity's P31 `type` picks
        //    up such non-domain values, and they'd otherwise become bogus strings.
        filterNoiseReferences(pool);
        // 2. References to an unmodeled class read as their display-name string.
        collapseUnmodeledReferences(model, pool);
        // 3. A declared entity field keeps QID-backed references even when the
        //    referent is outside the loaded top-level population. Other raw references
        //    still collapse to labels.
        List<String> memberList = memberClasses(model, pool);
        collapseReferencesOutsideDeclaredEntityFields(model, pool);

        // 4. Remove legacy extraction plumbing. Datasource provenance is exposed
        //    through ConfiguredInstanceFields below, not a second dynamic field.
        stripUndeclaredStatementSource(model, pool);
        stripLegacyWikidata(pool);
        // 5. Drop the reify forward list (`__Nomination`): the declared model never
        //    had it, so the relation stays one-directional (like Constellation/Star —
        //    navigate Nomination as its own member type, no auto-materialized inverse).
        stripReifyLists(model, pool);

        List<ProductClass> classes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c == null || !seen.add(c.className())) {
                continue;
            }
            classes.add(compileClass(model, c, pool));
        }

        ProductSchema schema = new ProductSchema(classes, memberList);
        // Give every instance of a class the SAME field order (its ProductClass
        // order), so cards read consistently instead of each entity's raw
        // extraction-insertion order.
        reorderInstanceFields(pool, schema);
        // The schema view is built lazily (only if the user opens it) and captures
        // the declared model so it can show ModelClass ↔ ProductClass side by side.
        // ProductDomain is generic — the wikidata universe is supplied here.
        return new ProductDomain(schema, pool, WikidataDynamicObject.class,
                () -> new quiz.transform.app.ProductSchemaInspector(model, schema),
                roleSelections);
    }

    /** Reorder each stamped instance's fields to its ProductClass's field order —
     *  declared fields (model order) first, then any extras — so all instances of a
     *  class render their fields in the same order. */
    private static void reorderInstanceFields(List<WikidataDynamicObject> pool,
                                              ProductSchema schema) {
        for (WikidataDynamicObject o : pool) {
            if (o == null || !o.hasTypeStamp()) {
                continue;
            }
            ProductClass pc = schema.get(o.typeName());
            Map<String, Object> map = o.dynamicFields();
            if (pc == null || map.size() <= 1) {
                continue;
            }
            Map<String, Object> ordered = new LinkedHashMap<>();
            for (ProductField f : pc.fields()) {
                if (map.containsKey(f.name())) {
                    ordered.put(f.name(), map.get(f.name()));
                }
            }
            for (Map.Entry<String, Object> e : map.entrySet()) {
                ordered.putIfAbsent(e.getKey(), e.getValue());
            }
            map.clear();
            map.putAll(ordered);
        }
    }

    private static ProductClass compileClass(GeneratedProjectModel model,
                                             GeneratedClassModel c,
                                             List<WikidataDynamicObject> pool) {
        List<ProductField> fields = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (GeneratedFieldModel f : c.effectiveFields(model)) {
            if (f == null || !names.add(f.name())) {
                continue;
            }
            fields.add(compileField(model, f));
        }
        // Datasource-contributed fields are ordinary declared fields here exactly as
        // in generated ModelBuilder classes. Do not reconstruct an older synthetic
        // Wikidata link in this second schema path.
        for (wikidata.explore.model.ConfiguredInstanceFields.Field configured
                : wikidata.explore.model.ConfiguredInstanceFields.of(
                        c, model, datasource.Datasources.standard())) {
            objectview.field.FieldRef declaration =
                    wikidata.explore.model.ConfiguredInstanceFields.toFieldRef(
                            configured.declaration());
            if (names.add(declaration.name())) {
                fields.add(ProductField.declared(declaration));
            }
        }
        // The reify `source` back-ref is structural — stripped, documented here.
        if (c.reifiesStatements() && names.add("source")) {
            fields.add(ProductField.structural("source"));
        }
        // One question, asked once. It used to read the canonical spec and then
        // correct the answer for owned classes, because a part borrows its owner's QID
        // and so looked like an entity to anything reading the id alone.
        return new ProductClass(c.className(), c.displayClassName(),
                c.baseClassName(), c.classKind().identityFromSource(), fields);
    }

    private static ProductField compileField(
            GeneratedProjectModel model, GeneratedFieldModel f) {
        FieldType type = f.type();
        String target = f.entityClassName();
        boolean targetDeclared =
                type == FieldType.ENTITY && model.findClass(target) != null;
        boolean reference = targetDeclared;

        boolean collection = f.cardinality() ==
                wikidata.explore.model.FieldCardinality.COLLECTION;

        String label = label(model, type, target, reference, collection);
        String nested = reference ? target : null;
        return new ProductField(f.name(), label, reference, collection, nested, false,
                type == FieldType.ENTITY);
    }

    private static String label(GeneratedProjectModel model, FieldType type,
                                String target, boolean reference, boolean collection) {
        String base;
        if (reference) {
            GeneratedClassModel tc = model.findClass(target);
            base = tc != null ? tc.displayClassName() : target;
        } else if (type == FieldType.ENTITY) {
            base = "String";   // an unmodeled / bare reference, read as its label
        } else {
            base = scalarLabel(type);
        }
        return collection ? "List<" + base + ">" : base;
    }

    private static String scalarLabel(FieldType type) {
        return switch (type) {
            case NUMBER -> "Number";
            case DATE -> "Date";
            case BOOLEAN -> "Boolean";
            case IMAGE -> "Image";
            case TEXT -> "Text";
            default -> "String";
        };
    }

    // --- convention resolution ------------------------------------------------

    /** Collapses, in place, values of ENTITY fields whose declared target class
     *  isn't in the model — on instances stamped as the owning class. */
    private static void collapseUnmodeledReferences(GeneratedProjectModel model,
                                                    List<WikidataDynamicObject> pool) {
        // (className, fieldName) pairs to collapse.
        Set<String> unmodeled = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c == null || !seen.add(c.className())) {
                continue;
            }
            for (GeneratedFieldModel f : c.effectiveFields(model)) {
                if (f != null && f.type() == FieldType.ENTITY
                        && model.findClass(f.entityClassName()) == null) {
                    unmodeled.add(c.className() + " " + f.name());
                }
            }
        }
        if (unmodeled.isEmpty()) {
            return;
        }
        for (WikidataDynamicObject o : pool) {
            if (o == null || !o.hasTypeStamp()) {
                continue;
            }
            for (Map.Entry<String, Object> e : o.dynamicFields().entrySet()) {
                if (unmodeled.contains(o.typeName() + " " + e.getKey())) {
                    e.setValue(toDisplayString(e.getValue()));
                }
            }
        }
    }

    /** The reify `source` back-ref is pure plumbing — remove it from a statement
     *  instance so no surface renders it (no view operation reads it either). A
     *  statement class that DECLARES a field of that name, in any role, owns it as
     *  domain data; which role it plays does not decide whether it survives. */
    private static void stripUndeclaredStatementSource(
            GeneratedProjectModel model, List<WikidataDynamicObject> pool) {
        Set<String> legacyOwners = new LinkedHashSet<>();
        for (GeneratedClassModel owner : model.classes()) {
            if (owner != null && owner.reifiesStatements()
                    && owner.effectiveFields(model).stream().noneMatch(field ->
                    field != null && ModelStatementReifications.SYNTHETIC_SUBJECT_FIELD
                            .equals(field.name()))) {
                legacyOwners.add(owner.className());
            }
        }
        if (legacyOwners.isEmpty()) return;
        for (WikidataDynamicObject o : pool) {
            if (o != null && o.directClassNames().stream().anyMatch(legacyOwners::contains)) {
                o.remove(ModelStatementReifications.SYNTHETIC_SUBJECT_FIELD);
            }
        }
    }

    /** Remove the obsolete embedded link; wikidataSource is the declared field. */
    private static void stripLegacyWikidata(List<WikidataDynamicObject> pool) {
        for (WikidataDynamicObject o : pool) {
            if (o == null) {
                continue;
            }
            o.dynamicFields().remove("wikidata");
            o.dynamicFields().remove("Wikidata");
        }
    }

    /** A reference is Wikimedia-meta noise (a "Wikimedia list article", a Wikinews
     *  article, …) — not a domain value, so it's dropped from its field. */
    private static boolean isNoiseReference(Object v) {
        if (!(v instanceof WikidataDynamicObject w)) {
            return false;
        }
        String n = w.getDisplayName();
        if (n == null) {
            return false;
        }
        String s = n.toLowerCase();
        return s.contains("wikimedia") || s.contains("wikinews");
    }

    /** Drop Wikimedia-meta noise referents from every field, in place. */
    private static void filterNoiseReferences(List<WikidataDynamicObject> pool) {
        for (WikidataDynamicObject o : pool) {
            if (o == null) {
                continue;
            }
            for (Map.Entry<String, Object> e
                    : new ArrayList<>(o.dynamicFields().entrySet())) {
                Object v = e.getValue();
                if (isNoiseReference(v)) {
                    o.remove(e.getKey());
                } else if (v instanceof List<?> list) {
                    List<Object> kept = new ArrayList<>();
                    for (Object i : list) {
                        if (!isNoiseReference(i)) {
                            kept.add(i);
                        }
                    }
                    if (kept.size() != list.size()) {
                        if (kept.isEmpty()) {
                            o.remove(e.getKey());
                        } else {
                            o.dynamicFields().put(e.getKey(),
                                    kept.size() == 1 ? kept.get(0) : kept);
                        }
                    }
                }
            }
        }
    }

    /** Remove the reify forward-list field ({@code __Nomination}) from the source
     *  entities — the declared model never had it; the relation stays one-directional. */
    private static void stripReifyLists(GeneratedProjectModel model,
                                        List<WikidataDynamicObject> pool) {
        Set<String> keys = new LinkedHashSet<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c != null && c.reifiesStatements()) {
                keys.add("__" + c.className());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        for (WikidataDynamicObject o : pool) {
            if (o != null) {
                for (String k : keys) {
                    o.remove(k);
                }
            }
        }
    }

    /** A declared modeled entity field keeps its references without making those
     *  referents top-level members. References in every other field collapse to text. */
    private static void collapseReferencesOutsideDeclaredEntityFields(
            GeneratedProjectModel model, List<WikidataDynamicObject> pool) {
        Set<String> entityFields = new LinkedHashSet<>();
        for (GeneratedClassModel owner : model.classes()) {
            if (owner == null) continue;
            for (GeneratedFieldModel field : owner.effectiveFields(model)) {
                if (field != null && field.type() == FieldType.ENTITY
                        && model.findClass(field.entityClassName()) != null) {
                    entityFields.add(owner.className() + "\0" + field.name());
                }
            }
        }
        for (WikidataDynamicObject o : pool) {
            if (o == null) {
                continue;
            }
            for (String key : new ArrayList<>(o.dynamicFields().keySet())) {
                if (!entityFields.contains(o.typeName() + "\0" + key)) {
                    o.dynamicFields().put(key,
                            collapseReference(o.dynamicFields().get(key)));
                }
            }
        }
    }

    private static Object collapseReference(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            return w.getDisplayName();
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object i : list) {
                out.add(collapseReference(i));
            }
            return out;
        }
        return v;
    }

    private static Object toDisplayString(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            return w.getDisplayName();
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object i : c) {
                out.add(toDisplayString(i));
            }
            return out;
        }
        return v;
    }

    /** Member classes: declared classes that actually have STAMPED instances in
     *  the pool (a real entity you can select/browse), ordered as the model
     *  declares them. A declared class that only ever appears as a bare label —
     *  never stamped, e.g. the `type` values — stays a reference target, not a
     *  member. Identity-only entities (e.g. Category, whose only field is the
     *  wikidata link) ARE members: their QID identity is what you group by. */
    private static List<String> memberClasses(GeneratedProjectModel model,
                                              List<WikidataDynamicObject> pool) {
        Set<String> stamped = new LinkedHashSet<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null && o.hasTypeStamp()) {
                stamped.addAll(o.directClassNames());
            }
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c != null && seen.add(c.className())
                    && stamped.contains(c.className())) {
                out.add(c.className());
            }
        }
        return out;
    }

}
