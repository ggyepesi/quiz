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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        applyWithSets(project, pool, loadSets(project, pool, client, log), log);
    }

    /**
     * NETWORK: load each COMPANION_MATCH field's companion-set, keyed by
     * {@code "Class.field"} so a Remap can re-match offline via
     * {@link #applyWithSets} without re-fetching.
     */
    public static Map<String, Set<List<String>>> loadSets(
            GeneratedProjectModel project,
            Collection<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {

        Map<String, Set<List<String>>> out = new LinkedHashMap<>();
        if (project == null || pool == null || client == null) {
            return out;
        }
        for (GeneratedClassModel c : allClasses(project)) {
            for (GeneratedFieldModel f : companionFields(c)) {
                FieldSourceMapping m = f.mapping();
                if (!validConfig(m, f.name(), log)) {
                    continue;
                }
                Set<String> values = new LinkedHashSet<>();
                for (WikidataDynamicObject o : pool) {
                    if (o != null && c.className().equals(o.typeName())) {
                        String v = qid(o.get(m.matchValueField()));
                        if (!v.isEmpty()) {
                            values.add(v);
                        }
                    }
                }
                if (values.isEmpty()) {
                    continue;
                }
                out.put(key(c, f), CompanionLoader.load(
                        values, m.propertyPid(), m.qualifierPid(), client, log));
            }
        }
        return out;
    }

    /** PURE: mark records using precomputed companion-sets (from {@link #loadSets}). */
    public static void applyWithSets(GeneratedProjectModel project,
                                     Collection<WikidataDynamicObject> pool,
                                     Map<String, Set<List<String>>> sets,
                                     GenerationLog log) {
        if (project == null || pool == null || sets == null) {
            return;
        }
        for (GeneratedClassModel c : allClasses(project)) {
            for (GeneratedFieldModel f : companionFields(c)) {
                FieldSourceMapping m = f.mapping();
                Set<List<String>> companions = sets.get(key(c, f));
                if (companions == null || !validConfig(m, f.name(), null)) {
                    continue;
                }
                String subjectField = m.subjectField().isBlank()
                        ? DEFAULT_SUBJECT_FIELD : m.subjectField();
                List<WikidataDynamicObject> records = new ArrayList<>();
                for (WikidataDynamicObject o : pool) {
                    if (o != null && c.className().equals(o.typeName())) {
                        records.add(o);
                    }
                }
                if (!records.isEmpty()) {
                    CompanionMatcher.apply(records, companions, subjectField,
                            m.matchValueField(), m.matchRoleField(), f.name(), log);
                }
            }
        }
    }

    private static List<GeneratedFieldModel> companionFields(GeneratedClassModel c) {
        List<GeneratedFieldModel> out = new ArrayList<>();
        for (GeneratedFieldModel f : c.fields()) {
            if (f != null && f.mapping() != null
                    && f.mapping().productionKind() == FieldProductionKind.COMPANION_MATCH) {
                out.add(f);
            }
        }
        return out;
    }

    private static String key(GeneratedClassModel c, GeneratedFieldModel f) {
        return c.className() + "." + f.name();
    }

    private static boolean validConfig(FieldSourceMapping m, String outcome,
                                       GenerationLog log) {
        boolean ok = m.propertyPid() != null && m.propertyPid().matches("(?i)P\\d+")
                && m.qualifierPid() != null && m.qualifierPid().matches("(?i)P\\d+")
                && m.matchValueField() != null && !m.matchValueField().isBlank()
                && m.matchRoleField() != null && !m.matchRoleField().isBlank();
        if (!ok && log != null) {
            log.message("Companion match '" + outcome
                    + "' skipped: incomplete config (need property, qualifier, "
                    + "value field, role field).\n");
        }
        return ok;
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
