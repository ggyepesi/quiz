package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stamps a class instance's ENTITY-field referents with the class the field
 * declares (its {@code entityClassName}), so a reference resolves to a real
 * (bare) class and reads as "Nominee: Meryl Streep" — instead of collapsing to a
 * bare, untyped label ({@link BareReferenceCollapse} turns an unstamped referent
 * into a plain string, which is why nominee/forWork looked like "not clear what
 * this references").
 *
 * <p>The declared target may be either a modeled class (e.g. {@code Nominee}) OR
 * a named VOCABULARY {@link wikidata.explore.model.Selection} (e.g. {@code
 * category -> OscarCategories}, where the closed category vocabulary IS the
 * referent's type). Only stamps when the target actually EXISTS as one of those,
 * so a dangling {@code entityClassName} (a typo, or a target since removed) is
 * left as an untyped label rather than conjuring a phantom type. An already
 * type-stamped referent (e.g. a served class member) is never re-stamped. The
 * referents are shared pool instances (QID identity), so a single stamp types the
 * entity everywhere it is referenced.
 */
public final class ReferentClassStamp {

    private ReferentClassStamp() {}

    /** @return the number of referent objects newly stamped. */
    public static int apply(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> instances) {

        if (model == null || instances == null) {
            return 0;
        }

        // className -> (fieldName -> declared entityClassName), only for ENTITY
        // fields whose declared target is a real class in the model.
        Map<String, Map<String, String>> fieldClasses = new LinkedHashMap<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c == null) {
                continue;
            }
            Map<String, String> byField = new LinkedHashMap<>();
            for (GeneratedFieldModel f : c.fields()) {
                if (f == null || f.type() != FieldType.ENTITY) {
                    continue;
                }
                String target = f.entityClassName();
                if (target == null || target.isBlank()
                        || (model.findClass(target) == null
                                && model.findSelection(target) == null)) {
                    continue;
                }
                byField.put(f.name(), target);
            }
            if (!byField.isEmpty()) {
                fieldClasses.put(c.className(), byField);
            }
        }
        if (fieldClasses.isEmpty()) {
            return 0;
        }

        int stamped = 0;
        for (WikidataDynamicObject o : instances) {
            if (o == null || o.typeName() == null) {
                continue;
            }
            Map<String, String> byField = fieldClasses.get(o.typeName());
            if (byField == null) {
                continue;
            }
            for (Map.Entry<String, String> e : byField.entrySet()) {
                stamped += stamp(o.get(e.getKey()), e.getValue());
            }
        }
        return stamped;
    }

    private static int stamp(Object value, String className) {
        if (value instanceof WikidataDynamicObject w) {
            if (!w.hasTypeStamp()
                    && w.qid() != null && WikidataIds.isQid(w.qid())) {
                w.type(className);
                return 1;
            }
            return 0;
        }
        if (value instanceof List<?> list) {
            int n = 0;
            for (Object item : list) {
                n += stamp(item, className);
            }
            return n;
        }
        return 0;
    }
}
