package quiz.transform.app;

import objectview.Viewable;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
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
        // New snapshots carry this graph. Derivation remains only for old snapshots
        // and direct in-memory construction/tests.
        this.fieldGraph = fieldGraph == null
                ? SnapshotFieldGraph.derive(pool) : fieldGraph;
        this.statementTypes = statementTypes == null
                ? java.util.Set.of() : statementTypes;
    }

    @Override public List<String> types() { return fieldGraph.memberTypes(); }

    @Override public java.util.Set<String> structuralFields(String type) {
        // Saved manual groups are ancestry-qualified view structure, not a data field
        // to offer as a new GROUP_BY facet. The wikidata "source" field is likewise
        // structural provenance for statement classes.
        java.util.Set<String> structural = new java.util.LinkedHashSet<>();
        structural.add("groups");
        if (statementTypes.contains(type)) {
            structural.add("source");
        }
        return java.util.Set.copyOf(structural);
    }

    @Override public List<DomainField> fields(String type) {
        return fieldGraph.fields(type, structuralFields(type));
    }

    @Override public FieldTypeSource fieldTypes(String type) {
        return fieldGraph.fieldTypes(type);
    }

    /** A small graph-derived shape sample; no instance-pool scan is required. */
    @Override public Viewable representativeSample(String type) {
        return fieldGraph.shapeSample(type);
    }

    @Override public Collection<? extends Viewable> instances() { return pool; }
    @Override public Class<? extends Viewable> universe() { return WikidataDynamicObject.class; }
}
