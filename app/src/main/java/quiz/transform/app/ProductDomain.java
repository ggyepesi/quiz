package quiz.transform.app;

import objectview.Viewable;
import domain.DomainField;
import domain.DomainModel;
import domain.DomainSchemas;
import quiz.transform.ui.SchemaView;
import objectview.field.FieldSchema;
import objectview.viewconfig.FieldTypeSource;

import java.util.Collection;
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

    private final ProductSchema schema;
    private final Collection<? extends Viewable> pool;
    private final Class<? extends Viewable> universe;
    // Lazily builds the ModelClass↔ProductClass inspector; supplied by the compiler
    // (which holds the declared model). Null = no schema view.
    private final SchemaView schemaView;
    private final java.util.Map<String, List<Viewable>> selections;

    public ProductDomain(ProductSchema schema, Collection<? extends Viewable> pool,
                         Class<? extends Viewable> universe, SchemaView schemaView) {
        this(schema, pool, universe, schemaView, java.util.Map.of());
    }

    public ProductDomain(ProductSchema schema, Collection<? extends Viewable> pool,
                         Class<? extends Viewable> universe, SchemaView schemaView,
                         java.util.Map<String, List<Viewable>> selections) {
        this.schema = schema;
        this.pool = pool;
        this.universe = universe;
        this.schemaView = schemaView;
        this.selections = selections == null ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(selections));
    }

    @Override public javax.swing.JComponent schemaView() {
        return schemaView == null ? null : schemaView.schemaView();
    }

    @Override public List<String> types() {
        return schema.memberClasses();
    }

    @Override public String baseType(String type) {
        ProductClass productClass = schema.get(type);
        return productClass == null || productClass.baseClassName() == null
                || productClass.baseClassName().isBlank()
                ? null : productClass.baseClassName();
    }

    @Override public Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }

    @Override public FieldSchema fieldSchema(String type) {
        ProductClass productClass = schema.get(type);
        return productClass == null ? null : productClass.asFieldSchema();
    }

    @Override public boolean entityOrigin(String type, objectview.field.FieldPath path) {
        if (type == null || path == null || path.isRoot()) return false;
        String currentType = type;
        List<String> segments = path.segments();
        for (int i = 0; i < segments.size(); i++) {
            ProductClass owner = schema.get(currentType);
            if (owner == null) return false;
            ProductField field = null;
            String segment = segments.get(i);
            for (ProductField candidate : owner.fields()) {
                if (segment.equals(candidate.name())) {
                    field = candidate;
                    break;
                }
            }
            if (field == null) return false;
            if (i == segments.size() - 1) return field.entityOrigin();
            currentType = field.nestedClassName();
            if (currentType == null || currentType.isBlank()) return false;
        }
        return false;
    }

    /** Authoritative field types for the config editor's dynamic sample of {@code
     *  type} — labels, structural-ness and nested sources straight from the model. */
    @Override public FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }

    @Override public Collection<? extends Viewable> instances() { return pool; }
    @Override public List<String> selectionNames() { return List.copyOf(selections.keySet()); }
    @Override public List<Viewable> selectionMembers(String name) {
        return selections.getOrDefault(name, List.of());
    }
    @Override public boolean exposesEntityUniverse() { return true; }
    @Override public Class<? extends Viewable> universe() { return universe; }
}
