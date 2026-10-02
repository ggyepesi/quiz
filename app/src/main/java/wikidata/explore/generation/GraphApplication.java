package wikidata.explore.generation;

import datasource.graph.GraphDiscoveryConfiguration.PopulationOperation;
import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.rule.RuleTreeCompiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applying a graph result to the project's instances, as an operation that runs without a
 * window (directive 22, the design's APPLY_GRAPH_DECISIONS).
 *
 * <p>A graph decides WHICH entities belong to its output class, never what they contain.
 * Narrowing keeps the project's own generated instances that were accepted; adding stamps
 * accepted generated instances with the output class and keeps every existing member.
 * Accepted ids with no generated instance are reported, not invented — an empty shell is
 * indistinguishable from an instance whose acquisition failed.
 *
 * <p>Applying stops when the result holds Review entries its graph said to wait for and
 * that have no manual decision yet: that is the build's AWAITING_DECISION state, and the
 * outcome names every such entry. The desktop and a headless build get the same answer;
 * the desktop keeps its results dialog open so the reviewer can decide them.
 *
 * <p>This lived in the ModelBuilder frame, where no build could reach it.
 */
public final class GraphApplication {

    private GraphApplication() { }

    /** What applying a result did, or why it did not. */
    public sealed interface Outcome permits AwaitingDecision, Applied { }

    /** Nothing was applied: these entries need a decision first. */
    public record AwaitingDecision(String graphName, List<WikidataDynamicObject> undecided)
            implements Outcome {
        public AwaitingDecision {
            undecided = List.copyOf(undecided);
        }

        public String message() {
            String names = undecided.stream()
                    .map(value -> value.getDisplayName() + " (" + value.getIdentifier() + ")")
                    .collect(Collectors.joining(", "));
            return "Graph result \"" + graphName + "\" is awaiting a decision: "
                    + undecided.size() + " Review " + (undecided.size() == 1 ? "entry has" : "entries have")
                    + " no manual decision — " + names
                    + ". Accept or reject each one, then apply again.";
        }
    }

    /** The run after applying, the result marked as applied (the fact the next Generate
     *  reads its pending additions from), and what the application changed. */
    public record Applied(GenerationRun run, GraphDiscoveryResultStore.Artifact result,
                          String graphName, String outputClass,
                          PopulationOperation operation, int previousMembers, int addedMembers,
                          long resultingMembers, int ungenerated) implements Outcome {

        public String message(boolean model) {
            return GraphApplication.message(graphName, outputClass, operation, previousMembers,
                    addedMembers, resultingMembers, ungenerated, model);
        }
    }

    /**
     * Applies {@code result} to the instances of {@code current} under {@code model}, or says
     * which entries it is waiting for. A missing run is an empty pool.
     */
    public static Outcome apply(GraphDiscoveryResultStore.Artifact result,
                                GeneratedProjectModel model, GenerationRun current)
            throws Exception {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(model, "model");
        if (result.outputClass().isBlank()) {
            throw new IllegalArgumentException("Graph result \"" + result.type()
                    + "\" names no output class to apply to");
        }
        List<WikidataDynamicObject> undecided = result.awaitingDecision();
        if (!undecided.isEmpty()) return new AwaitingDecision(result.type(), undecided);

        String outputClass = result.outputClass();
        List<WikidataDynamicObject> previousPool =
                current == null ? List.of() : current.dynamicObjects();
        Set<String> previousMembers = membersOf(previousPool, outputClass);
        GenerationRuns.NarrowedPool narrowed = result.populationOperation() == PopulationOperation.ADD
                ? GenerationRuns.addedTo(previousPool, outputClass,
                        carrier(model, outputClass), result.acceptedIdentities())
                : GenerationRuns.narrowedTo(previousPool, outputClass, result.acceptedIdentities());
        List<WikidataDynamicObject> pool = new ArrayList<>(narrowed.pool());

        GeneratedProjectModel snapshot = model.copy();
        GenerationPipeline pipeline = new GenerationPipeline();
        GeneratedViewableRuntime runtime = pipeline.buildRuntime(snapshot);
        List<Viewable> instances = pipeline.materialize(runtime, pool);
        GenerationRun run = new GenerationRun(snapshot, 0,
                RuleTreeCompiler.compileProject(snapshot), pool, runtime, instances, null,
                current == null ? List.of() : current.loadedDeclarations(),
                GenerationRun.Quality.completeQuality(), List.of(),
                GenerationRun.SelfReferenceAudit.notRun(),
                GenerationRun.OwnedCompositionAudit.notRun(),
                GenerationRun.KindClassificationAudit.notRun(),
                GenerationRun.ProjectionAudit.notRun()).producedLike(current);

        Set<String> added = new LinkedHashSet<>(narrowed.kept());
        added.removeAll(previousMembers);
        return new Applied(run, GraphDiscoveryResultStore.applied(result), result.type(),
                outputClass, result.populationOperation(),
                previousMembers.size(), added.size(),
                pool.stream().filter(Objects::nonNull)
                        .filter(value -> value.directClassNames().contains(outputClass)).count(),
                narrowed.ungenerated().size());
    }

    static String message(String graphName, String outputClass, PopulationOperation operation,
                          long previousMembers, long addedMembers, long resultingMembers,
                          long missing, boolean model) {
        String effect = operation == PopulationOperation.ADD
                ? "added " + addedMembers + " generated instance(s) to " + previousMembers
                        + " existing " + outputClass + " instance(s); " + resultingMembers
                        + " instance(s) now belong to " + outputClass
                : "kept " + resultingMembers + " accepted " + outputClass
                        + " instance(s) from " + previousMembers + " existing instance(s)";
        return "Applied graph result \"" + graphName + "\": " + effect
                + (missing == 0 ? "" : ", and " + missing + " accepted id(s) have no "
                        + "generated instance yet — generate to acquire them")
                + ". Use \"Save " + (model ? "model" : "domain")
                + "\" to persist them.";
    }

    /** The stable carrier whose entities an additive graph may classify. */
    private static String carrier(GeneratedProjectModel model, String outputClass) {
        GeneratedClassModel output = model.findClass(outputClass);
        if (output == null || output.baseClassName().isBlank()) return outputClass;
        return output.baseClassName();
    }

    private static Set<String> membersOf(List<WikidataDynamicObject> pool, String className) {
        return pool.stream().filter(Objects::nonNull)
                .filter(value -> value.directClassNames().contains(className))
                .map(WikidataDynamicObject::getIdentifier).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
