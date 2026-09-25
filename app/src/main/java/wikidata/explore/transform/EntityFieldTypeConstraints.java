package wikidata.explore.transform;

import datasource.schema.FieldType;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityRepresentations;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Enforces configured entity-field types on the dynamic pool before it is saved. */
public final class EntityFieldTypeConstraints {
    private EntityFieldTypeConstraints() { }

    /** @return the number of incompatible field values removed. */
    public static int apply(GeneratedProjectModel project,
                            Collection<WikidataDynamicObject> pool,
                            GenerationLog log) {
        if (project == null || pool == null) return 0;
        int removed = 0;
        for (WikidataDynamicObject owner : pool) {
            if (owner == null) continue;
            GeneratedClassModel ownerClass = project.findClass(owner.typeName());
            if (ownerClass == null) continue;
            for (GeneratedFieldModel field : ownerClass.effectiveFields(project)) {
                if (field == null || field.type() != FieldType.ENTITY) continue;
                String expected = field.entityClassName();
                if (expected == null || expected.isBlank()
                        || project.findClass(expected) == null) continue;
                removed += prune(owner, field.name(), expected, project);
            }
        }
        if (removed > 0 && log != null) {
            log.message("Removed " + removed
                    + " entity-field value(s) whose configured types were incompatible.\n");
        }
        return removed;
    }

    private static int prune(WikidataDynamicObject owner, String fieldName,
                             String expected, GeneratedProjectModel project) {
        Object value = owner.get(fieldName);
        if (value instanceof Collection<?> values) {
            List<Object> kept = new ArrayList<>();
            int removed = 0;
            for (Object item : values) {
                if (accepted(item, expected, project)) kept.add(item);
                else removed++;
            }
            if (removed == 0) return 0;
            if (kept.isEmpty()) owner.remove(fieldName);
            else owner.put(fieldName, kept);
            return removed;
        }
        if (value != null && !accepted(value, expected, project)) {
            owner.remove(fieldName);
            return 1;
        }
        return 0;
    }

    private static boolean accepted(Object value, String expected,
                                    GeneratedProjectModel project) {
        if (!(value instanceof WikidataDynamicObject entity)
                || !entity.hasTypeStamp()) return true;
        return EntityRepresentations.fieldAccepts(
                project, expected, entity.directClassNames());
    }
}
