package wikidata.explore.extract;

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
 * IS its explicitly saved instance identities, so those same members are browsable too.
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
     *   <li>{@link PopulationSelection} — its explicit instance QIDs, labelled through
     *       the same entity lookup as a vocabulary.</li>
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
            return labelled(population.instanceQids(), api, selection.name(), sink);
        }
        return new ArrayList<>();
    }

    /**
     * Thin overload without a SPARQL client: both explicit selection kinds resolve.
     */
    public List<WikidataDynamicObject> resolve(
            Selection selection, WikidataApiClient api, GenerationLog log) {
        return resolve(selection, null, api, 0, log);
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
                    api.getEntities(qids, List.of(), sink.batchSink());
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

}
