package wikidata.explore.extract;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.Selection;
import wikidata.explore.model.VocabularySelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link Selection}'s CONTENT to displayable objects — the members you can
 * browse even though a Selection is never a served product. This is what keeps a
 * Selection's content inspectable: a vocabulary genuinely IS its values, so you can
 * see its members (labelled) in the workbench without it being a class; a population
 * IS the subjects its relation selects, so a bounded sample of them is browsable too.
 *
 * <p>The same resolution feeds the later slices — the reify value constraint reads
 * the QID set, and reference rendering reads the labels.
 */
public final class SelectionContentResolver {

    /**
     * Content-resolves a Selection, dispatching on its concrete subtype:
     *
     * <ul>
     *   <li>{@link VocabularySelection} — its explicit value QIDs resolved to labelled
     *       objects via {@code wbgetentities}. A type-only vocabulary (bounded solely
     *       by a P31 filter) resolves to nothing here (it needs a query, not a QID
     *       lookup).</li>
     *   <li>{@link PopulationSelection} — a BOUNDED SPARQL sample of the subjects that
     *       carry its relation into its targets, then labelled. Requires a {@code Pxxx}
     *       relation AND at least one {@code Qxxx} target and a non-null {@code sparql}
     *       client — otherwise empty (no unbounded all-of-Wikidata scan).</li>
     * </ul>
     *
     * @param limit the maximum number of population subjects to sample (ignored for a
     *              vocabulary); non-positive means no bounded query is issued.
     */
    public List<WikidataDynamicObject> resolve(
            Selection selection,
            WikidataSparqlClient sparql,
            WikidataApiClient api,
            int limit,
            GenerationLog log) {

        if (selection == null || api == null) {
            return new ArrayList<>();
        }
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;

        if (selection instanceof VocabularySelection vocab) {
            return labelled(vocab.valueQids(), api, selection.name(), sink);
        }
        if (selection instanceof PopulationSelection population) {
            return resolvePopulation(population, sparql, api, limit, sink);
        }
        return new ArrayList<>();
    }

    /**
     * Thin overload without a SPARQL client: a {@link VocabularySelection} still
     * resolves; a {@link PopulationSelection} returns empty (it needs the client for
     * its bounded query).
     */
    public List<WikidataDynamicObject> resolve(
            Selection selection, WikidataApiClient api, GenerationLog log) {
        return resolve(selection, null, api, 0, log);
    }

    private List<WikidataDynamicObject> resolvePopulation(
            PopulationSelection population,
            WikidataSparqlClient sparql,
            WikidataApiClient api,
            int limit,
            GenerationLog sink) {

        List<WikidataDynamicObject> out = new ArrayList<>();
        String relationPid = population.relationPid();
        List<String> targets = new ArrayList<>();
        for (String q : population.targetQids()) {
            if (q != null && q.matches("(?i)Q\\d+")) {
                targets.add(q);
            }
        }

        // Guard: a bounded query needs a relation, at least one target and a client.
        // Otherwise we'd scan all of Wikidata (or have nothing to run at all).
        if (sparql == null
                || limit <= 0
                || !relationPid.matches("(?i)P\\d+")
                || targets.isEmpty()) {
            return out;
        }

        List<String> qids = new ArrayList<>();
        String query = buildPopulationQuery(relationPid, targets, limit);
        try {
            for (WikidataBinding binding : sparql.query(query)) {
                String qid = binding.qid("subject");
                if (qid != null && qid.matches("Q\\d+") && !qids.contains(qid)) {
                    qids.add(qid);
                }
            }
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            } else {
                sink.message("Selection \"" + population.name()
                        + "\" subject sampling failed (" + ex.getMessage() + ")\n");
            }
            return out;
        }

        return labelled(qids, api, population.name(), sink);
    }

    /** Labels a list of QIDs via {@code wbgetentities}, in the given order,
     *  falling back to the QID when a label is missing or the fetch fails. */
    private List<WikidataDynamicObject> labelled(
            List<String> rawQids,
            WikidataApiClient api,
            String selectionName,
            GenerationLog sink) {

        List<WikidataDynamicObject> out = new ArrayList<>();
        List<String> qids = new ArrayList<>();
        for (String q : rawQids) {
            if (q != null && q.matches("(?i)Q\\d+")) {
                qids.add(q);
            }
        }
        if (qids.isEmpty()) {
            return out;
        }
        try {
            Map<String, WikidataApiClient.ApiEntity> details =
                    api.getEntities(qids, List.of());
            for (String qid : qids) {
                WikidataApiClient.ApiEntity e = details.get(qid);
                String label = e == null || e.label() == null || e.label().isBlank()
                        ? qid : e.label();
                out.add(new WikidataDynamicObject(qid, label));
            }
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            } else {
                sink.message("Selection \"" + selectionName
                        + "\" content resolution failed (" + ex.getMessage() + ")\n");
            }
        }
        return out;
    }

    private static String buildPopulationQuery(
            String relationPid, List<String> targets, int limit) {
        StringBuilder q = new StringBuilder(
                "SELECT DISTINCT ?subject WHERE {\n  ?subject wdt:")
                .append(relationPid).append(" ?value .\n  VALUES ?value {");
        for (String qid : targets) {
            q.append(" wd:").append(qid);
        }
        q.append(" }\n} LIMIT ").append(limit);
        return q.toString();
    }
}
