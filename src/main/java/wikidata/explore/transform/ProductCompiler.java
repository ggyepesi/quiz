package wikidata.explore.transform;

import quiz.Quizable;
import quiz.transform.app.ProductClass;
import quiz.transform.app.ProductDomain;
import quiz.transform.app.ProductField;
import quiz.transform.app.ProductSchema;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
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
 *   <li>each field's shape/label comes from the model (cardinality, target class)
 *       cross-checked against the post-collapse instance value;
 *   <li>the {@code wikidata} link and a statement class's {@code source} reify
 *       back-ref are marked structural (hidden everywhere).
 * </ul>
 *
 * The result is a typed {@link ProductSchema}; QID never surfaces as a field.
 */
public final class ProductCompiler {

    private ProductCompiler() {}

    public static ProductDomain compile(GeneratedProjectModel model,
                                        List<WikidataDynamicObject> pool) {
        // 1. Unmodeled references (declared target class absent from the model)
        //    become display strings — the domain doesn't model that class, so it
        //    reads as a label, not a navigable chip.
        collapseUnmodeledReferences(model, pool);
        // 2. Bare references (no substance) collapse the same way.
        BareReferenceCollapse.apply(pool);

        // The reify convention (ModelStatementReifications): a class C that reifies
        // statements OF class S gets a `source` back-ref to S, and S gets a forward
        // list field `__C`. Neither is in the declared model — derive both here so
        // they're typed like everything else instead of leaking as raw objects.
        Map<String, List<GeneratedClassModel>> reifyChildren = new LinkedHashMap<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c != null && c.reifiesStatements()) {
                reifyChildren
                        .computeIfAbsent(c.statementSourceClass(), k -> new ArrayList<>())
                        .add(c);
            }
        }

        List<ProductClass> classes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c == null || !seen.add(c.className())) {
                continue;
            }
            classes.add(compileClass(model, c, pool,
                    reifyChildren.getOrDefault(c.className(), List.of())));
        }

        return new ProductDomain(new ProductSchema(classes, memberClasses(model, pool)), pool);
    }

    private static ProductClass compileClass(GeneratedProjectModel model,
                                             GeneratedClassModel c,
                                             List<WikidataDynamicObject> pool,
                                             List<GeneratedClassModel> reifyChildren) {
        List<ProductField> fields = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (GeneratedFieldModel f : c.effectiveFields(model)) {
            if (f == null || !names.add(f.name())) {
                continue;
            }
            fields.add(compileField(model, c.className(), f, pool));
        }
        // Forward reify links: `__C` holds the statement records reified out of this
        // class — a real reference list to the (member) statement class C.
        for (GeneratedClassModel rc : reifyChildren) {
            String name = "__" + rc.className();
            if (names.add(name)) {
                fields.add(new ProductField(name, "List<" + rc.displayClassName() + ">",
                        true, true, rc.className(), false));
            }
        }
        // Structural markers: the auto-seeded wikidata link (every class) and a
        // statement class's reify `source` back-ref — hidden, not arguments.
        if (names.add("wikidata")) {
            fields.add(ProductField.structural("wikidata"));
        }
        if (c.reifiesStatements() && names.add("source")) {
            fields.add(ProductField.structural("source"));
        }
        return new ProductClass(c.className(), c.displayClassName(), fields);
    }

    private static ProductField compileField(GeneratedProjectModel model,
                                             String className,
                                             GeneratedFieldModel f,
                                             List<WikidataDynamicObject> pool) {
        FieldType type = f.type();
        String target = f.entityClassName();
        boolean targetDeclared =
                type == FieldType.ENTITY && model.findClass(target) != null;

        Object sample = sampleValue(className, f.name(), pool);
        // Prefer the post-collapse runtime value: a declared-but-bare target (its
        // values collapsed to strings) is NOT a reference despite the ENTITY type.
        boolean reference = sample != null ? isReferenceValue(sample) : targetDeclared;

        boolean collection = switch (f.cardinality()) {
            case COLLECTION -> true;
            case SINGLE -> false;
            case AUTO -> sample instanceof Collection<?>;
        };

        String label = label(model, type, target, reference, collection);
        String nested = reference ? target : null;
        return new ProductField(f.name(), label, reference, collection, nested, false);
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

    /** Member classes: those with substantive stamped instances in the pool
     *  (as opposed to classes that appear only as reference targets), ordered as
     *  the model declares them. */
    private static List<String> memberClasses(GeneratedProjectModel model,
                                              List<WikidataDynamicObject> pool) {
        Set<String> substantive = new LinkedHashSet<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null && o.hasTypeStamp() && isSubstantive(o)) {
                substantive.add(o.typeName());
            }
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c != null && seen.add(c.className())
                    && substantive.contains(c.className())) {
                out.add(c.className());
            }
        }
        return out;
    }

    // Substance = a field beyond the auto-seeded `wikidata` link (a bare object is
    // just a label, not a member class).
    private static boolean isSubstantive(WikidataDynamicObject o) {
        Set<String> keys = o.dynamicFieldValues().keySet();
        return !keys.isEmpty() && !(keys.size() == 1 && keys.contains("wikidata"));
    }

    private static Object sampleValue(String className, String fieldName,
                                      List<WikidataDynamicObject> pool) {
        for (WikidataDynamicObject o : pool) {
            if (o != null && o.hasTypeStamp() && className.equals(o.typeName())) {
                Object v = o.dynamicFieldValues().get(fieldName);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static boolean isReferenceValue(Object v) {
        if (v instanceof Quizable) {
            return true;
        }
        if (v instanceof Collection<?> c) {
            for (Object i : c) {
                if (i instanceof Quizable) {
                    return true;
                }
            }
        }
        return false;
    }
}
