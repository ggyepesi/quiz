package domain.datasource.configured;

import batch.BatchCheckpointStore;
import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.WorkDescriptor;
import batch.WorkUnit;
import com.fasterxml.jackson.databind.JsonNode;
import domain.datasource.api.discovery.DiscoveredRelatedRecord;
import domain.datasource.api.discovery.RelatedRecordDiscoveryResult;
import domain.datasource.api.discovery.RelatedRecordSeed;
import domain.runtime.GenerationLog;
import wikidata.explore.query.logical.StepGenerationLog;
import work.Query;
import work.QueryContext;
import work.QueryStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Executes one configured search-to-detail operation through shared query/batch logs. */
final class ConfiguredRestDiscoveryQuery implements Query<RelatedRecordDiscoveryResult> {
    private final String providerId;
    private final String providerName;
    private final ConfiguredDatasourceDefinition.RestOperation operation;
    private final ConfiguredRestClient client;
    private final List<RelatedRecordSeed> seeds;

    ConfiguredRestDiscoveryQuery(String providerId, String providerName,
            ConfiguredDatasourceDefinition.RestOperation operation,
            ConfiguredRestClient client, List<RelatedRecordSeed> seeds) {
        this.providerId = providerId;
        this.providerName = providerName;
        this.operation = operation;
        this.client = client;
        this.seeds = List.copyOf(seeds == null ? List.of() : seeds);
    }

    @Override public String purpose() { return "Discover " + operation.displayName(); }
    @Override public String queryType() { return providerName + " JSON REST API"; }
    @Override public String skeleton() {
        return "exact selected " + operation.inputNamespace()
                + " entities -> bounded search IDs -> detail records -> exact configured "
                + "evidence match";
    }
    @Override public String description() {
        return "Search " + providerName + " for at most "
                + operation.maximumRecordsPerSeed() + " record(s) per selected entity, then "
                + "retain only detail records whose configured evidence names that entity";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("selected entities", Integer.toString(seeds.size()),
                "maximum records per entity",
                Integer.toString(operation.maximumRecordsPerSeed()),
                "maximum detail requests",
                Integer.toString(seeds.size() * operation.maximumRecordsPerSeed()));
    }

    @Override public RelatedRecordDiscoveryResult execute(QueryContext context)
            throws Exception {
        if (seeds.isEmpty()) return new RelatedRecordDiscoveryResult(
                List.of(), 0, 0, List.of());

        List<SearchOutcome> searches = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        runStage(context, "Search " + providerName,
                seeds.stream().map(seed -> searchUnit(seed)).toList(), searches,
                failures);
        searches.stream().filter(search -> !search.failure().isBlank())
                .forEach(search -> failures.add(search.failure()));
        int reported = searches.stream().mapToInt(SearchOutcome::total).sum();
        int retained = searches.stream().mapToInt(value -> value.ids().size()).sum();
        context.message(providerName + " search reported " + reported
                + " matching record(s) across " + searches.stream()
                        .filter(search -> search.failure().isBlank()).count()
                + " completed seed search(es); retained " + retained
                + " bounded record ID occurrence(s), at most "
                + operation.maximumRecordsPerSeed() + " per seed.\n");

        LinkedHashMap<String, List<RelatedRecordSeed>> candidateSeeds = new LinkedHashMap<>();
        for (SearchOutcome search : searches) {
            for (String id : search.ids()) {
                candidateSeeds.computeIfAbsent(id, ignored -> new ArrayList<>())
                        .add(search.seed());
            }
        }

        List<DetailOutcome> details = new ArrayList<>();
        runStage(context, "Load " + candidateSeeds.size() + " " + providerName
                        + " detail record(s)",
                candidateSeeds.entrySet().stream()
                        .map(entry -> detailUnit(entry.getKey(), entry.getValue().size()))
                        .toList(),
                details, failures);

        LinkedHashMap<String, MutableRecord> records = new LinkedHashMap<>();
        for (DetailOutcome detail : details) {
            if (!detail.failure().isBlank()) {
                failures.add(detail.failure());
                continue;
            }
            if (detail.record() == null) continue;
            records.computeIfAbsent(detail.record().qualifiedId(), ignored ->
                    new MutableRecord(detail.record())).merge(detail.record());
        }
        return new RelatedRecordDiscoveryResult(records.values().stream()
                .map(MutableRecord::freeze).toList(), seeds.size(), candidateSeeds.size(),
                failures.stream().distinct().toList());
    }

