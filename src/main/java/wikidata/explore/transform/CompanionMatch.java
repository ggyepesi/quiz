package wikidata.explore.transform;

import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generation-time pass for {@link FieldProductionKind#COMPANION_MATCH} boolean
 * fields (e.g. {@code Nomination.won}): loads the companion-set for each such
 * field ({@link CompanionLoader}) and marks the records ({@link CompanionMatcher}).
 *
 * <p>Subject = the field's configured {@code subjectField} (blank → the reify
 * {@code "source"}). Value/role = its {@code matchValueField}/{@code matchRoleField};
 * companion property + role qualifier = its {@code propertyPid}/{@code qualifierPid}.
 * The load is anchored on the VALUE set (the distinct {@code matchValueField} values,
 * e.g. the categories), not the subjects. Generic — nothing award-specific.
 */
public final class CompanionMatch {

    // Fallback subject when subjectField is unset: the reify source field
    // (ReifyConstruct sourceField in ModelStatementReifications).
    static final String DEFAULT_SUBJECT_FIELD = "source";

    private CompanionMatch() {}

    public static void apply(GeneratedProjectModel project,
                             Collection<WikidataDynamicObject> pool,
                             WikidataSparqlClient client,
                             GenerationLog log) {

        if (project == null || pool == null || client == null) {
            return;
        }

        for (GeneratedClassModel c : allClasses(project)) {
            for (GeneratedFieldModel f : c.fields()) {
                if (f == null || f.mapping() == null
                        || f.mapping().productionKind() != FieldProductionKind.COMPANION_MATCH) {
                    continue;
                }
                applyField(c, f, pool, client, log);
            }
        }
    }

    private static void applyField(GeneratedClassModel c,
                                   GeneratedFieldModel f,
                                   Collection<WikidataDynamicObject> pool,
                                   WikidataSparqlClient client,
                                   GenerationLog log) {

        FieldSourceMapping m = f.mapping();
        String companionProperty = m.propertyPid();
        String roleQualifier = m.qualifierPid();
        String subjectField = m.subjectField().isBlank()
                ? DEFAULT_SUBJECT_FIELD : m.subjectField();
        String valueField = m.matchValueField();
        String roleField = m.matchRoleField();
        String outcomeField = f.name();

        if (companionProperty == null || !companionProperty.matches("(?i)P\\d+")
                || roleQualifier == null || !roleQualifier.matches("(?i)P\\d+")
                || valueField == null || valueField.isBlank()
                || roleField == null || roleField.isBlank()) {
            if (log != null) {
                log.message("Companion match '" + outcomeField
                        + "' skipped: incomplete config (need property, qualifier, "
                        + "value field, role field).\n");
            }
            return;
        }

        // The records of THIS class, and the distinct VALUES to anchor the load
        // (e.g. the categories the nominations are in).
        List<WikidataDynamicObject> records = new ArrayList<>();
        Set<String> values = new LinkedHashSet<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null && c.className().equals(o.typeName())) {
                records.add(o);
                String v = qid(o.get(valueField));
                if (!v.isEmpty()) {
                    values.add(v);
                }
            }
        }
        if (records.isEmpty()) {
            return;
        }

        Set<List<String>> companions =
                CompanionLoader.load(values, companionProperty, roleQualifier, client, log);

        CompanionMatcher.apply(records, companions, subjectField,
                valueField, roleField, outcomeField, log);
    }

    private static List<GeneratedClassModel> allClasses(GeneratedProjectModel p) {
        List<GeneratedClassModel> all = new ArrayList<>(p.classes());
        if (p.rootClass() != null && !all.contains(p.rootClass())) {
            all.add(p.rootClass());
        }
        return all;
    }

    private static String qid(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            return w.qid() == null ? "" : w.qid();
        }
        return v == null ? "" : String.valueOf(v);
    }
}
