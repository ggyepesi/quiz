package wikidata.explore.extract;

import wikidata.api.WikidataApiClient;
import wikidata.explore.model.Selection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link Selection}'s CONTENT to displayable objects — the members you can
 * browse even though a Selection is never a served product. This is what keeps a
 * Selection's content inspectable: a vocabulary genuinely IS its values, so you can
 * see its members (labelled) in the workbench without it being a class.
 *
 * <p>The same resolution feeds the later slices — the reify value constraint reads
 * the QID set, and reference rendering reads the labels.
 */
public final class SelectionContentResolver {

    /**
     * A VOCABULARY's explicit value QIDs resolved to labelled objects via
     * {@code wbgetentities}. Returns an empty list when there's nothing to resolve
     * (or the fetch fails); a type-only vocabulary — one bounded solely by a P31
     * filter — is left to a later slice (it needs a query, not a QID lookup).
     */
    public List<WikidataDynamicObject> resolve(
            Selection selection, WikidataApiClient api, GenerationLog log) {

        List<WikidataDynamicObject> out = new ArrayList<>();
        if (selection == null || api == null) {
            return out;
        }
        List<String> qids = new ArrayList<>();
        for (String q : selection.valueQids()) {
            if (q != null && q.matches("(?i)Q\\d+")) {
                qids.add(q);
            }
        }
        if (qids.isEmpty()) {
            return out;
        }

        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
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
                sink.message("Selection \"" + selection.name()
                        + "\" content resolution failed (" + ex.getMessage() + ")\n");
            }
        }
        return out;
    }
}
