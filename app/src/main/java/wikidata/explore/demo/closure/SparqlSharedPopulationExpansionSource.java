package wikidata.explore.demo.closure;

import batch.BatchCheckpointStore;
import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.BatchProgress;
import batch.FailureClassifier;
import batch.WorkDescriptor;
import batch.WorkUnit;
import wikidata.WikidataBinding;
import wikidata.WikidataBatchFailureClassifier;
import wikidata.WikidataSparqlClient;
import work.CancellationToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Executes the shared-population relation as small, bounded SPARQL map steps. */
public final class SparqlSharedPopulationExpansionSource
        implements SharedPopulationExpansionSource {
    private static final int MEMBER_BATCH_SIZE = 40;
    private static final int TYPE_BATCH_SIZE = 40;
    private static final int LABEL_BATCH_SIZE = 100;

    private final WikidataSparqlClient client;
    private final BatchPolicy batchPolicy;
    private final BatchProgress progress;
    private final FailureClassifier failureClassifier;
    private final CancellationToken cancellation;
    private final int parallelism;
    private final Map<String, List<?>> completedRequests = new LinkedHashMap<>();

    public SparqlSharedPopulationExpansionSource(WikidataSparqlClient client) {
        this(client, BatchPolicy.defaults(), BatchProgress.NOOP,
                WikidataBatchFailureClassifier.INSTANCE,
                new CancellationToken(), 1);
    }

    public SparqlSharedPopulationExpansionSource(
            WikidataSparqlClient client,
            BatchPolicy batchPolicy,
            BatchProgress progress,
            FailureClassifier failureClassifier,
            CancellationToken cancellation,
            int parallelism) {
        this.client = Objects.requireNonNull(client, "client");
        this.batchPolicy = batchPolicy == null ? BatchPolicy.defaults() : batchPolicy;
        this.progress = progress == null ? BatchProgress.NOOP : progress;
        this.failureClassifier = Objects.requireNonNull(
                failureClassifier, "failureClassifier");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be >= 1");
        this.parallelism = parallelism;
    }

    @Override
    public SharedPopulationExpansion expand(
            SharedPopulationClosureConfig config,
            List<String> sourceQids) throws Exception {
        if (sourceQids == null || sourceQids.isEmpty()) {
            return SharedPopulationExpansion.empty();
        }

        // Keeping the reverse lookup and fan-out in one WDQS query can create a
        // large intermediate result and even a truncated JSON response. Map them
        // separately and keep every fan-out request bounded by VALUES.
        List<Membership> memberships = memberships(config, sourceQids);
        if (memberships.isEmpty()) return SharedPopulationExpansion.empty();
        boolean resourceLimitReached = memberships.size()
                > config.maxMembershipsPerUnit();
        if (resourceLimitReached) {
            memberships = new ArrayList<>(memberships.subList(
                    0, config.maxMembershipsPerUnit()));
        }

        List<Connection> connections = new ArrayList<>();
        for (int from = 0; from < memberships.size(); from += MEMBER_BATCH_SIZE) {
            int to = Math.min(memberships.size(), from + MEMBER_BATCH_SIZE);
            int remaining = config.maxConnectionsPerUnit() - connections.size();
            if (remaining <= 0) {
                resourceLimitReached = true;
                break;
            }
            List<Connection> part = connections(
                    config, memberships.subList(from, to), remaining + 1);
            if (part.size() > remaining) {
                connections.addAll(part.subList(0, remaining));
                resourceLimitReached = true;
                break;
            }
            connections.addAll(part);
        }
        int candidateTargetCount = distinctTargetCount(connections);
        connections = allowedConnections(config, connections);
        int rejectedTargetCount = candidateTargetCount - distinctTargetCount(connections);

        Set<String> entities = new LinkedHashSet<>();
        entities.addAll(sourceQids);
        memberships.forEach(row -> entities.add(row.memberQid()));
        connections.forEach(row -> entities.add(row.targetQid()));
        Map<String, String> labels = labels(entities);

        List<SharedPopulationMember> members = memberships.stream()
                .map(row -> new SharedPopulationMember(
                        row.sourceQid(), label(labels, row.sourceQid()),
                        row.memberQid(), label(labels, row.memberQid())))
                .toList();
        List<SharedPopulationEdge> edges = connections.stream()
                .map(row -> new SharedPopulationEdge(
                        row.sourceQid(), label(labels, row.sourceQid()),
                        row.memberQid(), label(labels, row.memberQid()),
                        row.targetQid(), label(labels, row.targetQid())))
                .toList();
        return new SharedPopulationExpansion(
                members, edges, rejectedTargetCount, resourceLimitReached);
    }

    private List<Membership> memberships(
            SharedPopulationClosureConfig config,
            List<String> sourceQids) throws Exception {
        return partitions("memberships", sourceQids, sourceQids.size(),
                part -> membershipQuery(config, part), rows -> {
                    List<Membership> result = new ArrayList<>();
                    for (WikidataBinding row : rows) {
                        String source = row.qid("source");
                        String member = row.qid("member");
                        if (source != null && member != null) {
                            result.add(new Membership(source, member));
                        }
                    }
                    return result;
                });
    }

    private List<Connection> connections(
            SharedPopulationClosureConfig config,
            List<Membership> memberships,
            int limit) throws Exception {
        return partitions("connections", memberships, MEMBER_BATCH_SIZE,
                part -> connectionQuery(config, part, limit), rows -> {
                    List<Connection> result = new ArrayList<>();
                    for (WikidataBinding row : rows) {
                        String source = row.qid("source");
                        String member = row.qid("member");
                        String target = row.qid("target");
                        if (source != null && member != null && target != null) {
                            result.add(new Connection(source, member, target));
                        }
                    }
                    return result;
                });
    }

    private Map<String, String> labels(Set<String> qids) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (Label label : partitions("labels", List.copyOf(qids), LABEL_BATCH_SIZE,
                SparqlSharedPopulationExpansionSource::labelQuery, rows -> {
                    List<Label> labels = new ArrayList<>();
                    for (WikidataBinding row : rows) {
                        String entity = row.qid("entity");
                        String value = row.value("label");
                        if (entity != null && value != null) {
                            labels.add(new Label(entity, value));
                        }
                    }
                    return labels;
                })) {
            result.put(label.qid(), label.label());
        }
        return result;
    }

    private List<Connection> allowedConnections(
            SharedPopulationClosureConfig config,
            List<Connection> connections) throws Exception {
        if (config.targetRootQids().isEmpty() || connections.isEmpty()) {
            return connections;
        }
        List<String> targets = connections.stream()
                .map(Connection::targetQid)
                .distinct()
                .toList();
        Set<String> allowed = new LinkedHashSet<>(partitions(
                "target types", targets, TYPE_BATCH_SIZE,
                part -> typeQuery(part, config.targetRootQids()), rows -> {
                    List<String> result = new ArrayList<>();
                    for (WikidataBinding row : rows) {
                        String target = row.qid("target");
                        if (target != null) result.add(target);
                    }
                    return result;
                }));
        return connections.stream()
                .filter(row -> allowed.contains(row.targetQid()))
                .toList();
    }

    @Override
    public String request(
            SharedPopulationClosureConfig config,
            List<String> sourceQids) {
        return membershipQuery(config, sourceQids)
                + "\n# Then expand members in VALUES batches of "
                + MEMBER_BATCH_SIZE + ", filter targets through roots "
                + config.targetRootQids() + ", and fetch labels separately.";
    }

    public static String membershipQuery(
            SharedPopulationClosureConfig config,
            List<String> sourceQids) {
        return """
                SELECT DISTINCT ?source ?member WHERE {
                  VALUES ?source { %s }
                  ?member wdt:%s ?source .
                }
                LIMIT %d
                """.formatted(values(sourceQids), config.populationPropertyPid(),
                config.maxMembershipsPerUnit() + 1);
    }

    private static String connectionQuery(
            SharedPopulationClosureConfig config,
            List<Membership> memberships,
            int limit) {
        StringBuilder tuples = new StringBuilder();
        for (Membership row : memberships) {
            if (!tuples.isEmpty()) tuples.append(' ');
            tuples.append("(wd:").append(row.sourceQid())
                    .append(" wd:").append(row.memberQid()).append(')');
        }
        return """
                SELECT DISTINCT ?source ?member ?target WHERE {
                  VALUES (?source ?member) { %s }
                  ?member wdt:%s ?target .
                  FILTER(?target != ?source)
                }
                LIMIT %d
                """.formatted(tuples, config.nextWavePropertyPid(), limit);
    }

    private static String labelQuery(List<String> qids) {
        // Asking for "en" alone drops any entity whose only label is the multilingual
        // mul default, which is how #90 served bare QIDs. The language a Wikidata label
        // query asks for is one decision, and LabelService is where it is made.
        return """
                SELECT ?entity ?label WHERE {
                  VALUES ?entity { %s }
                  ?entity rdfs:label ?label .
                  %s
                }
                """.formatted(values(qids),
                wikidata.query.LabelService.labelFilter("label", null));
    }

    static String typeQuery(List<String> targetQids, List<String> rootQids) {
        return """
                SELECT DISTINCT ?target WHERE {
                  VALUES ?target { %s }
                  VALUES ?root { %s }
                  { ?target wdt:P279* ?root }
                  UNION
                  { ?target wdt:P31/wdt:P279* ?root }
                }
                """.formatted(values(targetQids), values(rootQids));
    }

    private static String values(List<String> qids) {
        return qids.stream().map(qid -> "wd:" + qid)
                .reduce((a, b) -> a + " " + b).orElseThrow();
    }

    private static String label(Map<String, String> labels, String qid) {
        return labels.getOrDefault(qid, qid);
    }

    private static int distinctTargetCount(List<Connection> connections) {
        return (int) connections.stream().map(Connection::targetQid).distinct().count();
    }

    private record Membership(String sourceQid, String memberQid) { }

    private record Connection(String sourceQid, String memberQid, String targetQid) { }

    private record Label(String qid, String label) { }

    private <I, O> List<O> partitions(
            String stage,
            List<I> inputs,
            int size,
            Function<List<I>, String> request,
            Function<List<WikidataBinding>, List<O>> decode) throws Exception {
        if (inputs.isEmpty()) return List.of();
        List<WorkUnit<List<O>>> units = new ArrayList<>();
        for (int from = 0; from < inputs.size(); from += size) {
            units.add(new QueryUnit<>(stage,
                    inputs.subList(from, Math.min(inputs.size(), from + size)),
                    request, decode));
        }
        List<O> result = new ArrayList<>();
        new BatchExecutor<List<O>>(batchPolicy, progress, failureClassifier,
                cancellation, BatchCheckpointStore.NONE, parallelism)
                .run(units, (descriptor, values) -> result.addAll(values));
        return result;
    }

    @SuppressWarnings("unchecked")
    private <O> List<O> executeOnce(
            String request,
            Function<List<WikidataBinding>, List<O>> decode) throws Exception {
        List<?> existing;
        synchronized (completedRequests) {
            existing = completedRequests.get(request);
        }
        if (existing != null) return (List<O>) existing;
        List<O> result = List.copyOf(decode.apply(client.query(request)));
        synchronized (completedRequests) {
            List<?> raced = completedRequests.putIfAbsent(request, result);
            return raced == null ? result : (List<O>) raced;
        }
    }

    private final class QueryUnit<I, O> implements WorkUnit<List<O>> {
        private final String stage;
        private final List<I> inputs;
        private final Function<List<I>, String> requestFactory;
        private final Function<List<WikidataBinding>, List<O>> decoder;
        private final WorkDescriptor descriptor;

        private QueryUnit(
                String stage,
                List<I> inputs,
                Function<List<I>, String> requestFactory,
                Function<List<WikidataBinding>, List<O>> decoder) {
            this.stage = stage;
            this.inputs = List.copyOf(inputs);
            this.requestFactory = requestFactory;
            this.decoder = decoder;
            String request = requestFactory.apply(this.inputs);
            this.descriptor = new WorkDescriptor(
                    "shared-population-" + stage.replace(' ', '-'),
                    stage + ":" + request,
                    stage + " (" + inputs.size() + ")",
                    Map.of("count", Integer.toString(inputs.size())));
        }

        @Override public WorkDescriptor descriptor() { return descriptor; }

        @Override public String request() { return requestFactory.apply(inputs); }

        @Override public List<O> execute() throws Exception {
            return executeOnce(request(), decoder);
        }

        @Override public List<? extends WorkUnit<List<O>>> split() {
            if (inputs.size() < 2) return List.of();
            int middle = inputs.size() / 2;
            return List.of(
                    new QueryUnit<>(stage, inputs.subList(0, middle),
                            requestFactory, decoder),
                    new QueryUnit<>(stage, inputs.subList(middle, inputs.size()),
                            requestFactory, decoder));
        }
    }
}
