package quiz.transform.app;

import objectview.Viewable;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import quiz.transform.ui.DomainSchemas;
import objectview.field.FieldSchema;
import objectview.viewconfig.FieldTypeSource;
import wikidata.explore.extract.SnapshotFieldGraph;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** A {@link DomainModel} over a loaded Wikidata snapshot pool (the wikidata bridge). */
public final class SnapshotDomain implements DomainModel {

    private final List<WikidataDynamicObject> pool;
    private final SnapshotFieldGraph fieldGraph;
    // Statement-reification classes (from the domain's model): their "source"
    // field is the auto-created reify back-reference — provenance, not an
    // argument — so the field pickers skip it.
    private final java.util.Set<String> statementTypes;

    public SnapshotDomain(List<WikidataDynamicObject> pool) {
        this(pool, null, java.util.Set.of());
    }

    public SnapshotDomain(List<WikidataDynamicObject> pool,
                          java.util.Set<String> statementTypes) {
        this(pool, null, statementTypes);
    }

    public SnapshotDomain(List<WikidataDynamicObject> pool,
                          SnapshotFieldGraph fieldGraph) {
        this(pool, fieldGraph, java.util.Set.of());
    }

    public SnapshotDomain(List<WikidataDynamicObject> pool,
                          SnapshotFieldGraph fieldGraph,
                          java.util.Set<String> statementTypes) {
        // A bare reference (unstamped, no substance — e.g. the type values
        // "film", "song") reads as its display-name String, not an object chip.
        wikidata.explore.transform.BareReferenceCollapse.apply(pool);
        this.pool = pool;
        // Loaded snapshots pass their persisted graph; direct in-memory domains derive
        // the same graph once from their freshly assembled instances.
        this.fieldGraph = fieldGraph == null
                ? SnapshotFieldGraph.derive(pool) : fieldGraph;
        this.statementTypes = statementTypes == null
                ? java.util.Set.of() : statementTypes;
    }

    @Override public List<String> types() { return fieldGraph.allTypes(); }
    @Override public List<String> servedTypes() { return fieldGraph.memberTypes(); }
    @Override public String baseType(String type) { return fieldGraph.baseType(type); }

    @Override public java.util.Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }

    // The snapshot is immutable, so each type's schema is stable — cache it, since the
    // dotted-field / structural / fieldTypes projections call fieldSchema repeatedly (once
    // per type on every recursive path build).
    private final java.util.Map<String, FieldSchema> schemaCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override public FieldSchema fieldSchema(String type) {
        return type == null ? null : schemaCache.computeIfAbsent(type, this::buildFieldSchema);
    }

    private FieldSchema buildFieldSchema(String type) {
        // Only reify back-reference (structural) fields are hidden from transform
        // operations. Group-valued fields are ordinary references and stay configurable.
        java.util.Set<String> extraStructural = new java.util.LinkedHashSet<>();
        if (statementTypes.contains(type)) {
            extraStructural.add("source");
        }
        return fieldGraph.fieldSchema(type, extraStructural);
    }

    @Override public FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }

    /** A small graph-derived shape sample; no instance-pool scan is required. */
    @Override public Viewable representativeSample(String type) {
        return fieldGraph.shapeSample(type);
    }

    @Override public Collection<? extends Viewable> instances() { return pool; }
    @Override public Class<? extends Viewable> universe() { return WikidataDynamicObject.class; }
}
