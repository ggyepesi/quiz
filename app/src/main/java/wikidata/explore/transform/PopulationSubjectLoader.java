package wikidata.explore.transform;

import wikidata.WikidataIds;

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

        List<WikidataDynamicObject> created = new ArrayList<>();
        if (client == null
                || relationPid == null || !relationPid.matches("(?i)P\\d+")
                || targetValues == null || targetValues.isEmpty()
                || entityType == null || entityType.isBlank()) {
            // The guard: no bounded value set (or no relation) => refuse, rather
            // than run an all-of-Wikidata membership scan.
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
        String query = buildQuery(relationPid, targetValues);
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        try (GenerationLog.Group g = sink.group(
                "Discover subjects: " + relationPid + " into " + label)) {
            int rows = 0;
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
            g.subquery("Subjects with " + relationPid + " into " + label,
                    query, rows + " subjects");
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

    private static String buildQuery(String relationPid, Set<String> targetValues) {
        StringBuilder q = new StringBuilder(
                "SELECT DISTINCT ?subject WHERE {\n  ?subject wdt:")
                .append(relationPid).append(" ?value .\n  VALUES ?value {");
        for (String qid : targetValues) {
            if (qid != null && qid.matches("(?i)Q\\d+")) {
                q.append(" wd:").append(qid);
            }
        }
        q.append(" }\n}");
        return q.toString();
    }
}
