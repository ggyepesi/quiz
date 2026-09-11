package wikidata.explore.transform;

import wikidata.WikidataIds;
import wikidata.explore.model.EntityBound;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import batch.BatchCheckpointStore;
import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.WorkDescriptor;
import batch.WorkUnit;
import wikidata.WikidataBatchFailureClassifier;

/**
 * Discovers the subjects of a POPULATION Selection — the entities that carry the
 * reify's statement property into the value domain — so a reify can draw its
 * subjects from a population instead of the members of a source class.
 *
 * <p>This is the parked direct-discovery loader, revived with a real home and a
 * guard: it refuses to run without a bounded value set (an unbounded
 * {@code ?s wdt:P ?v} membership query would scan all of Wikidata). Discovered
 * objects are stamped {@code entityType} (an internal load type, never a served
 * product) and reuse any already-labelled pool instance by QID.
 */
public final class PopulationSubjectLoader {

    private static final int REVERSE_BATCH_SIZE = 50;
    private work.CancellationToken cancellation = new work.CancellationToken();

    public PopulationSubjectLoader cancellation(work.CancellationToken token) {
        cancellation = token == null ? new work.CancellationToken() : token;
        return this;
    }

    /** @return the NEWLY created subject objects (already added to the caller's
     *  indexes by the caller); an existing pool object is reused, not duplicated. */
    public List<WikidataDynamicObject> discover(
            Collection<WikidataDynamicObject> pool,
            String relationPid,
            Set<String> targetValues,
            String entityType,
            String domainLabel,
            WikidataSparqlClient client,
            GenerationLog log) {
        return discover(pool, relationPid, targetValues, entityType, domainLabel,
                client, log, 0);
    }

    /** Bounded form used by inspection workflows. Zero keeps production's complete
     * discovery semantics; a positive limit is rendered into the remote query. */
    public List<WikidataDynamicObject> discover(
            Collection<WikidataDynamicObject> pool,
            String relationPid,
            Set<String> targetValues,
            String entityType,
            String domainLabel,
            WikidataSparqlClient client,
            GenerationLog log,
            int limit) {
        return discover(pool, relationPid, targetValues, EntityBound.unbounded(),
                entityType, domainLabel, client, log, limit);
    }

    /** As above, additionally bounding which entities may be the SUBJECT. */
    public List<WikidataDynamicObject> discover(
            Collection<WikidataDynamicObject> pool,
            String relationPid,
            Set<String> targetValues,
            EntityBound subjectBound,
            String entityType,
            String domainLabel,
            WikidataSparqlClient client,
            GenerationLog log,
            int limit) {
        return discover(pool, relationPid, targetValues, EntityBound.unbounded(),
                subjectBound, entityType, domainLabel, client, log, limit);
    }

