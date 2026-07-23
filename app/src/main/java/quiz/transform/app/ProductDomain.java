package quiz.transform.app;

import quiz.Quizable;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import objectview.field.FieldKind;
import quiz.transform.ui.SchemaView;
import objectview.viewconfig.FieldTypeSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link DomainModel} backed by a compiled {@link ProductSchema} — the typed
 * schema read once at transform-context entry — over the (convention-resolved)
 * instance pool. Unlike {@link SnapshotDomain}, its field types, cardinality,
 * reference targets and structural fields come from the model, not per-sample
 * reflection, so nested references, list-vs-single and hidden plumbing are all
 * authoritative. Built by {@code ProductCompiler}.
 *
 * <p>Generic by design: it only stores and serves the pool + universe it's handed,
 * so it carries no wikidata dependency — the backing specifics live in the compiler.
 */
public final class ProductDomain implements DomainModel, SchemaView {

    // Cap nested-path expansion so a reference cycle (or just deep chains) can't
    // blow up the field list — a path visits any given class at most once.
    private static final int MAX_DEPTH = 4;

    private final ProductSchema schema;
    private final Collection<? extends Quizable> pool;
    private final Class<? extends Quizable> universe;
    // Lazily builds the ModelClass↔ProductClass inspector; supplied by the compiler
    // (which holds the declared model). Null = no schema view.
    private final SchemaView schemaView;

    public ProductDomain(ProductSchema schema, Collection<? extends Quizable> pool,
                         Class<? extends Quizable> universe, SchemaView schemaView) {
        this.schema = schema;
        this.pool = pool;
        this.universe = universe;
        this.schemaView = schemaView;
    }

    @Override public javax.swing.JComponent schemaView() {
        return schemaView == null ? null : schemaView.schemaView();
    }

    @Override public List<String> types() {
        return schema.memberClasses();
    }

    @Override public List<DomainField> fields(String type) {
        List<DomainField> out = new ArrayList<>();
        // type is the OWNING top-level class for every path (nested paths still
        // belong to the type the user selected), matching SnapshotDomain.
        collectFields(type, type, "", new HashSet<>(), 0, out);
        return out;
    }

    private void collectFields(String topType, String className, String prefix,
                               Set<String> visited, int depth,
                               List<DomainField> out) {
        ProductClass pc = schema.get(className);
        if (pc == null || !visited.add(className) || depth > MAX_DEPTH) {
            return;
        }
        for (ProductField f : schema.fields(className)) {
            String path = prefix.isEmpty() ? f.name() : prefix + "." + f.name();
            FieldKind kind = f.collection() ? FieldKind.COLLECTION
                    : f.reference() ? FieldKind.REFERENCE
                    : FieldKind.ofTypeLabel(f.typeLabel());
            out.add(new DomainField(topType, path, f.reference(), f.collection(), kind));
            if (f.reference() && f.nestedClassName() != null) {
                // The referent's identity is a useful leaf path (nominee.name).
                out.add(new DomainField(topType, path + ".name", false, false, FieldKind.TEXT));
                collectFields(topType, f.nestedClassName(), path,
                        new HashSet<>(visited), depth + 1, out);
            }
        }
        visited.remove(className);
    }

    @Override public Set<String> structuralFields(String type) {
        return schema.structuralFields(type);
    }

    /** Authoritative field types for the config editor's dynamic sample of {@code
     *  type} — labels, structural-ness and nested sources straight from the model. */
    @Override public FieldTypeSource fieldTypes(String type) {
        return sourceFor(type);
    }

    private FieldTypeSource sourceFor(String className) {
        ProductClass pc = schema.get(className);
        return new FieldTypeSource() {
            @Override public FieldTypeSource.FieldTypeInfo field(String name) {
                ProductField f = pc == null ? null : pc.field(name);
                if (f == null) {
                    return null;   // unknown (e.g. the `name` identity row) → reflect
                }
                // A name-only reference (target has no fields beyond its identity, e.g.
                // Category) stays a reference — identity is preserved for grouping —
                // but offers no expansion: null nested source means no "+fields" button.
                boolean recurse = f.reference() && f.nestedClassName() != null
                        && !schema.fields(f.nestedClassName()).isEmpty();
                FieldTypeSource nested = recurse ? sourceFor(f.nestedClassName()) : null;
                String nestedName = recurse ? displayName(f.nestedClassName()) : null;
                return new FieldTypeSource.FieldTypeInfo(
                        f.typeLabel(), f.structural(), nestedName, nested);
            }

            @Override public java.util.List<String> fieldNames() {
                // Enumerate the model's fields so a reference with NO current value is
                // still expandable — its children come from the schema, not a sample.
                java.util.List<String> names = new java.util.ArrayList<>();
                for (ProductField f : schema.fields(className)) {
                    names.add(f.name());
                }
                return names;
            }
        };
    }

    private String displayName(String className) {
        ProductClass c = schema.get(className);
        return c != null ? c.displayName() : className;
    }

    @Override public Collection<? extends Quizable> instances() { return pool; }
    @Override public Class<? extends Quizable> universe() { return universe; }
}
