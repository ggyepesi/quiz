package domain.analysis;

import domain.datasource.graph.RelationRoute;
import domain.datasource.wikidata.WikidataRelationRouteSpec;
import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds finite typed relation routes which connect explicit source/target examples.
 *
 * <p>The discovery algorithm knows nothing about Wikidata requests. Its reader supplies
 * all bounded segments leaving a requested entity; the same {@link RelationRoute} that
 * generation executes is assembled here, so discovery cannot invent a second route
 * language.</p>
 */
public final class RelationRouteDiscovery {
    private RelationRouteDiscovery() { }

    public record Example(String sourceQid, List<String> expectedTargetQids) {
        public Example {
            sourceQid = cleanQid(sourceQid, "source");
            expectedTargetQids = expectedTargetQids == null ? List.of()
                    : expectedTargetQids.stream().map(value -> cleanQid(value, "target"))
                            .distinct().toList();
            if (expectedTargetQids.isEmpty()) {
                throw new IllegalArgumentException(
                        "Each source QID needs at least one expected target QID");
            }
        }
    }

    /** One labelled route fragment ending at an entity. A qualifier fragment has two
     * steps (statement, qualifier); a direct property fragment has one. */
    public record Segment(
            List<RelationRoute.Step> steps,
            List<String> stepLabels,
            String targetQid,
            String targetLabel) {
        public Segment {
            steps = steps == null ? List.of() : List.copyOf(steps);
            stepLabels = stepLabels == null ? List.of() : List.copyOf(stepLabels);
            if (steps.isEmpty() || steps.size() != stepLabels.size()) {
                throw new IllegalArgumentException(
                        "A discovered segment needs one label per route step");
            }
            targetQid = cleanQid(targetQid, "segment target");
            targetLabel = targetLabel == null || targetLabel.isBlank()
                    ? targetQid : targetLabel.trim();
        }
    }

    public record ReadResult(Map<String, List<Segment>> segments, boolean limitReached) {
        public ReadResult {
            LinkedHashMap<String, List<Segment>> copy = new LinkedHashMap<>();
            if (segments != null) segments.forEach((qid, values) ->
                    copy.put(qid, values == null ? List.of() : List.copyOf(values)));
            segments = Map.copyOf(copy);
        }
    }

    @FunctionalInterface
    public interface SegmentReader {
        ReadResult read(List<String> entityQids) throws Exception;
    }

    public record Witness(String sourceQid, String targetQid) { }

    public record Candidate(
            RelationRoute route,
            List<String> stepLabels,
            int matchedExamples,
            int totalExamples,
            int additionalResults,
            List<Witness> witnesses) {
        public Candidate {
            stepLabels = List.copyOf(stepLabels);
            witnesses = List.copyOf(witnesses);
        }
    }

    public record Result(
            List<Candidate> candidates,
            int entitiesInspected,
            int expectedExamples,
            boolean discoveryLimitReached) {
        public Result { candidates = List.copyOf(candidates); }
    }