    /** As above, retaining the object's semantic bound. Inspection renders that bound
     * as one readable query; complete generation resolves its object QIDs and reverses
     * the statement through bounded {@code VALUES} batches. */
    public List<WikidataDynamicObject> discover(
            Collection<WikidataDynamicObject> pool,
            String relationPid,
            Set<String> targetValues,
            EntityBound objectBound,
            EntityBound subjectBound,
            String entityType,
            String domainLabel,
            WikidataSparqlClient client,
            GenerationLog log,
            int limit) {

        List<WikidataDynamicObject> created = new ArrayList<>();
        EntityBound objects = objectBound == null
                ? EntityBound.unbounded() : objectBound;
        EntityBound subjects = subjectBound == null
                ? EntityBound.unbounded() : subjectBound;
        boolean objectsBounded = targetValues != null && !targetValues.isEmpty()
                || pinsTheJoin(objects);
        if (client == null
                || relationPid == null || !relationPid.matches("(?i)P\\d+")
                || entityType == null || entityType.isBlank()
                || !(objectsBounded || pinsTheJoin(subjects))) {
            // The guard: refuse unless at least ONE end actually PINS the join, rather
            // than run an all-of-Wikidata membership scan. Asking bounded() was a
            // different question — a vocabulary bound IS bounded and pins nothing here,
            // because only the project can say what is in it.
            return created;
        }

        Map<String, WikidataDynamicObject> known = new LinkedHashMap<>();
        if (pool != null) {
            for (WikidataDynamicObject o : pool) {
                if (o != null && o.qid() != null && WikidataIds.isQid(o.qid())) {
                    known.putIfAbsent(o.qid(), o);
                }
            }
        }

        String label = domainLabel == null || domainLabel.isBlank()
                ? "its value domain" : domainLabel;
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        try (GenerationLog.Group g = sink.group(
                "Discover subjects: " + relationPid + " into " + label)) {
            List<String> subjectQids;
            // Inspection deliberately asks one limited question. Complete production
            // discovery instead resolves a relational object population and reverses
            // the statement over exact VALUES batches; one relation pattern may still
            // denote hundreds of thousands of subjects and is not operationally bounded.
            if (limit > 0) {
                subjectQids = querySubjects(client, g,
                        "Subjects with " + relationPid + " into " + label,
                        buildQuery(relationPid, targetValues, objects, subjects, limit));
            } else {
                List<String> explicitDiscoveryObjects = onlyQids(targetValues);
                boolean hasExactObjectDomain = !explicitDiscoveryObjects.isEmpty()
                        || objects.kind() == EntityBound.Kind.EXPLICIT
                        || objects.kind() == EntityBound.Kind.RELATION;
                List<String> exactObjects = exactDiscoveryObjects(
                        explicitDiscoveryObjects, objects, client, g);
                subjectQids = hasExactObjectDomain
                        ? (exactObjects.isEmpty() ? List.of()
                                : discoverSubjectsInBatches(
                                        client, g, relationPid, exactObjects, subjects))
                        : querySubjects(client, g,
                                "Subjects with " + relationPid + " into " + label,
                                buildQuery(relationPid, targetValues, objects, subjects, 0));
            }
            for (String qid : subjectQids) {
                WikidataDynamicObject o = known.get(qid);
                if (o == null) {
                    o = new WikidataDynamicObject(qid, qid);
                    known.put(qid, o);
                    created.add(o);
                }
                // Stamp the internal load type so QualifierLoader/reify can select it
                // without a modeled source class.
                o.type(entityType);
            }
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            sink.message("Population subject discovery failed ("
                    + e.getMessage() + ")\n");
            // A selected seed can be marked EXPANDED only after its complete reverse
            // adjacency query succeeds. Continuing with an empty population would save
            // a false coverage claim and make that branch disappear from the frontier.
            throw new IllegalStateException(
                    "Required population subject discovery failed for "
                            + relationPid + " into " + label, e);
        }
        return created;
    }

    private List<String> exactDiscoveryObjects(
            List<String> targetValues,
            EntityBound objectBound,
            WikidataSparqlClient client,
            GenerationLog log) throws Exception {
        if (!targetValues.isEmpty()) return targetValues;
        if (objectBound.kind() == EntityBound.Kind.EXPLICIT) {
            return onlyQids(objectBound.qids());
        }
        if (objectBound.kind() != EntityBound.Kind.RELATION) return List.of();
        return RelationalObjectPopulationLoader.load(
                client, objectBound, log, cancellation);
    }

    private List<String> discoverSubjectsInBatches(
            WikidataSparqlClient client,
            GenerationLog log,
            String relationPid,
            List<String> objectQids,
            EntityBound subjectBound) throws Exception {
        List<WorkUnit<List<String>>> units = new ArrayList<>();
        for (int from = 0; from < objectQids.size(); from += REVERSE_BATCH_SIZE) {
            units.add(new ReverseSubjectsUnit(client, relationPid,
                    objectQids.subList(from,
                            Math.min(objectQids.size(), from + REVERSE_BATCH_SIZE)),
                    subjectBound));
        }
        LinkedHashMap<String, String> subjects = new LinkedHashMap<>();
        new BatchExecutor<List<String>>(
                BatchPolicy.defaults().withResume(false),
                log.batchProgress(),
                WikidataBatchFailureClassifier.INSTANCE,
                cancellation,
                BatchCheckpointStore.NONE)
                .run(units, (descriptor, qids) ->
                        qids.forEach(qid -> subjects.putIfAbsent(qid, qid)));
        log.message("Discovered " + subjects.size() + " distinct subject(s) from "
                + objectQids.size() + " resolved object(s) in " + units.size()
                + " initial batch(es).\n");
        return List.copyOf(subjects.keySet());
    }

