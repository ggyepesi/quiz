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

        List<WikidataDynamicObject> created = new ArrayList<>();
        EntityBound subjects = subjectBound == null
                ? EntityBound.unbounded() : subjectBound;
        boolean objectsBounded = targetValues != null && !targetValues.isEmpty();
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
        String query = buildQuery(relationPid, targetValues, subjects, limit);
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        try (GenerationLog.Group g = sink.group(
                "Discover subjects: " + relationPid + " into " + label)) {
            String title = "Subjects with " + relationPid + " into " + label;
            GenerationLog.Running running = g.subqueryStarted(title, query);
            int rows = 0;
            try {
                for (WikidataBinding binding : client.query(query)) {
                    String qid = binding.qid("subject");
                    if (qid == null || !WikidataIds.isQid(qid)) {
                        continue;
                    }
                    rows++;
                    WikidataDynamicObject o = known.get(qid);
                    if (o == null) {
                        o = new WikidataDynamicObject(qid, qid);
                        // A directly discovered subject is still a Wikidata entity. The
                        // ordinary class loader seeds this source link while constructing
                        // its objects; omitting it here made the same Person lose the
                        // Wikidata affordance solely because P39 discovered it.
                        o.put("wikidata", o.wikidataUrl());
                        known.put(qid, o);
                        created.add(o);
                    }
                    // Stamp the internal load type so QualifierLoader/reify can select it
                    // without a modeled source class.
                    o.type(entityType);
                }
                running.done(rows + " subjects");
            } catch (Exception failure) {
                running.failed(failure.getMessage());
                throw failure;
            }
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
        StringBuilder q = new StringBuilder(
                "SELECT DISTINCT ?subject WHERE {\n  ?subject wdt:")
                .append(relationPid).append(" ?value .\n");
        EntityBound subjectBound = subjects == null ? EntityBound.unbounded() : subjects;
        switch (subjectBound.kind()) {
            case EXPLICIT -> {
                q.append("  VALUES ?subject {");
                for (String qid : subjectBound.qids()) q.append(" wd:").append(qid);
                q.append(" }\n");
            }
            case RELATION -> {
                // Descendants are P279* on the TARGET, so "instances of Q5 or any
                // subclass of it" is one pattern rather than a pre-expanded QID list.
                q.append("  ?subject wdt:").append(subjectBound.relationPid())
                 .append(subjectBound.includeDescendants() ? "/wdt:P279* " : " ")
                 .append("?subjectKind .\n  VALUES ?subjectKind {");
                for (String qid : subjectBound.qids()) q.append(" wd:").append(qid);
                q.append(" }\n");
            }
            case UNBOUNDED -> { }
            // A vocabulary is a REFERENCE and only the project can resolve it, so it
            // must never arrive here. It could: this switch is a STATEMENT, not an
            // expression, so adding the kind compiled without covering it and the
            // missing case emitted no pattern at all — while bounded() still answered
            // true, so the guard let the query run unpinned. That is the
            // all-of-Wikidata scan the guard exists to prevent, produced by the
            // mechanism meant to prevent it.
            case VOCABULARY -> throw new IllegalStateException(
                    "Subject vocabulary bound '" + subjectBound.selectionName()
                            + "' reached the loader unresolved");
        }
        if (targetValues != null && !targetValues.isEmpty()) {
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