    public static Result discover(
            List<Example> examples,
            int maximumSteps,
            int maximumEntities,
            SegmentReader reader) throws Exception {
        List<Example> bounded = examples == null ? List.of() : List.copyOf(examples);
        if (bounded.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one source → target example");
        }
        if (reader == null) throw new IllegalArgumentException("A segment reader is required");
        int depth = Math.max(1, Math.min(6, maximumSteps));
        int entityLimit = Math.max(bounded.size(), Math.min(5_000, maximumEntities));
        int pathLimit = Math.max(100, entityLimit * 20);

        Map<String, Set<String>> expected = new LinkedHashMap<>();
        for (Example example : bounded) {
            expected.computeIfAbsent(example.sourceQid(), ignored -> new LinkedHashSet<>())
                    .addAll(example.expectedTargetQids());
        }
        int totalExpected = expected.values().stream().mapToInt(Set::size).sum();

        List<Path> frontier = expected.keySet().stream()
                .map(source -> Path.start(source)).toList();
        Map<String, List<Segment>> adjacency = new LinkedHashMap<>();
        LinkedHashSet<String> inspected = new LinkedHashSet<>();
        LinkedHashSet<String> admittedEntities = new LinkedHashSet<>(expected.keySet());
        Map<String, CandidateBuilder> routeResults = new LinkedHashMap<>();
        boolean limited = false;

        for (int wave = 0; wave < depth && !frontier.isEmpty(); wave++) {
            List<String> unread = frontier.stream()
                    .filter(path -> path.steps().size() < depth)
                    .map(Path::currentQid).filter(qid -> !adjacency.containsKey(qid))
                    .distinct().toList();
            if (!unread.isEmpty()) {
                ReadResult read = reader.read(unread);
                limited |= read.limitReached();
                inspected.addAll(unread);
                unread.forEach(qid -> adjacency.put(qid,
                        read.segments().getOrDefault(qid, List.of())));
            }

            List<Path> next = new ArrayList<>();
            Set<PathKey> nextKeys = new LinkedHashSet<>();
            for (Path path : frontier) {
                for (Segment segment : adjacency.getOrDefault(
                        path.currentQid(), List.of())) {
                    if (path.steps().size() + segment.steps().size() > depth) continue;
                    boolean cycle = path.visitedQids().contains(segment.targetQid());
                    Path reached = path.append(segment);
                    Set<String> targets = expected.getOrDefault(
                            path.sourceQid(), Set.of());
                    String reachedSignature = signature(reached.steps());
                    routeResults.computeIfAbsent(reachedSignature,
                                    ignored -> new CandidateBuilder(reached))
                            .reach(path.sourceQid(), reached.currentQid(),
                                    targets.contains(reached.currentQid()));
                    if (reached.steps().size() >= depth
                            || cycle) continue;
                    if (!admittedEntities.contains(segment.targetQid())) {
                        if (admittedEntities.size() >= entityLimit) {
                            limited = true;
                            continue;
                        }
                        admittedEntities.add(segment.targetQid());
                    }
                    PathKey key = new PathKey(reached.sourceQid(), reached.currentQid(),
                            signature(reached.steps()));
                    if (nextKeys.add(key)) {
                        if (next.size() >= pathLimit) {
                            limited = true;
                            continue;
                        }
                        next.add(reached);
                    }
                }
            }
            frontier = List.copyOf(next);
        }

        List<Candidate> found = routeResults.values().stream()
                .filter(CandidateBuilder::matchesAnExample)
                .map(builder -> builder.build(totalExpected))
                .sorted(Comparator.comparingInt(Candidate::matchedExamples).reversed()
                        .thenComparingInt(Candidate::additionalResults)
                        .thenComparingInt(candidate -> candidate.route().steps().size())
                        .thenComparing(candidate -> candidate.route().label()))
                .toList();
        return new Result(found, inspected.size(), totalExpected, limited);
    }

    private record Path(
            String sourceQid,
            String currentQid,
            List<RelationRoute.Step> steps,
            List<String> stepLabels,
            Set<String> visitedQids) {
        static Path start(String qid) {
            return new Path(qid, qid, List.of(), List.of(), Set.of(qid));
        }

        Path append(Segment segment) {
            List<RelationRoute.Step> appendedSteps = new ArrayList<>(steps);
            appendedSteps.addAll(segment.steps());
            List<String> appendedLabels = new ArrayList<>(stepLabels);
            appendedLabels.addAll(segment.stepLabels());
            LinkedHashSet<String> visited = new LinkedHashSet<>(visitedQids);
            visited.add(segment.targetQid());
            return new Path(sourceQid, segment.targetQid(), List.copyOf(appendedSteps),
                    List.copyOf(appendedLabels), Set.copyOf(visited));
        }
    }

    private record PathKey(String sourceQid, String targetQid, String route) { }

    private static final class CandidateBuilder {
        private final List<RelationRoute.Step> steps;
        private final List<String> labels;
        private final LinkedHashSet<Witness> witnesses = new LinkedHashSet<>();
        private final LinkedHashSet<Witness> reachedResults = new LinkedHashSet<>();

        CandidateBuilder(Path path) {
            steps = path.steps();
            labels = path.stepLabels();
        }

        void reach(String source, String target, boolean expected) {
            Witness result = new Witness(source, target);
            reachedResults.add(result);
            if (expected) witnesses.add(result);
        }

        boolean matchesAnExample() { return !witnesses.isEmpty(); }

        Candidate build(int total) {
            String signature = signature(steps);
            String id = "discovered-" + Integer.toUnsignedString(signature.hashCode(), 36);
            String label = String.join(" → ", labels);
            RelationRoute route = new RelationRoute(id, label, steps);
            // Validate through the provider's one typed-walk authority before a result
            // can reach the editor.
            new WikidataRelationRouteSpec(List.of(route));
            return new Candidate(route, labels, witnesses.size(), total,
                    reachedResults.size() - witnesses.size(),
                    List.copyOf(witnesses));
        }
    }

    private static String signature(Collection<RelationRoute.Step> steps) {
        return steps.stream().map(step -> step.relation().providerId() + ":"
                        + step.relation().relationId() + ":" + step.direction())
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String cleanQid(String value, String role) {
        String clean = value == null ? "" : value.trim().toUpperCase();
        if (!WikidataIds.isQid(clean)) {
            throw new IllegalArgumentException("Invalid " + role + " QID: "
                    + (clean.isBlank() ? "(blank)" : clean));
        }
        return clean;
    }
}