    private static List<String> querySubjects(
            WikidataSparqlClient client, GenerationLog log,
            String title, String query) throws Exception {
        return queryValues(client, log, title, query, "subject");
    }

    private static List<String> queryValues(
            WikidataSparqlClient client, GenerationLog log,
            String title, String query, String variable) throws Exception {
        GenerationLog.Running running = log.subqueryStarted(title, query);
        try {
            LinkedHashMap<String, String> qids = new LinkedHashMap<>();
            for (WikidataBinding binding : client.query(query)) {
                String qid = binding.qid(variable);
                if (qid != null && WikidataIds.isQid(qid)) qids.putIfAbsent(qid, qid);
            }
            running.done(qids.size() + " " + variable + "(s)");
            return List.copyOf(qids.keySet());
        } catch (Exception failure) {
            running.failed(failure.getMessage());
            throw failure;
        }
    }

    private static List<String> onlyQids(Collection<String> values) {
        LinkedHashMap<String, String> qids = new LinkedHashMap<>();
        if (values != null) {
            for (String value : values) {
                if (WikidataIds.isQid(value)) qids.putIfAbsent(value, value);
            }
        }
        return List.copyOf(qids.keySet());
    }

    static String reverseSubjectsQuery(
            String relationPid, List<String> objectQids, EntityBound subjects) {
        StringBuilder q = new StringBuilder("SELECT DISTINCT ?subject WHERE {\n")
                .append("  VALUES ?value {");
        for (String qid : objectQids) q.append(" wd:").append(qid);
        q.append(" }\n  ?subject wdt:").append(relationPid).append(" ?value .\n");
        appendSubjectBound(q, subjects);
        return q.append("}").toString();
    }

    /**
     * The one emitter for a subject bound, shared by inspection and production.
     *
     * <p>VOCABULARY must throw explicitly: this switch is a statement, so adding an
     * enum kind otherwise still compiles while {@code bounded()} can let an unpinned
     * all-of-Wikidata scan through. A vocabulary is a project reference and must have
     * been resolved before reaching this datasource operation.
     */
    private static void appendSubjectBound(StringBuilder q, EntityBound subjects) {
        EntityBound bound = subjects == null ? EntityBound.unbounded() : subjects;
        switch (bound.kind()) {
            case EXPLICIT -> {
                q.append("  VALUES ?subject {");
                for (String qid : bound.qids()) q.append(" wd:").append(qid);
                q.append(" }\n");
            }
            case RELATION -> {
                // Descendants are P279* on the TARGET: instances of Q5 or any
                // subclass of it remain one relational bound.
                q.append("  ?subject wdt:").append(bound.relationPid())
                        .append(bound.includeDescendants() ? "/wdt:P279* " : " ")
                        .append("?subjectKind .\n  VALUES ?subjectKind {");
                for (String qid : bound.qids()) q.append(" wd:").append(qid);
                q.append(" }\n");
            }
            case UNBOUNDED -> { }
            case VOCABULARY -> throw new IllegalStateException(
                    "Subject vocabulary bound '" + bound.selectionName()
                            + "' reached the loader unresolved");
        }
    }

    private static final class ReverseSubjectsUnit implements WorkUnit<List<String>> {
        private final WikidataSparqlClient client;
        private final String relationPid;
        private final List<String> objectQids;
        private final EntityBound subjectBound;
        private final WorkDescriptor descriptor;

