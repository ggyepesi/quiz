package wikidata.explore.transform;

import wikidata.WikidataSparqlClient;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledFieldSource;
import wikidata.explore.compiled.CompiledProjectModel;
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

    // The model-resolved config for one COMPANION_MATCH field. Both model types
    // compile down to this, so the pool load/mark logic is model-agnostic.
    private record CompanionField(
            String className, String fieldName,
            String propertyPid, String qualifierPid,
            String subjectField, String matchValueField, String matchRoleField) {

        String key() {
            return className + "." + fieldName;
        }
    }

    // ---------------- editable-model API ----------------

    public static void apply(GeneratedProjectModel project,
                             Collection<WikidataDynamicObject> pool,
                             WikidataSparqlClient client,
                             GenerationLog log) {
        List<CompanionField> fields = editableFields(project, log);
        applyWithSets(fields, pool, loadSets(fields, pool, client, log), log);
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

        if (project == null || pool == null || client == null) {
            return new LinkedHashMap<>();
        }
        return loadSets(editableFields(project, log), pool, client, log);
    }

    /** PURE: mark records using precomputed companion-sets (from {@link #loadSets}). */
    public static void applyWithSets(GeneratedProjectModel project,
                                     Collection<WikidataDynamicObject> pool,
                                     Map<String, Set<List<String>>> sets,
                                     GenerationLog log) {
        if (project == null || pool == null || sets == null) {
            return;
        }
        applyWithSets(editableFields(project, null), pool, sets, log);
    }

    // ---------------- compiled-model API ----------------

    public static void apply(CompiledProjectModel project,
                             Collection<WikidataDynamicObject> pool,
                             WikidataSparqlClient client,
                             GenerationLog log) {
        List<CompanionField> fields = compiledFields(project, log);
        applyWithSets(fields, pool, loadSets(fields, pool, client, log), log);
    }

    public static Map<String, Set<List<String>>> loadSets(
            CompiledProjectModel project,
            Collection<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {

        if (project == null || pool == null || client == null) {
            return new LinkedHashMap<>();
        }
        return loadSets(compiledFields(project, log), pool, client, log);
    }

    public static void applyWithSets(CompiledProjectModel project,
                                     Collection<WikidataDynamicObject> pool,
                                     Map<String, Set<List<String>>> sets,
                                     GenerationLog log) {
        if (project == null || pool == null || sets == null) {
            return;
        }
        applyWithSets(compiledFields(project, null), pool, sets, log);
    }

    // ---------------- shared pool logic ----------------

    private static Map<String, Set<List<String>>> loadSets(
            List<CompanionField> fields,
            Collection<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {

        Map<String, Set<List<String>>> out = new LinkedHashMap<>();
        for (CompanionField cf : fields) {
            Set<String> values = new LinkedHashSet<>();
            for (WikidataDynamicObject o : pool) {
                if (o != null && cf.className().equals(o.typeName())) {
                    String v = qid(o.get(cf.matchValueField()));
                    if (!v.isEmpty()) {
                        values.add(v);
                    }
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            out.put(cf.key(), CompanionLoader.load(
                    values, cf.propertyPid(), cf.qualifierPid(), client, log));
        }
        return out;
    }

    private static void applyWithSets(
            List<CompanionField> fields,
            Collection<WikidataDynamicObject> pool,
            Map<String, Set<List<String>>> sets,
            GenerationLog log) {

        for (CompanionField cf : fields) {
            Set<List<String>> companions = sets.get(cf.key());
            if (companions == null) {
                continue;
            }
            String subjectField = cf.subjectField().isBlank()
                    ? DEFAULT_SUBJECT_FIELD : cf.subjectField();
            List<WikidataDynamicObject> records = new ArrayList<>();
            for (WikidataDynamicObject o : pool) {
                if (o != null && cf.className().equals(o.typeName())) {
                    records.add(o);
                }
            }
            if (!records.isEmpty()) {
                CompanionMatcher.apply(records, companions, subjectField,
                        cf.matchValueField(), cf.matchRoleField(), cf.fieldName(), log);
            }
        }
    }

    // ---------------- model -> CompanionField list ----------------

    private static List<CompanionField> editableFields(
            GeneratedProjectModel project, GenerationLog log) {

        List<CompanionField> out = new ArrayList<>();
        for (GeneratedClassModel c : allClasses(project)) {
            for (GeneratedFieldModel f : c.fields()) {
                if (f == null || f.mapping() == null
                        || f.mapping().productionKind()
                        != FieldProductionKind.COMPANION_MATCH) {
                    continue;
                }
                FieldSourceMapping m = f.mapping();
                if (!validConfig(m.propertyPid(), m.qualifierPid(),
                        m.matchValueField(), m.matchRoleField(), f.name(), log)) {
                    continue;
                }
                out.add(new CompanionField(c.className(), f.name(),
                        m.propertyPid(), m.qualifierPid(), m.subjectField(),
                        m.matchValueField(), m.matchRoleField()));
            }
        }
        return out;
    }

    private static List<CompanionField> compiledFields(
            CompiledProjectModel project, GenerationLog log) {

        List<CompanionField> out = new ArrayList<>();
        for (CompiledClass c : project.classes()) {
            for (CompiledField f : c.ownFields()) {
                if (f.source().productionKind()
                        != FieldProductionKind.COMPANION_MATCH) {
                    continue;
                }
                CompiledFieldSource m = f.source();
                if (!validConfig(m.propertyPid(), m.qualifierPid(),
                        m.matchValueField(), m.matchRoleField(), f.name(), log)) {
                    continue;
                }
                out.add(new CompanionField(c.className(), f.name(),
                        m.propertyPid(), m.qualifierPid(), m.subjectField(),
                        m.matchValueField(), m.matchRoleField()));
            }
        }
        return out;
    }

    private static boolean validConfig(String propertyPid, String qualifierPid,
                                       String matchValueField, String matchRoleField,
                                       String outcome, GenerationLog log) {
        boolean ok = propertyPid != null && propertyPid.matches("(?i)P\\d+")
                && qualifierPid != null && qualifierPid.matches("(?i)P\\d+")
                && matchValueField != null && !matchValueField.isBlank()
                && matchRoleField != null && !matchRoleField.isBlank();
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
