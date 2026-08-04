package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads a REFERENCED-only class's declared entity-valued property-fields onto its
 * referents — the general form of "give an identity holder its own fields." A
 * referenced-only class (e.g. {@code Nominee}, {@code ForWork}) has no membership
 * of its own and is never extracted as a root, so the normal field pipeline never
 * runs for it; its members only ever appear as referents. This pass closes that
 * gap: declaring {@code Nominee.type} (P31) or {@code ForWork.genre} (P136) makes
 * the field fill from each referent entity's outgoing claim for that property.
 *
 * <p>So <em>declaring the field IS the configuration</em> — nothing hardcoded.
 * Run AFTER {@link ReferentClassStamp} (referents must know their class) and after
 * a value domain / labels pass. Scope: OUTGOING property-fields — ENTITY (labelled
 * refs via {@code wbgetentities}), DATE (a {@link aux.FlexibleDate}) and STRING
 * (the raw literal, both read off the statements' mainsnak) — so a referenced class
 * can carry its own attributes, e.g. a {@code Ceremony} with a {@code year}/{@code
 * date}. Incoming relations are still out of scope. A SINGLE field takes the first
 * value, a COLLECTION keeps all; an already-populated field is left alone.
 */
public final class ReferentFieldLoad {

    private ReferentFieldLoad() {}

    /** @return the number of (referent, field) values loaded. */
    public static int apply(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> pool,
            WikidataApiClient api,
            GenerationLog log) {

        if (model == null || pool == null || api == null) {
            return 0;
        }

        // Referenced-only classes and their entity-valued property-fields.
        Map<String, List<GeneratedFieldModel>> byClass = new LinkedHashMap<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c == null
                    || MembershipPattern.of(c, model) != MembershipPattern.REFERENCED) {
                continue;
            }
            List<GeneratedFieldModel> fields = new ArrayList<>();
            for (GeneratedFieldModel f : c.fields()) {
                if (f != null && loadableType(f.type())
                        && clean(f.mapping().propertyPid()).matches("(?i)P\\d+")) {
                    fields.add(f);
                }
            }
            if (!fields.isEmpty()) {
                byClass.put(c.className(), fields);
            }
        }
        if (byClass.isEmpty()) {
            return 0;
        }

        // Index the referents by their stamped class. Walk the WHOLE reachable
        // graph (entity field values), not just the top-level pool: a referent can
        // exist ONLY nested inside another record — e.g. a Ceremony is a qualifier
        // value (P805) of a Nomination, never an extraction subject, so it never
        // lands in the top-level pool the way Nominee/ForWork (which ARE subjects)
        // do. Flattening finds it regardless of which pool the caller passes.
        Map<String, List<WikidataDynamicObject>> referents = new LinkedHashMap<>();
        for (WikidataDynamicObject o : collectReachable(pool)) {
            if (o == null || o.qid() == null || !WikidataIds.isQid(o.qid())) {
                continue;
            }
            if (byClass.containsKey(o.typeName())) {
                referents.computeIfAbsent(o.typeName(), k -> new ArrayList<>()).add(o);
            }
        }

        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        int loaded = 0;
        for (Map.Entry<String, List<GeneratedFieldModel>> e : byClass.entrySet()) {
            List<WikidataDynamicObject> objs = referents.get(e.getKey());
            if (objs == null || objs.isEmpty()) {
                continue;
            }
            for (GeneratedFieldModel f : e.getValue()) {
                loaded += loadField(model, e.getKey(), objs, f, api, sink);
            }
        }
        return loaded;
    }

    /** Entity refs (outgoing claims), dates and plain strings load onto referents;
     *  other kinds (boolean/auto) aren't a referent property load. */
    private static boolean loadableType(FieldType t) {
        return t == FieldType.ENTITY || t == FieldType.DATE || t == FieldType.STRING;
    }

    private static int loadField(
            GeneratedProjectModel model, String className,
            List<WikidataDynamicObject> objs,
            GeneratedFieldModel field, WikidataApiClient api, GenerationLog log) {
        return field.type() == FieldType.ENTITY
                ? loadEntityField(model, className, objs, field, api, log)
                : loadLiteralField(className, objs, field, api, log);
    }

    /** DATE / STRING: the property's literal value(s) read off the statements
     *  (mainsnak) — a DATE becomes a {@link aux.FlexibleDate}, a STRING the raw
     *  literal. This is what lets a Ceremony carry its own {@code year}/{@code date}. */
    private static int loadLiteralField(
            String className, List<WikidataDynamicObject> objs,
            GeneratedFieldModel field, WikidataApiClient api, GenerationLog log) {

        String pid = clean(field.mapping().propertyPid());
        List<String> qids = new ArrayList<>(objs.size());
        for (WikidataDynamicObject o : objs) {
            qids.add(o.qid());
        }

        Map<String, List<WikidataApiClient.ApiStatement>> stmts;
        try {
            stmts = api.getStatements(qids, pid, List.of(), log::subquery);
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            } else {
                log.message("Referent field load " + className + "." + field.name()
                        + " (" + pid + ") failed (" + ex.getMessage() + ")\n");
            }
            return 0;
        }

        boolean collection = field.cardinality() != null
                && field.cardinality().isCollection();
        boolean date = field.type() == FieldType.DATE;
        int loaded = 0;
        for (WikidataDynamicObject o : objs) {
            if (o.get(field.name()) != null) {
                continue;
            }
            List<WikidataApiClient.ApiStatement> ss = stmts.get(o.qid());
            if (ss == null || ss.isEmpty()) {
                continue;
            }
            List<Object> values = new ArrayList<>();
            for (WikidataApiClient.ApiStatement s : ss) {
                String raw = s.value();
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Object v = date ? aux.FlexibleDate.fromWikidataLiteral(raw) : raw;
                if (v != null) {
                    values.add(v);
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            o.put(field.name(), collection ? values : values.get(0));
            loaded++;
        }
        log.message("Referent field load " + className + "." + field.name()
                + " (" + pid + ") -> " + loaded + " value(s)\n");
        return loaded;
    }

    private static int loadEntityField(
            GeneratedProjectModel model, String className,
            List<WikidataDynamicObject> objs,
            GeneratedFieldModel field, WikidataApiClient api, GenerationLog log) {

        String pid = clean(field.mapping().propertyPid());
        List<String> qids = new ArrayList<>(objs.size());
        for (WikidataDynamicObject o : objs) {
            qids.add(o.qid());
        }

        Map<String, WikidataApiClient.ApiEntity> details;
        try {
            details = api.getEntities(qids, List.of(pid), log::subquery);
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            } else {
                log.message("Referent field load " + className + "." + field.name()
                        + " (" + pid + ") failed (" + ex.getMessage() + ")\n");
            }
            return 0;
        }

        // Label the distinct value entities once.
        Set<String> valueQids = new LinkedHashSet<>();
        for (WikidataDynamicObject o : objs) {
            WikidataApiClient.ApiEntity e = details.get(o.qid());
            if (e != null) {
                valueQids.addAll(e.claim(pid));
            }
        }
        Map<String, WikidataApiClient.ApiEntity> labels;
        try {
            labels = valueQids.isEmpty()
                    ? Map.of()
                    : api.getEntities(new ArrayList<>(valueQids), List.of(), log::subquery);
        } catch (Exception ex) {
            labels = Map.of();
        }

        boolean collection = field.cardinality() != null
                && field.cardinality().isCollection();
        int loaded = 0;
        for (WikidataDynamicObject o : objs) {
            WikidataApiClient.ApiEntity e = details.get(o.qid());
            if (e == null || o.get(field.name()) != null) {
                continue;   // no data, or already populated
            }
            List<WikidataDynamicObject> values = new ArrayList<>();
            for (String vq : e.claim(pid)) {
                WikidataApiClient.ApiEntity le = labels.get(vq);
                String label = le == null || le.label() == null || le.label().isBlank()
                        ? vq : le.label();
                values.add(new WikidataDynamicObject(vq, label));
            }
            if (values.isEmpty()) {
                continue;
            }
            o.put(field.name(), collection ? values : values.get(0));
            loaded++;
        }
        log.message("Referent field load " + className + "." + field.name()
                + " (" + pid + ") -> " + loaded + " value(s)\n");

        // NOTE: the DESCRIPTIVE vocabulary for this field's target (e.g. Nominee.type ->
        // NomineeType) is NOT built here — that is done post-prune from the SERVED pool
        // by DescriptiveVocabularyBuild, so it lists exactly the types that survive
        // (a type whose only bearer was pruned must not linger in the vocabulary).
        return loaded;
    }

    /** All distinct (by identity) {@link WikidataDynamicObject}s reachable from the
     *  roots through entity field values — flattens nested referents into one list so
     *  a qualifier-only referent (never a top-level pool entry) is still indexed. */
    private static List<WikidataDynamicObject> collectReachable(
            Collection<WikidataDynamicObject> roots) {
        Set<WikidataDynamicObject> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<WikidataDynamicObject> queue = new ArrayDeque<>();
        for (WikidataDynamicObject r : roots) {
            if (r != null && seen.add(r)) {
                queue.addLast(r);
            }
        }
        List<WikidataDynamicObject> out = new ArrayList<>(seen.size());
        while (!queue.isEmpty()) {
            WikidataDynamicObject o = queue.pollFirst();   // FIFO: preserve root order
            out.add(o);
            for (Object v : o.dynamicFieldValues().values()) {
                pushReachable(v, seen, queue);
            }
        }
        return out;
    }

    private static void pushReachable(
            Object v, Set<WikidataDynamicObject> seen,
            Deque<WikidataDynamicObject> queue) {
        if (v instanceof WikidataDynamicObject w) {
            if (seen.add(w)) {
                queue.addLast(w);
            }
        } else if (v instanceof Collection<?> c) {
            for (Object item : c) {
                pushReachable(item, seen, queue);
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                pushReachable(item, seen, queue);
            }
        }
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
