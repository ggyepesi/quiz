package wikidata.explore.transform;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Restrict construct: drops REIFIED records missing a {@code required} field — e.g.
 * a Nomination with no ceremony edition (a bare P1411 statement whose P805 is
 * absent, leaving edition empty). Pairs with the subject-default override (#92
 * slice 2): turn a plain reference's subject-default OFF so an absent qualifier
 * stays EMPTY, then mark it required so the ceremony-less record is dropped rather
 * than served as a phantom (the Whale, #95).
 *
 * <p>Reified classes only: a {@code required} field on a SPARQL-extracted class is
 * already enforced as a non-optional triple at query time (RuleTreeCompiler), so
 * this adds nothing there. Removes the dropped records from {@code pool} and returns
 * them, so a caller can also exclude them elsewhere.
 */
public final class RequiredFieldRestrictions {

    private RequiredFieldRestrictions() {}

    public static List<WikidataDynamicObject> apply(
            GeneratedProjectModel project,
            Collection<WikidataDynamicObject> pool,
            GenerationLog log) {

        List<WikidataDynamicObject> dropped = new ArrayList<>();
        if (project == null || pool == null) {
            return dropped;
        }
        for (GeneratedClassModel cls : project.classes()) {
            if (cls == null || !cls.reifiesStatements()) {
                continue;
            }
            List<String> requiredFields = new ArrayList<>();
            for (GeneratedFieldModel f : cls.fields()) {
                if (f != null && f.required() && !f.isNameField()) {
                    requiredFields.add(f.name());
                }
            }
            if (requiredFields.isEmpty()) {
                continue;
            }
            int before = dropped.size();
            for (WikidataDynamicObject o : pool) {
                if (o == null || !cls.className().equals(o.typeName())) {
                    continue;
                }
                for (String rf : requiredFields) {
                    if (isEmpty(o.get(rf))) {
                        dropped.add(o);
                        break;
                    }
                }
            }
            int n = dropped.size() - before;
            if (log != null && n > 0) {
                log.message("Restrict " + cls.className()
                        + " (required " + String.join(", ", requiredFields)
                        + "): dropped " + n + " record(s) missing a required field\n");
            }
        }
        if (!dropped.isEmpty()) {
            Set<WikidataDynamicObject> drop =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            drop.addAll(dropped);
            pool.removeIf(drop::contains);
        }
        return dropped;
    }

    private static boolean isEmpty(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (v instanceof CharSequence s) {
            return s.toString().isBlank();
        }
        return false;
    }
}
