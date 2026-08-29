package wikidata.explore.demo.closure;

import batch.BatchCheckpointStore;
import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.BatchProgress;
import batch.FailureClassifier;
import batch.ResultCommitter;
import batch.WorkDescriptor;
import batch.WorkUnit;
import batch.WorkUnitFactory;
import work.CancellationToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes a bounded, breadth-first closure over values related through population members.
 * SPARQL expansion is the map phase; the per-wave visited-set merge is the reduce phase.
 */
public final class SharedPopulationClosure {
    private static final String UNIT_TYPE = "shared-population-wave";

    private final SharedPopulationClosureConfig config;
    private final SharedPopulationExpansionSource source;
    private final BatchPolicy batchPolicy;
    private final BatchProgress progress;
    private final FailureClassifier failureClassifier;
    private final CancellationToken cancellation;
    private final BatchCheckpointStore checkpoints;
    private final int parallelism;
    private final InMemoryClosureResultStore committedResults =
            new InMemoryClosureResultStore();

    public SharedPopulationClosure(
            SharedPopulationClosureConfig config,
            SharedPopulationExpansionSource source,
            BatchPolicy batchPolicy,
            BatchProgress progress,
            FailureClassifier failureClassifier,
            CancellationToken cancellation,
            BatchCheckpointStore checkpoints,
            int parallelism) {
        this.config = Objects.requireNonNull(config, "config");
        this.source = Objects.requireNonNull(source, "source");
        this.batchPolicy = batchPolicy == null ? BatchPolicy.defaults() : batchPolicy;
        this.progress = progress == null ? BatchProgress.NOOP : progress;
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.checkpoints = checkpoints == null ? BatchCheckpointStore.NONE : checkpoints;
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be >= 1");
        this.parallelism = parallelism;
    }

    public Result execute() throws Exception {
        LinkedHashMap<String, Value> values = new LinkedHashMap<>();
        values.put(config.startValueQid(),
                new Value(config.startValueQid(), config.startValueQid(), 0));
        LinkedHashSet<SharedPopulationEdge> allEdges = new LinkedHashSet<>();
        LinkedHashSet<SharedPopulationMember> allMembers = new LinkedHashSet<>();
        List<Wave> waves = new ArrayList<>();
        List<String> frontier = List.of(config.startValueQid());
        boolean valueLimitReached = false;

        for (int depth = 1; depth <= config.maxDepth() && !frontier.isEmpty(); depth++) {
            cancellation.throwIfCancelled();
            int waveDepth = depth;
            List<SharedPopulationMember> mappedMembers = new ArrayList<>();
            List<SharedPopulationEdge> mappedEdges = new ArrayList<>();
            int[] rejectedTargetCount = { 0 };
            boolean[] resourceLimitReached = { false };
            List<WorkUnit<SharedPopulationExpansion>> units = units(waveDepth, frontier);
            WorkUnitFactory<SharedPopulationExpansion> restorer = descriptor ->
                    restore(waveDepth, descriptor);
            ResultCommitter<SharedPopulationExpansion> committer = (descriptor, result) -> {
                committedResults.commit(runKey(waveDepth), descriptor, result);
            };

            BatchExecutor<SharedPopulationExpansion> executor = new BatchExecutor<>(
                    batchPolicy, progress, failureClassifier, cancellation,
                    checkpoints, parallelism);
            String runKey = runKey(waveDepth);
            // FINISH means there is no resumable journal. A later execute() is a fresh
            // wave and must not reduce stale child results left by an earlier adaptive
            // split. An unfinished journal, conversely, requires those results.
            if (checkpoints.recover(runKey).isEmpty()) {
                committedResults.reset(runKey);
            }
            executor.run(runKey, units, restorer, committer);

            // A resumed executor invokes the committer only for work that was still
            // pending. Reduce every result retained for this run, including partitions
            // committed before the interruption.
            for (SharedPopulationExpansion result : committedResults.results(runKey)) {
                mappedMembers.addAll(result.members());
                mappedEdges.addAll(result.edges());
                rejectedTargetCount[0] += result.rejectedTargetCount();
                resourceLimitReached[0] |= result.resourceLimitReached();
            }

            mappedMembers.sort(Comparator
                    .comparing(SharedPopulationMember::sourceQid)
                    .thenComparing(SharedPopulationMember::memberQid));
            LinkedHashSet<SharedPopulationMember> waveMembers =
                    new LinkedHashSet<>(mappedMembers);
            allMembers.addAll(waveMembers);
            mappedEdges.sort(Comparator
                    .comparing(SharedPopulationEdge::sourceQid)
                    .thenComparing(SharedPopulationEdge::targetQid)
                    .thenComparing(SharedPopulationEdge::memberQid));
            LinkedHashSet<SharedPopulationEdge> waveEdges = new LinkedHashSet<>(mappedEdges);
            allEdges.addAll(waveEdges);

            LinkedHashSet<String> next = new LinkedHashSet<>();
            for (SharedPopulationEdge edge : waveEdges) {
                updateLabel(values, edge.sourceQid(), edge.sourceLabel());
                Value existing = values.get(edge.targetQid());
                if (existing != null) {
                    updateLabel(values, edge.targetQid(), edge.targetLabel());
                    continue;
                }
                if (values.size() >= config.maxValues()) {
                    valueLimitReached = true;
                    continue;
                }
                values.put(edge.targetQid(),
                        new Value(edge.targetQid(), edge.targetLabel(), waveDepth));
                next.add(edge.targetQid());
            }
            waves.add(new Wave(waveDepth, List.copyOf(frontier),
                    List.copyOf(waveMembers), List.copyOf(waveEdges), List.copyOf(next),
                    rejectedTargetCount[0], resourceLimitReached[0]));
            frontier = List.copyOf(next);
            if (valueLimitReached || resourceLimitReached[0]) break;
        }
        return new Result(List.copyOf(values.values()), List.copyOf(allMembers),
                List.copyOf(allEdges), List.copyOf(waves), valueLimitReached,
                waves.stream().anyMatch(Wave::resourceLimitReached));
    }

