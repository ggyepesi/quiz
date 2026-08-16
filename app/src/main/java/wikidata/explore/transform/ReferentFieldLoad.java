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
 * Loads a REFERENCED-only or OWNED_COMPONENT class's declared property-fields onto
 * its instances — the general form of "give a non-root identity holder its own fields." A
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

    /** What a field load did: values assigned, and the entities it could NOT reach.
     *  Not a pass/fail flag — a load over thousands of entities normally answers for
     *  almost all of them, and throwing that away because one batch of 50 was
     *  unreachable both mis-states what is missing and makes the next run re-ask for
     *  everything. An entity that answered is covered even when it has no such
     *  property; only an unreached one is unresolved. */
    private record LoadOutcome(int loaded, Set<String> unavailable) {
        LoadOutcome {
            unavailable = unavailable == null ? Set.of() : Set.copyOf(unavailable);
        }
        boolean completed() { return unavailable.isEmpty(); }
        static LoadOutcome completed(int loaded) { return new LoadOutcome(loaded, Set.of()); }
    }

    private record EntityFieldBatch(
            Map<String, WikidataApiClient.ApiEntity> entities,
            Map<String, WikidataApiClient.ApiEntity> labels,
            Set<String> unavailable) {
        EntityFieldBatch {
            unavailable = unavailable == null ? Set.of() : Set.copyOf(unavailable);
        }
        static EntityFieldBatch unreached(Collection<String> qids) {
            return new EntityFieldBatch(Map.of(), Map.of(), new LinkedHashSet<>(qids));
        }
    }

    private record LiteralFieldBatch(
            Map<String, Map<String, List<WikidataApiClient.ApiStatement>>> statements,
            Set<String> unavailable) {
        LiteralFieldBatch {
            unavailable = unavailable == null ? Set.of() : Set.copyOf(unavailable);
        }
        static LiteralFieldBatch unreached(Collection<String> qids) {
            return new LiteralFieldBatch(Map.of(), new LinkedHashSet<>(qids));
        }
    }

    /** What a load did: values assigned, and the declarations it COMPLETED — the ones a
     *  later run can skip entirely rather than re-asking for the entities that had no
     *  answer. */
    public record Result(
            int loaded,
            java.util.List<wikidata.explore.extract.LoadedDeclaration> completed,
            java.util.List<wikidata.explore.extract.LoadedDeclaration> failed) {
        public Result(int loaded,
                      java.util.List<wikidata.explore.extract.LoadedDeclaration> completed) {
            this(loaded, completed, java.util.List.of());
        }

        public Result {
            completed = completed == null ? java.util.List.of() : List.copyOf(completed);
            failed = failed == null ? java.util.List.of() : List.copyOf(failed);
        }
    }

    private ReferentFieldLoad() {}

    /** @return the number of (referent, field) values loaded. */
    public static int apply(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> pool,
            WikidataApiClient api,
            GenerationLog log) {
        return load(model, pool, api, log, java.util.List.of()).loaded();
    }

    /**
     * As {@link #apply}, skipping declarations a previous run already completed.
     *
     * @param alreadyLoaded exact declaration/QID coverage recorded by the snapshot
     */
    public static Result load(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> pool,
            WikidataApiClient api,
            GenerationLog log,
            Collection<wikidata.explore.extract.LoadedDeclaration> alreadyLoaded) {

        return load(model, pool, api, log, alreadyLoaded, false);
    }

    public static Result load(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> pool,
            WikidataApiClient api,
            GenerationLog log,
            Collection<wikidata.explore.extract.LoadedDeclaration> alreadyLoaded,
            boolean deferLabels) {

        if (model == null || pool == null || api == null) {
            return new Result(0, java.util.List.of());
        }
        Map<String, wikidata.explore.extract.LoadedDeclaration> known = new LinkedHashMap<>();
        if (alreadyLoaded != null) {
            for (wikidata.explore.extract.LoadedDeclaration d : alreadyLoaded) {
                if (d != null) known.put(d.key(), d);
            }
        }

        // Classes never extracted as roots, and their declared property-fields.
        Map<String, List<GeneratedFieldModel>> byClass = new LinkedHashMap<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c == null || !loadsHere(MembershipPattern.of(c, model))) {
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
            return new Result(0, java.util.List.of());
        }

        // Index the referents by every direct class membership. Walk the WHOLE reachable
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
            for (String className : o.directClassNames()) {
                if (byClass.containsKey(className)) {
                    referents.computeIfAbsent(className, k -> new ArrayList<>()).add(o);
                }
            }
        }

        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        int loaded = 0;
        List<wikidata.explore.extract.LoadedDeclaration> completed = new ArrayList<>();
        List<wikidata.explore.extract.LoadedDeclaration> failed = new ArrayList<>();
        for (Map.Entry<String, List<GeneratedFieldModel>> e : byClass.entrySet()) {
            List<WikidataDynamicObject> objs = referents.get(e.getKey());
            if (objs == null || objs.isEmpty()) {
                continue;
            }
            EntityFieldBatch entityBatch = loadEntityFields(
                    e.getKey(), e.getValue(), objs, known, api, sink, deferLabels);
            LiteralFieldBatch literalBatch = loadLiteralFields(
                    e.getKey(), e.getValue(), objs, known, api, sink);
            for (GeneratedFieldModel f : e.getValue()) {
                String pid = clean(f.mapping().propertyPid());
                String key = wikidata.explore.extract.LoadedDeclaration.key(
                        e.getKey(), f.name(), pid);
                wikidata.explore.extract.LoadedDeclaration done = known.get(key);
                Set<String> coveredQids = done == null
                        ? Set.of() : new LinkedHashSet<>(done.coveredQids());
                Set<String> currentQids = new LinkedHashSet<>();
                for (WikidataDynamicObject obj : objs) currentQids.add(obj.qid());
                if (!coveredQids.isEmpty() && coveredQids.containsAll(currentQids)) {
                    sink.message("Referent field " + e.getKey() + "." + f.name()
                            + " (" + pid + ") already loaded for " + currentQids.size()
                            + " entities — skipped.\n");
                    completed.add(new wikidata.explore.extract.LoadedDeclaration(
                            e.getKey(), f.name(), pid, currentQids));
                    continue;
                }
                List<WikidataDynamicObject> uncovered = objs.stream()
                        .filter(obj -> !coveredQids.contains(obj.qid()))
                        .toList();
                LoadOutcome outcome = loadField(
                        e.getKey(), uncovered, f, sink, entityBatch, literalBatch);
                loaded += outcome.loaded();
                // Coverage is per ENTITY, not per declaration: everything asked about
                // that an answer came back for is covered — including an entity that
                // simply has no such property — and only the entities no batch reached
                // stay unresolved. Reporting the whole declaration as unresolved because
                // one batch of 50 failed both overstated what is missing (4,972 entities
                // named for ~150 real failures) and made the next run re-ask for all of
                // it.
                Set<String> nowCovered = new LinkedHashSet<>(currentQids);
                nowCovered.removeAll(outcome.unavailable());
                if (!nowCovered.isEmpty()) {
                    completed.add(new wikidata.explore.extract.LoadedDeclaration(
                            e.getKey(), f.name(), pid, nowCovered));
                } else if (done != null) {
                    // Nothing new was reached; keep the coverage an earlier run earned.
                    completed.add(done);
                }
                if (!outcome.completed()) {
                    failed.add(new wikidata.explore.extract.LoadedDeclaration(
                            e.getKey(), f.name(), pid, outcome.unavailable()));
                }
            }
        }
        return new Result(loaded, List.copyOf(completed), List.copyOf(failed));
    }

    /**
     * The membership patterns whose members are never extracted as roots, so the normal
     * field pipeline never runs for them and their declared properties would otherwise
     * never load.
     *
     * <p>REFERENCED members appear only as the value-end of a field; an OWNED_COMPONENT
     * is produced per owner; and an EVIDENCE_KIND is STAMPED from P31 evidence rather
     * than queried — its members are pool entities with real QIDs and no root query of
     * their own. Declaring {@code Person.birthDate (P569)} used to validate, discover,
     * and then silently load nothing.
     */
    private static boolean loadsHere(MembershipPattern pattern) {
        return pattern == MembershipPattern.REFERENCED
                || pattern == MembershipPattern.OWNED_COMPONENT
                || pattern == MembershipPattern.EVIDENCE_KIND;
    }

    /** Entity refs (outgoing claims), dates and plain strings load onto referents;
     *  other kinds (boolean/auto) aren't a referent property load. */
    private static boolean loadableType(FieldType t) {
        return t == FieldType.ENTITY || t == FieldType.DATE || t == FieldType.STRING;
    }

    private static LoadOutcome loadField(
            String className,
            List<WikidataDynamicObject> objs,
            GeneratedFieldModel field, GenerationLog log,
            EntityFieldBatch entityBatch, LiteralFieldBatch literalBatch) {
        return field.type() == FieldType.ENTITY
                ? loadEntityField(className, objs, field, log, entityBatch)
                : loadLiteralField(className, objs, field, log, literalBatch);
    }

    /** Fetch all entity-valued sibling fields together. wbgetentities returns the
     * complete claims document for an entity, so asking once per PID only downloads
     * the same body repeatedly (notably Name.givenName + Name.familyName). */
    private static EntityFieldBatch loadEntityFields(
            String className, List<GeneratedFieldModel> fields,
            List<WikidataDynamicObject> objs,
            Map<String, wikidata.explore.extract.LoadedDeclaration> known,
            WikidataApiClient api, GenerationLog log, boolean deferLabels) {
        Set<String> qids = new LinkedHashSet<>();
        Set<String> pids = new LinkedHashSet<>();
        for (GeneratedFieldModel field : fields) {
            if (field.type() != FieldType.ENTITY) continue;
            String pid = clean(field.mapping().propertyPid());
            wikidata.explore.extract.LoadedDeclaration done = known.get(
                    wikidata.explore.extract.LoadedDeclaration.key(
                            className, field.name(), pid));
            Set<String> covered = done == null ? Set.of()
                    : new LinkedHashSet<>(done.coveredQids());
            for (WikidataDynamicObject obj : objs) {
                if (!covered.contains(obj.qid()) && obj.get(field.name()) == null) {
                    qids.add(obj.qid());
                    pids.add(pid);
                }
            }
        }
        if (qids.isEmpty()) {
            return new EntityFieldBatch(Map.of(), Map.of(), Set.of());
        }

        Map<String, WikidataApiClient.ApiEntity> details;
        Set<String> unavailable;
        try (GenerationLog.Group group = log.group("Load " + pids.size()
                + " referent fields on " + className + " for " + qids.size()
                + " entities (" + String.join(", ", pids) + ")")) {
            // Partial, not all-or-nothing: what the reachable batches answered is real
            // data, and an entity that answered without the property genuinely lacks it.
            // Only the entities no batch reached are unresolved.
            WikidataApiClient.PartialEntities partial = api.getEntityClaimsPartial(
                    new ArrayList<>(qids), new ArrayList<>(pids), group.batchSink());
            details = partial.entities();
            unavailable = new LinkedHashSet<>(partial.unavailableQids());
            if (!unavailable.isEmpty()) {
                log.message("Referent entity fields on " + className + ": "
                        + unavailable.size() + " of " + qids.size()
                        + " entities unreachable in " + partial.failedBatches()
                        + " batch(es); the rest were loaded.\n");
            }
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "Referent field load interrupted");
            }
            log.message("Referent entity fields on " + className + " failed ("
                    + ex.getMessage() + ")\n");
            return EntityFieldBatch.unreached(qids);
        }

        Set<String> valueQids = new LinkedHashSet<>();
        for (WikidataApiClient.ApiEntity entity : details.values()) {
            for (String pid : pids) valueQids.addAll(entity.claim(pid));
        }
        Map<String, WikidataApiClient.ApiEntity> labels;
        if (deferLabels) {
            return new EntityFieldBatch(details, Map.of(), unavailable);
        }
        try (GenerationLog.Group group = log.group("Resolve " + valueQids.size()
                + " value label(s) for " + className)) {
            labels = valueQids.isEmpty() ? Map.of()
                    : api.getEntities(new ArrayList<>(valueQids), List.of(),
                    group.batchSink());
        } catch (Exception ex) {
            labels = Map.of();
        }
        return new EntityFieldBatch(details, labels, unavailable);
    }

    /** Fetch DATE/STRING siblings from one claims body, just as entity-valued sibling
     * fields are grouped above. Parsing remains per PID and assignment per field. */
    private static LiteralFieldBatch loadLiteralFields(
            String className, List<GeneratedFieldModel> fields,
            List<WikidataDynamicObject> objs,
            Map<String, wikidata.explore.extract.LoadedDeclaration> known,
            WikidataApiClient api, GenerationLog log) {
        Set<String> qids = new LinkedHashSet<>();
        Set<String> pids = new LinkedHashSet<>();
        for (GeneratedFieldModel field : fields) {
            if (field.type() != FieldType.DATE && field.type() != FieldType.STRING) continue;
            String pid = clean(field.mapping().propertyPid());
            wikidata.explore.extract.LoadedDeclaration done = known.get(
                    wikidata.explore.extract.LoadedDeclaration.key(
                            className, field.name(), pid));
            Set<String> covered = done == null ? Set.of()
                    : new LinkedHashSet<>(done.coveredQids());
            for (WikidataDynamicObject obj : objs) {
                if (!covered.contains(obj.qid()) && obj.get(field.name()) == null) {
                    qids.add(obj.qid());
                    pids.add(pid);
                }
            }
        }
        if (qids.isEmpty()) return new LiteralFieldBatch(Map.of(), Set.of());

        try (GenerationLog.Group group = log.group("Load " + pids.size()
                + " literal referent fields on " + className + " for " + qids.size()
                + " entities (" + String.join(", ", pids) + ")")) {
            // Partial, for the same reason as the entity batch above: the entities a
            // reachable batch answered for are loaded and covered; only unreached ones
            // stay unresolved.
            WikidataApiClient.PartialStatements partial =
                    api.getStatementsByPropertyPartial(
                            new ArrayList<>(qids), new ArrayList<>(pids), group.batchSink());
            if (!partial.unavailableQids().isEmpty()) {
                log.message("Referent literal fields on " + className + ": "
                        + partial.unavailableQids().size() + " of " + qids.size()
                        + " entities unreachable in " + partial.failedBatches()
                        + " batch(es); the rest were loaded.\n");
            }
            return new LiteralFieldBatch(partial.statements(),
                    new LinkedHashSet<>(partial.unavailableQids()));
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "Referent field load interrupted");
            }
            log.message("Referent literal fields on " + className + " failed ("
                    + ex.getMessage() + ")\n");
            return LiteralFieldBatch.unreached(qids);
        }
    }

    /** The entities of THIS field that no batch reached — the class-wide unavailable
     *  set narrowed to the ones this field actually asked about. */
    private static Set<String> unreachedOf(
            Collection<String> asked, Set<String> unavailable) {
        if (unavailable.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>(asked);
        out.retainAll(unavailable);
        return out;
    }

    /** The entities still MISSING this field. A populated field is left alone at
     *  assignment, so fetching for it downloads a property that is already known and
     *  throws the answer away — on a re-run over a whole domain that is most of the
     *  requests. Filtering here makes the cost proportional to what is missing. */
    private static List<String> qidsMissing(
            List<WikidataDynamicObject> objs, GeneratedFieldModel field) {
        List<String> qids = new ArrayList<>(objs.size());
        for (WikidataDynamicObject o : objs) {
            if (o.get(field.name()) == null) {
                qids.add(o.qid());
            }
        }
        return qids;
    }

    /** DATE / STRING: the property's literal value(s) read off the statements
     *  (mainsnak) — a DATE becomes a {@link aux.FlexibleDate}, a STRING the raw
     *  literal. This is what lets a Ceremony carry its own {@code year}/{@code date}. */
    private static LoadOutcome loadLiteralField(
            String className, List<WikidataDynamicObject> objs,
            GeneratedFieldModel field, GenerationLog log, LiteralFieldBatch batch) {

        String pid = clean(field.mapping().propertyPid());
        List<String> qids = qidsMissing(objs, field);
        if (qids.isEmpty()) {
            return LoadOutcome.completed(0);
        }

        Map<String, List<WikidataApiClient.ApiStatement>> stmts =
                batch.statements().getOrDefault(pid, Map.of());
        Set<String> unreached = unreachedOf(qids, batch.unavailable());

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
        return new LoadOutcome(loaded, unreached);
    }

    private static LoadOutcome loadEntityField(
            String className,
            List<WikidataDynamicObject> objs,
            GeneratedFieldModel field, GenerationLog log,
            EntityFieldBatch batch) {

        String pid = clean(field.mapping().propertyPid());
        List<String> qids = qidsMissing(objs, field);
        if (qids.isEmpty()) {
            return LoadOutcome.completed(0);
        }

        Set<String> unreached = unreachedOf(qids, batch.unavailable());

        boolean collection = field.cardinality() != null
                && field.cardinality().isCollection();
        int loaded = 0;
        for (WikidataDynamicObject o : objs) {
            WikidataApiClient.ApiEntity e = batch.entities().get(o.qid());
            if (e == null || o.get(field.name()) != null) {
                continue;   // no data, or already populated
            }
            List<WikidataDynamicObject> values = new ArrayList<>();
            for (String vq : e.claim(pid)) {
                WikidataApiClient.ApiEntity le = batch.labels().get(vq);
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
        return new LoadOutcome(loaded, unreached);
    }

    /** Delegates to the one graph walk the snapshot writer also uses, so a field load
     *  sees exactly the objects that will be saved. */
    private static List<WikidataDynamicObject> collectReachable(
            Collection<WikidataDynamicObject> roots) {
        return wikidata.explore.extract.WikidataObjectGraph.reachable(roots);
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
