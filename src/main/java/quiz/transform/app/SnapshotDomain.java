package quiz.transform.app;

import quiz.Quizable;
import quiz.QuizableFieldPaths;
import quiz.transform.FieldAccess;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import quiz.fields.FieldKind;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A {@link DomainModel} over a loaded Wikidata snapshot pool (the wikidata bridge). */
public final class SnapshotDomain implements DomainModel {

    private final List<WikidataDynamicObject> pool;
    private final DomainSchema schema;
    // Statement-reification classes (from the domain's model): their "source"
    // field is the auto-created reify back-reference — provenance, not an
    // argument — so the field pickers skip it.
    private final java.util.Set<String> statementTypes;

    public SnapshotDomain(List<WikidataDynamicObject> pool) {
        this(pool, java.util.Set.of());
    }

    public SnapshotDomain(List<WikidataDynamicObject> pool,
                          java.util.Set<String> statementTypes) {
        // A bare reference (unstamped, no substance — e.g. the type values
        // "film", "song") reads as its display-name String, not an object chip.
        wikidata.explore.transform.BareReferenceCollapse.apply(pool);
        this.pool = pool;
        this.schema = new DomainSchema(pool);
        this.statementTypes = statementTypes == null
                ? java.util.Set.of() : statementTypes;
    }

    @Override public List<String> types() { return schema.types(); }

    @Override public java.util.Set<String> structuralFields(String type) {
        // The wikidata convention, translated to the generic seam: a statement
        // class's "source" field is the reify back-reference (provenance).
        return statementTypes.contains(type)
                ? java.util.Set.of("source") : java.util.Set.of();
    }

    @Override public List<DomainField> fields(String type) {
        // Enumerate from a sample instance so NESTED paths (nominee.name,
        // category.edition) appear for the dynamic snapshot too — same nested/typed
        // field model as the reflection domains. Shape is read from the sample value.
        WikidataDynamicObject sample = null;
        for (WikidataDynamicObject o : pool) {
            if (o != null && type.equals(o.typeName())) {
                sample = o;
                break;
            }
        }
        if (sample == null) {
            return List.of();
        }
        java.util.Set<String> structural = structuralFields(type);
        List<DomainField> out = new ArrayList<>();
        for (QuizableFieldPaths.FieldPath fp
                : QuizableFieldPaths.collectFromSample(sample, QuizableFieldPaths.ALL_FIELDS)) {
            String head = fp.dotted().contains(".")
                    ? fp.dotted().substring(0, fp.dotted().indexOf('.'))
                    : fp.dotted();
            if (structural.contains(head)) {
                continue;
            }
            Object value = FieldAccess.getPath(sample, fp.dotted());
            boolean ref = value instanceof Quizable
                    || (value instanceof Collection<?> c && anyQuizable(c));
            boolean col = value instanceof Collection<?>;
            FieldKind kind = col ? FieldKind.COLLECTION
                    : ref ? FieldKind.REFERENCE : FieldKind.ofValue(value);
            out.add(new DomainField(type, fp, ref, col, kind));
        }
        return out;
    }

    private static boolean anyQuizable(Collection<?> c) {
        for (Object o : c) {
            if (o instanceof Quizable) return true;
        }
        return false;
    }

    @Override public Collection<? extends Quizable> instances() { return pool; }
    @Override public Class<? extends Quizable> universe() { return WikidataDynamicObject.class; }
}
