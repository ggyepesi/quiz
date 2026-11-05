package wikidata.explore.generation;

import wikidata.WikidataIds;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves the one retained Wikidata-QID to English-Wikipedia-article correspondence. */
final class WikipediaArticleCorrespondence {
    private WikipediaArticleCorrespondence() { }

    static Map<String, List<WikidataDynamicObject>> byTitle(
            Collection<WikidataDynamicObject> values,
            wikidata.api.WikidataApiClient api, GenerationLog log) throws Exception {
        Map<String, List<WikidataDynamicObject>> byQid = new LinkedHashMap<>();
        if (values != null) for (WikidataDynamicObject value : values) {
            if (value != null && WikidataIds.isQid(value.qid())) {
                byQid.computeIfAbsent(value.qid(), ignored -> new ArrayList<>()).add(value);
            }
        }
        List<String> unresolved = byQid.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .noneMatch(value -> !value.enwikiTitle().isBlank()))
                .map(Map.Entry::getKey).toList();
        if (!unresolved.isEmpty()) {
            if (api == null) throw new IllegalArgumentException(
                    "Wikidata entity client is required to resolve Wikipedia articles");
            Map<String, wikidata.api.WikidataApiClient.ApiEntity> entities =
                    api.getEntities(unresolved, List.of(),
                            java.util.Set.of(
                                    wikidata.api.FactDemand.EntityMetadata.SITELINKS),
                            (log == null ? GenerationLog.NOOP : log).batchSink());
            entities.forEach((qid, entity) -> {
                if (entity == null || entity.enwikiTitle().isBlank()) return;
                List<WikidataDynamicObject> copies = byQid.get(qid);
                if (copies != null) copies.forEach(
                        value -> value.enwikiTitle(entity.enwikiTitle()));
            });
        }
        // The correspondence belongs to the entity, not to whichever in-memory copy
        // happened to receive wbgetentities first. Propagate a retained answer to every
        // copy before any source family consumes or later persists it.
        byQid.values().forEach(copies -> copies.stream()
                .map(WikidataDynamicObject::enwikiTitle)
                .filter(title -> !title.isBlank()).findFirst()
                .ifPresent(title -> copies.forEach(value -> value.enwikiTitle(title))));
        Map<String, List<WikidataDynamicObject>> byTitle = new LinkedHashMap<>();
        byQid.values().forEach(copies -> copies.stream()
                .map(WikidataDynamicObject::enwikiTitle)
                .filter(title -> !title.isBlank()).findFirst()
                .ifPresent(title -> byTitle.computeIfAbsent(title,
                        ignored -> new ArrayList<>()).addAll(copies)));
        return byTitle;
    }
}
