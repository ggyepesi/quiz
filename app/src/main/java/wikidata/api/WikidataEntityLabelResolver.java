package wikidata.api;

import wikidata.WikidataIds;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One policy boundary for resolving Wikidata entity names.
 *
 * <p>Both generation and repair need the same semantics: English with multilingual
 * fallback, explicit missing entities, partial-result preservation, and physical
 * requests of at most 50 QIDs. Interactive repair runs sequentially so it cannot create
 * a burst of hundreds of workers; generation may use the API client's bounded pool.
 */
public final class WikidataEntityLabelResolver {

    public enum Execution { SEQUENTIAL, BOUNDED_PARALLEL }

    public record Result(
            Map<String, String> labels,
            Set<String> missing,
            int failedBatches,
            List<String> unavailableQids) {
        public Result(Map<String, String> labels, Set<String> missing, int failedBatches) {
            this(labels, missing, failedBatches, List.of());
        }
        public Result {
            labels = Map.copyOf(labels);
            missing = Set.copyOf(missing);
            unavailableQids = unavailableQids == null ? List.of()
                    : unavailableQids.stream().distinct().toList();
        }
    }

    private static final int BATCH_SIZE = 50;
    private final WikidataApiClient api;

    public WikidataEntityLabelResolver(WikidataApiClient api) {
        this.api = java.util.Objects.requireNonNull(api, "api");
    }

    public Result resolve(
            Collection<String> qids,
            Execution execution,
            WikidataApiClient.BatchLog log) throws Exception {
        List<String> clean = qids == null ? List.of() : qids.stream()
                .filter(WikidataIds::isQid)
                .distinct()
                .toList();
        if (clean.isEmpty()) return new Result(Map.of(), Set.of(), 0);

        if (execution == Execution.BOUNDED_PARALLEL) {
            WikidataApiClient.PartialEntities partial =
                    api.getEntitiesBestEffort(clean, List.of(),
                            Set.of(FactDemand.EntityMetadata.LABEL), log);
            return result(partial.entities(), partial.failedBatches(),
                    partial.unavailableQids());
        }

        Map<String, WikidataApiClient.ApiEntity> entities = new LinkedHashMap<>();
        int failed = 0;
        Set<String> unavailable = new LinkedHashSet<>();
        for (int from = 0; from < clean.size(); from += BATCH_SIZE) {
            List<String> batch = clean.subList(from, Math.min(from + BATCH_SIZE, clean.size()));
            try {
                entities.putAll(api.getEntities(batch, List.of(),
                        Set.of(FactDemand.EntityMetadata.LABEL), log));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()
                        || e instanceof InterruptedException) {
                    throw e;
                }
                failed++;
                unavailable.addAll(batch);
            }
        }
        return result(entities, failed, List.copyOf(unavailable));
    }

    private static Result result(
            Map<String, WikidataApiClient.ApiEntity> entities, int failedBatches,
            List<String> unavailableQids) {
        Map<String, String> labels = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        entities.forEach((qid, entity) -> {
            if (entity.missing()) {
                missing.add(qid);
            } else if (entity.label() != null && !entity.label().isBlank()) {
                labels.put(qid, entity.label());
            }
        });
        return new Result(labels, missing, failedBatches, unavailableQids);
    }
}
