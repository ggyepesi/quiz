package quiz.transform.app;

import objectview.Viewable;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import quiz.transform.ui.DomainSchemas;
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

    public ProductDomain(ProductSchema schema, Collection<? extends Viewable> pool,
                         Class<? extends Viewable> universe, SchemaView schemaView) {
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

    @Override public String baseType(String type) {
        ProductClass productClass = schema.get(type);
        return productClass == null || productClass.baseClassName() == null
                || productClass.baseClassName().isBlank()
                ? null : productClass.baseClassName();
    }

    @Override public List<DomainField> fields(String type) {
        return DomainSchemas.fields(this, type);
    }

    @Override public Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }

    @Override public FieldSchema fieldSchema(String type) {
        ProductClass productClass = schema.get(type);
        return productClass == null ? null : productClass.asFieldSchema();
    }

    /** Authoritative field types for the config editor's dynamic sample of {@code
     *  type} — labels, structural-ness and nested sources straight from the model. */
    @Override public FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }

    @Override public Collection<? extends Viewable> instances() { return pool; }
    @Override public Class<? extends Viewable> universe() { return universe; }
}
