package wikidata.explore.extract;

import wikidata.WikidataIds;

import wikidata.explore.extract.WikidataDynamicObject;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers the VALUE VOCABULARY of a property over a population — the distinct,
 * labelled values a property takes across a set of subjects. This is what turns
 * "the type of a nominee" into a first-class vocabulary: sample some nominees, ask
 * for {@code SELECT DISTINCT ?value} of {@code P31} over them, and you get the type
 * set (human, fictional character, …) — the value domain of a {@code type} field,
 * to be materialized as a {@link wikidata.explore.model.VocabularySelection}. Same
 * for {@code genre} (P136) over works.
 *
 * <p>The subject list is a bounded SAMPLE (a vocabulary needs coverage, not every
 * subject), and the value list is itself capped — this is discovery, not a load.
 * Guarded: no property or no subjects means no query, so there is never an
 * unbounded scan.
 */
public final class ValueVocabularyDiscovery {

    /** @return the distinct values of {@code pid} over the sampled subjects, as
     *  labelled objects (qid + label); empty if nothing to ask or the query fails. */
    public List<WikidataDynamicObject> discover(
            Collection<String> subjectQids, String pid,
            int subjectSampleLimit, int valueLimit,
            WikidataSparqlClient sparql, WikidataApiClient api, GenerationLog log) {

        List<WikidataDynamicObject> out = new ArrayList<>();
        if (sparql == null || api == null
                || pid == null || !pid.matches("(?i)P\\d+")) {
            return out;
        }

        Set<String> subjects = new LinkedHashSet<>();
        if (subjectQids != null) {
            for (String q : subjectQids) {
                if (q != null && q.matches("(?i)Q\\d+")) {
                    subjects.add(q.trim());
                    if (subjectSampleLimit > 0 && subjects.size() >= subjectSampleLimit) {
                        break;
                    }
                }
            }
        }
        if (subjects.isEmpty()) {
            return out;   // guard: no bounded population => no query
        }

        String query = buildQuery(subjects, pid, valueLimit);
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        List<String> valueQids = new ArrayList<>();
        try (GenerationLog.Group g = sink.group(
                "Discover value vocabulary: " + pid + " over " + subjects.size()
                        + " subject(s)")) {
            Set<String> seen = new LinkedHashSet<>();
            for (WikidataBinding binding : sparql.query(query)) {
                String v = binding.qid("value");
                if (v != null && WikidataIds.isQid(v) && seen.add(v)) {
                    valueQids.add(v);
                }
            }
            g.subquery("Distinct " + pid + " values", query, valueQids.size() + " values");
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            } else {
                sink.message("Value-vocabulary discovery failed (" + e.getMessage() + ")\n");
            }
            return out;
        }
        if (valueQids.isEmpty()) {
            return out;
        }

        try {
            Map<String, WikidataApiClient.ApiEntity> labels =
                    api.getEntities(valueQids, List.of(), sink.batchSink());
            for (String v : valueQids) {
                WikidataApiClient.ApiEntity e = labels.get(v);
                String label = e == null || e.label() == null || e.label().isBlank()
                        ? v : e.label();
                out.add(new WikidataDynamicObject(v, label));
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            // best effort: fall back to QID-labelled values
            for (String v : valueQids) {
                out.add(new WikidataDynamicObject(v, v));
            }
        }
        return out;
    }

    private static String buildQuery(Set<String> subjects, String pid, int valueLimit) {
        StringBuilder q = new StringBuilder(
                "SELECT DISTINCT ?value WHERE {\n  VALUES ?s {");
        for (String s : subjects) {
            q.append(" wd:").append(s);
        }
        q.append(" }\n  ?s wdt:").append(pid).append(" ?value .\n}");
        if (valueLimit > 0) {
            q.append(" LIMIT ").append(valueLimit);
        }
        return q.toString();
    }
}
