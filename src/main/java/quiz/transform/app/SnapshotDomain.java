package quiz.transform.app;

import quiz.Quizable;
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
        List<DomainField> out = new ArrayList<>();
        for (String f : schema.fields(type)) {
            out.add(new DomainField(type, f,
                    schema.isReference(type, f), schema.isCollection(type, f)));
        }
        return out;
    }

    @Override public Collection<? extends Quizable> instances() { return pool; }
    @Override public Class<? extends Quizable> universe() { return WikidataDynamicObject.class; }
}