        private ReverseSubjectsUnit(
                WikidataSparqlClient client, String relationPid,
                List<String> objectQids, EntityBound subjectBound) {
            this.client = client;
            this.relationPid = relationPid;
            this.objectQids = List.copyOf(objectQids);
            this.subjectBound = subjectBound == null
                    ? EntityBound.unbounded() : subjectBound;
            String ids = String.join(",", this.objectQids);
            descriptor = new WorkDescriptor(
                    "population-subject-reverse",
                    relationPid + ":" + ids,
                    relationPid + " reverse subjects for " + this.objectQids.size()
                            + " object(s)",
                    Map.of("property", relationPid, "objects", ids));
        }

        @Override public WorkDescriptor descriptor() { return descriptor; }
        @Override public String request() {
            return reverseSubjectsQuery(relationPid, objectQids, subjectBound);
        }
        @Override public List<String> execute() throws Exception {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (WikidataBinding binding : client.query(request())) {
                String qid = binding.qid("subject");
                if (qid != null && WikidataIds.isQid(qid)) result.putIfAbsent(qid, qid);
            }
            return List.copyOf(result.keySet());
        }
        @Override public List<? extends WorkUnit<List<String>>> split() {
            if (objectQids.size() < 2) return List.of();
            int middle = objectQids.size() / 2;
            return List.of(
                    new ReverseSubjectsUnit(client, relationPid,
                            objectQids.subList(0, middle), subjectBound),
                    new ReverseSubjectsUnit(client, relationPid,
                            objectQids.subList(middle, objectQids.size()), subjectBound));
        }
    }

    /**
     * Both ends of the join, pinned as far as each is bounded.
     *
     * <p>R16: a join anchored on one side only spans every subject of the property in
     * Wikidata, soft-times-out, and returns a different partial row set each run. The
     * object side has always been pinned here; the subject side could not be, because
     * the model had no way to say which entities may be subjects. It does now, and an
     * unbounded subject simply contributes no pattern — the query is exactly what it
     * was before.
     */
    /** Whether this bound contributes a pattern to the query — which is what stops the
     *  scan, and is not the same as merely being configured. */
    private static boolean pinsTheJoin(EntityBound bound) {
        return bound.kind() == EntityBound.Kind.EXPLICIT
                || bound.kind() == EntityBound.Kind.RELATION;
    }

    static String buildQuery(
            String relationPid, Set<String> targetValues, EntityBound subjects, int limit) {
        return buildQuery(relationPid, targetValues, EntityBound.unbounded(),
                subjects, limit);
    }

    static String buildQuery(
            String relationPid, Set<String> targetValues, EntityBound objects,
            EntityBound subjects, int limit) {
        StringBuilder q = new StringBuilder(
                "SELECT DISTINCT ?subject WHERE {\n  ?subject wdt:")
                .append(relationPid).append(" ?value .\n");
        EntityBound objectBound = objects == null ? EntityBound.unbounded() : objects;
        switch (objectBound.kind()) {
            case RELATION -> {
                q.append("  ?value wdt:").append(objectBound.relationPid())
                        .append(objectBound.includeDescendants()
                                ? "/wdt:P279* " : " ")
                        .append("?valueKind .\n  VALUES ?valueKind {");
                for (String qid : objectBound.qids()) q.append(" wd:").append(qid);
                q.append(" }\n");
            }
            case EXPLICIT, UNBOUNDED -> { }
            case VOCABULARY -> throw new IllegalStateException(
                    "Object vocabulary bound '" + objectBound.selectionName()
                            + "' reached the loader unresolved");
        }
        appendSubjectBound(q, subjects);
        if (objectBound.kind() != EntityBound.Kind.RELATION
                && targetValues != null && !targetValues.isEmpty()) {
            q.append("  VALUES ?value {");
            for (String qid : targetValues) {
                if (qid != null && qid.matches("(?i)Q\\d+")) {
                    q.append(" wd:").append(qid);
                }
            }
            q.append(" }\n");
        }
        q.append("}");
        if (limit > 0) q.append("\nLIMIT ").append(limit);
        return q.toString();
    }
}
