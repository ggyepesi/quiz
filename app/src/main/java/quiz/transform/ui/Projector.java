package quiz.transform.ui;

import objectview.Viewable;
import quiz.transform.DynamicViewable;
import objectview.field.FieldAccess;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Materializes a PROJECT: for each member of {@code memberType}, a new instance of
 * {@code newType} carrying the selected fields' values (read by dotted path, so
 * nested/cross-class fields flatten in). The new instances are plain
 * {@link WikidataDynamicObject}s stamped with the new type — a first-class derived
 * class the {@link WorkingDomain} feeds back into the field pool. The new instances
 * are backing-agnostic {@link DynamicViewable}s (no Wikidata dependency).
 */
public final class Projector {

    private Projector() {}

    public static DerivedClass project(DomainModel domain, String memberType,
                                       List<DomainField> fields, String newType) {
        // Field name per projected path (leaf segment; disambiguated on collision).
        Map<DomainField, String> names = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (DomainField f : fields) {
            String name = leaf(f.field());
            String base = name;
            for (int i = 2; !used.add(name); i++) {
                name = base + "_" + i;
            }
            names.put(f, name);
        }

        List<Viewable> instances = new ArrayList<>();
        for (Viewable q : domain.instances()) {
            if (q == null || !memberType.equals(q.typeName())) {
                continue;
            }
            DynamicViewable o =
                    new DynamicViewable(q.getIdentifier(), q.getDisplayName());
            o.type(newType);
            for (Map.Entry<DomainField, String> e : names.entrySet()) {
                Object v = FieldAccess.getPath(q, e.getKey().field());
                if (v != null) {
                    o.put(e.getValue(), v);
                }
            }
            instances.add(o);
        }

        List<DomainField> derivedFields = new ArrayList<>();
        List<FieldRef> schemaFields = new ArrayList<>();
        for (Map.Entry<DomainField, String> e : names.entrySet()) {
            DomainField f = e.getKey();
            derivedFields.add(new DomainField(newType, e.getValue(),
                    f.reference(), f.collection(), f.kind()));
            FieldRef source = DomainSchemas.resolve(
                    domain, memberType, f.field());
            FieldRef projected = DomainSchemas.renamed(source, e.getValue());
            if (projected == null) {
                projected = DomainSchemas.flatSchema(List.of(
                        new DomainField(newType, e.getValue(),
                                f.reference(), f.collection(), f.kind())))
                        .field(e.getValue());
            }
            schemaFields.add(projected);
        }
        List<FieldRef> immutable = List.copyOf(schemaFields);
        FieldSchema schema = () -> immutable;
        return new DerivedClass(newType, derivedFields, instances, schema);
    }

    static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
