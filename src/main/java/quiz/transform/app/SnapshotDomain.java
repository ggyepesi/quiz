package quiz.transform.app;

import quiz.Quizable;
import quiz.QuizableFieldPaths;
import quiz.transform.FieldAccess;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A {@link DomainModel} over a loaded Wikidata snapshot pool (the wikidata bridge). */
public final class SnapshotDomain implements DomainModel {

    private final List<WikidataDynamicObject> pool;
    private final DomainSchema schema;

    public SnapshotDomain(List<WikidataDynamicObject> pool) {
        this.pool = pool;
        this.schema = new DomainSchema(pool);
    }

    @Override public List<String> types() { return schema.types(); }

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
        List<DomainField> out = new ArrayList<>();
        for (QuizableFieldPaths.FieldPath fp
                : QuizableFieldPaths.collectFromSample(sample, QuizableFieldPaths.ALL_FIELDS)) {
            String path = String.join(".", fp.path());
            Object value = FieldAccess.getPath(sample, path);
            boolean ref = value instanceof Quizable
                    || (value instanceof Collection<?> c && anyQuizable(c));
            boolean col = value instanceof Collection<?>;
            out.add(new DomainField(type, path, ref, col));
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