    private List<WorkUnit<SharedPopulationExpansion>> units(
            int depth,
            List<String> frontier) {
        List<WorkUnit<SharedPopulationExpansion>> units = new ArrayList<>();
        for (int from = 0; from < frontier.size(); from += config.batchSize()) {
            int to = Math.min(frontier.size(), from + config.batchSize());
            units.add(new WaveUnit(depth, frontier.subList(from, to)));
        }
        return units;
    }

    private WorkUnit<SharedPopulationExpansion> restore(
            int expectedDepth,
            WorkDescriptor descriptor) {
        if (!UNIT_TYPE.equals(descriptor.type())) {
            throw new IllegalArgumentException("Unsupported work type: " + descriptor.type());
        }
        int depth = Integer.parseInt(descriptor.parameters().get("depth"));
        if (depth != expectedDepth) {
            throw new IllegalArgumentException("Checkpoint belongs to another wave");
        }
        String qids = descriptor.parameters().get("sourceQids");
        return new WaveUnit(depth, qids == null || qids.isBlank()
                ? List.of() : List.of(qids.split(",")));
    }

    private String runKey(int depth) {
        return "shared-population:"
                + config.startValueQid() + ":"
                + config.populationPropertyPid() + ":"
                + config.nextWavePropertyPid() + ":depth=" + depth
                + ":roots=" + String.join(",", config.targetRootQids());
    }

    private static void updateLabel(
            Map<String, Value> values,
            String qid,
            String label) {
        Value existing = values.get(qid);
        if (existing != null && existing.label().equals(existing.qid())
                && label != null && !label.isBlank()) {
            values.put(qid, new Value(qid, label, existing.minimumDepth()));
        }
    }

    public record Value(String qid, String label, int minimumDepth) { }

    public record Wave(
            int depth,
            List<String> frontier,
            List<SharedPopulationMember> members,
            List<SharedPopulationEdge> edges,
            List<String> newValues,
            int rejectedTargetCount,
            boolean resourceLimitReached) { }

    public record Result(
            List<Value> values,
            List<SharedPopulationMember> members,
            List<SharedPopulationEdge> edges,
            List<Wave> waves,
            boolean valueLimitReached,
            boolean resourceLimitReached) { }

    private final class WaveUnit implements WorkUnit<SharedPopulationExpansion> {
        private final int depth;
        private final List<String> sourceQids;
        private final WorkDescriptor descriptor;

        private WaveUnit(int depth, List<String> sourceQids) {
            if (sourceQids == null || sourceQids.isEmpty()) {
                throw new IllegalArgumentException("sourceQids must not be empty");
            }
            this.depth = depth;
            this.sourceQids = List.copyOf(sourceQids);
            String joined = String.join(",", this.sourceQids);
            this.descriptor = new WorkDescriptor(
                    UNIT_TYPE,
                    runKey(depth) + ":sources=" + joined,
                    "Expand depth " + depth + " (" + sourceQids.size() + " value(s))",
                    Map.of("depth", Integer.toString(depth), "sourceQids", joined));
        }

        @Override public WorkDescriptor descriptor() {
            return descriptor;
        }

        @Override public String request() {
            return source.request(config, sourceQids);
        }

        @Override public SharedPopulationExpansion execute() throws Exception {
            return source.expand(config, sourceQids);
        }

        @Override public List<? extends WorkUnit<SharedPopulationExpansion>> split() {
            if (sourceQids.size() < 2) return List.of();
            int middle = sourceQids.size() / 2;
            return List.of(
                    new WaveUnit(depth, sourceQids.subList(0, middle)),
                    new WaveUnit(depth, sourceQids.subList(middle, sourceQids.size())));
        }
    }
}