    private <R extends batch.BatchOutcome> void runStage(QueryContext context, String title,
            List<? extends WorkUnit<R>> units, List<R> values, List<String> failures)
            throws Exception {
        if (units.isEmpty()) return;
        context.step(title, queryType(), skeleton(), Map.of(
                        "work units", Integer.toString(units.size()),
                        "parallel requests", Integer.toString(operation.parallelism())),
                step -> {
                    GenerationLog log = StepGenerationLog.of(context, step);
                    List<WorkDescriptor> failed = new BatchExecutor<R>(
                            new BatchPolicy(2, 0, 250L, false, 2),
                            log.batchProgress(), batch.FailureClassifier.standard(),
                            context.cancellation(), BatchCheckpointStore.NONE,
                            operation.parallelism())
                            .runBestEffort(units, (descriptor, value) -> values.add(value));
                    failed.forEach(descriptor -> failures.add(descriptor.title()));
                    long refused = values.stream()
                            .filter(value -> !value.failure().isBlank()).count();
                    String summary = (values.size() - refused) + " completed; " + refused
                            + " refused; " + failed.size() + " unavailable";
                    if (refused + failed.size() > 0) step.partial(summary);
                    else step.summary(summary);
                    return null;
                });
    }

    private WorkUnit<SearchOutcome> searchUnit(RelatedRecordSeed seed) {
        String url = ConfiguredRestUrls.search(operation.search(), seed,
                operation.maximumRecordsPerSeed());
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                return new WorkDescriptor(providerId + "-search",
                        providerId + ":search:" + seed.key(),
                        "Search " + providerName + " for " + seed.displayName()
                                + " (" + seed.entity().id() + ")",
                        Map.of("seed", seed.key(), "entity", seed.entity().qualifiedId(),
                                "limit", Integer.toString(
                                        operation.maximumRecordsPerSeed())));
            }
            @Override public String request() { return url; }
            @Override public SearchOutcome execute() throws Exception {
                JsonNode root;
                try {
                    root = client.get(url);
                } catch (objectview.utils.UrlOpener.HttpStatusException status) {
                    return new SearchOutcome(seed, List.of(), 0, refused(status,
                            "search for " + seed.displayName() + " ("
                                    + seed.entity().id() + ")"));
                }
                List<String> ids = JsonFieldPath.strings(root,
                                operation.search().idsPath()).stream()
                        .limit(operation.maximumRecordsPerSeed()).toList();
                int total = operation.search().totalPath().isBlank() ? ids.size()
                        : integer(JsonFieldPath.first(root,
                                operation.search().totalPath()), ids.size());
                return new SearchOutcome(seed, ids, total, "");
            }
            @Override public List<? extends WorkUnit<SearchOutcome>> split() {
                return List.of();
            }
        };
    }

    /**
     * A search only nominates a record. Its detail evidence decides every selected seed it
     * relates to, including seeds whose own search did not return it.
     */
    private WorkUnit<DetailOutcome> detailUnit(String resultId, int nominatingSeeds) {
        String url = ConfiguredRestUrls.detail(operation.detail(), resultId);
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                return new WorkDescriptor(providerId + "-detail",
                        providerId + ":detail:" + resultId,
                        "Load " + providerName + " record " + resultId,
                        Map.of("record", resultId, "nominated by",
                                nominatingSeeds + " seed search(es)"));
            }
            @Override public String request() { return url; }
            @Override public DetailOutcome execute() throws Exception {
                try {
                    return detail(client.get(url), resultId);
                } catch (objectview.utils.UrlOpener.HttpStatusException status) {
                    return new DetailOutcome(null, refused(status, "record " + resultId));
                }
            }
            @Override public List<? extends WorkUnit<DetailOutcome>> split() {
                return List.of();
            }
        };
    }

    /**
     * A status the server will not reconsider costs only the one request that received
     * it: the run continues and is partial. A server-side status is rethrown, so the
     * batch executor spends its unavailable budget on it first.
     */
    private String refused(objectview.utils.UrlOpener.HttpStatusException status,
            String what) throws objectview.utils.UrlOpener.HttpStatusException {
        if (status.status() == 429 || status.status() >= 500) throw status;
        return "Could not read " + providerName + " " + what + ": HTTP " + status.status();
    }

    private DetailOutcome detail(JsonNode root, String requestedId) {
        List<String> evidence = JsonFieldPath.strings(root, operation.match().path());
        List<String> matched = seeds.stream()
                .filter(seed -> evidence.stream().map(this::matchValue)
                        .anyMatch(value -> value.equalsIgnoreCase(seed.entity().id())))
                .map(RelatedRecordSeed::key).distinct().toList();
        if (matched.isEmpty()) return new DetailOutcome(null, "");

        ConfiguredDatasourceDefinition.Record schema = operation.record();
        String id = JsonFieldPath.first(root, schema.idPath());
        if (id.isBlank()) id = requestedId;
        String display = JsonFieldPath.first(root, schema.displayPath());
        LinkedHashMap<String, List<String>> fields = new LinkedHashMap<>();
        for (ConfiguredDatasourceDefinition.Field field : schema.fields()) {
            List<String> values = JsonFieldPath.strings(root, field.path());
            if (!field.collection() && values.size() > 1) values = values.subList(0, 1);
            if (!values.isEmpty()) fields.put(field.name(), values);
        }
        return new DetailOutcome(new DiscoveredRelatedRecord(
                schema.namespace(), id, display, fields, matched), "");
    }

    private String matchValue(String value) {
        String clean = value == null ? "" : value.trim();
        if (operation.match().valueFormat()
                != ConfiguredDatasourceDefinition.ValueFormat.WIKIDATA_URL) return clean;
        int slash = clean.lastIndexOf('/');
        String tail = slash >= 0 ? clean.substring(slash + 1) : clean;
        return wikidata.WikidataIds.isQid(tail.toUpperCase()) ? tail.toUpperCase() : "";
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    @Override public int rowCount(RelatedRecordDiscoveryResult result) {
        return result == null ? 0 : result.records().size();
    }
    @Override public QueryStatus status(RelatedRecordDiscoveryResult result) {
        return result != null && result.partial() ? QueryStatus.PARTIAL : QueryStatus.OK;
    }
    @Override public String summary(RelatedRecordDiscoveryResult result) {
        if (result == null) return "No result";
        return result.records().size() + " exact related record(s) from "
                + result.searchedCandidates() + " bounded candidate(s) for "
                + result.seedCount() + " selected entit"
                + (result.seedCount() == 1 ? "y" : "ies")
                + (result.failedWork().isEmpty() ? "" : "; "
                        + result.failedWork().size() + " request(s) failed");
    }

    private record SearchOutcome(RelatedRecordSeed seed, List<String> ids, int total,
            String failure) implements batch.BatchOutcome {
        SearchOutcome {
            ids = List.copyOf(ids == null ? List.of() : ids);
            failure = failure == null ? "" : failure;
        }
    }
    private record DetailOutcome(DiscoveredRelatedRecord record, String failure)
            implements batch.BatchOutcome {
        DetailOutcome { failure = failure == null ? "" : failure; }
    }

    private static final class MutableRecord {
        private final DiscoveredRelatedRecord first;
        private final LinkedHashSet<String> seedKeys = new LinkedHashSet<>();
        private MutableRecord(DiscoveredRelatedRecord first) {
            this.first = first;
            seedKeys.addAll(first.matchedSeedKeys());
        }
        private void merge(DiscoveredRelatedRecord next) {
            seedKeys.addAll(next.matchedSeedKeys());
        }
        private DiscoveredRelatedRecord freeze() {
            return new DiscoveredRelatedRecord(first.namespace(), first.id(),
                    first.displayName(), first.fields(), List.copyOf(seedKeys));
        }
    }
}
