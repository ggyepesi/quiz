package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reified statements that have lost an end their class declares a destination for.
 *
 * <p>A statement is a subject, a property and an object; a record without its subject or
 * its object is not a smaller statement but an incomplete one, which is why a domain
 * must declare a destination for both ends. An end can still be taken away after
 * acquisition: the disambiguation prune removes a Wikimedia-internal subject (a list
 * page carrying P39) and, by design, scrubs the references to it, and a dead-stub prune
 * or the entity-field type check can do the same. The statement then survived with no
 * subject — eleven History office holdings did, and re-reducing the snapshot by its key
 * folded them into three. Like an owned part whose owner is gone, such a record is
 * dropped once nothing can still remove an end, by this one rule rather than in each prune.
 *
 * <p>Only a declared end is required. A reusable model may leave an end open, and then
 * there is no destination to find empty.
 */
public final class IncompleteStatements {

    private IncompleteStatements() { }

    /** A statement record missing a declared end, and which end it is missing. */
    public record Missing(WikidataDynamicObject statement, String className,
                          FieldProductionKind end, String fieldName) { }

    public static List<Missing> find(GeneratedProjectModel model,
                                     Collection<WikidataDynamicObject> pool) {
        List<Missing> missing = new ArrayList<>();
        if (model == null || pool == null) return missing;
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz == null || !clazz.reifiesStatements()) continue;
            List<GeneratedFieldModel> ends = clazz.fields().stream()
                    .filter(field -> field != null && isEnd(field.mapping().productionKind()))
                    .toList();
            if (ends.isEmpty()) continue;
            for (WikidataDynamicObject record : pool) {
                if (record == null || !record.directClassNames().contains(clazz.className())) {
                    continue;
                }
                for (GeneratedFieldModel end : ends) {
                    if (record.get(end.name()) == null) {
                        missing.add(new Missing(record, clazz.className(),
                                end.mapping().productionKind(), end.name()));
                        break;
                    }
                }
            }
        }
        return missing;
    }

    private static boolean isEnd(FieldProductionKind kind) {
        return kind == FieldProductionKind.STATEMENT_SUBJECT
                || kind == FieldProductionKind.STATEMENT_OBJECT;
    }
}
