package wikidata.explore.workbench;

import datasource.graph.GraphDiscoveryConfiguration;
import dataset.DomainStorage;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The completed annotation set of every graph class in the project — the run results Save
 * writes, Show instances shows, and the next Generate reads pending additions from.
 *
 * <p>It lived in a private map on the graph EDITOR, so a project's run results were owned
 * by a Swing panel: Save and Load reached them through two pass-through layers, detaching
 * the editor cleared them, opening the editor was one of two load paths, and a rerun
 * cleared the held result before the new one existed — losing it to a cancelled run and
 * leaving the manual decisions it carried nothing to be copied from. Results now belong to
 * the project the way its instances do; the editor records into this and reads from it.
 *
 * <p>Keyed by declaration id, so a result stays its class's through a rename; the names it
 * recorded are restamped to the current ones before it is handed out.
 */
final class GraphResults {

    /** Reads a graph class's saved annotation file; a seam so a test need not write one. */
    @FunctionalInterface
    interface SavedResultLoader {
        GraphDiscoveryResultStore.Artifact load(GeneratedClassModel graphClass) throws Exception;
    }

    private final GeneratedProjectModel model;
    private final DomainStorage storage;
    private final Map<String, GraphDiscoveryResultStore.Artifact> held = new LinkedHashMap<>();

    GraphResults(GeneratedProjectModel model, DomainStorage storage) {
        this.model = Objects.requireNonNull(model, "model");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** The result held for one graph class under the names it has now, or null. */
    GraphDiscoveryResultStore.Artifact of(GeneratedClassModel graphClass) {
        if (graphClass == null) return null;
        String key = key(graphClass);
        GraphDiscoveryResultStore.Artifact result = held.get(key);
        if (result == null) return null;
        GraphDiscoveryResultStore.Artifact current = GraphDiscoveryResultStore.renamed(
                result, model.name(), graphClass.className(),
                graphClass.graphSource() == null
                        ? null : graphClass.graphSource().outputClassName());
        if (current != result) held.put(key, current);
        return current;
    }

    /** Every held result whose graph class still exists, in the project's class order. */
    List<GraphDiscoveryResultStore.Artifact> all() {
        return model.graphClasses().stream().map(this::of).filter(Objects::nonNull).toList();
    }

    /**
     * Holds a completed run as its class's result. The manual decisions on the result it
     * replaces are carried onto the entities both share: a decision is the modeller's, and
     * rerunning the graph is not a reason to forget it.
     */
    void record(GeneratedClassModel graphClass, GraphDiscoveryResultStore.Artifact result) {
        if (graphClass == null || result == null) return;
        preserveManualDecisions(of(graphClass), result);
        held.put(key(graphClass), result);
    }

    /** Records the explicit Apply on the result and holds the applied set. */
    GraphDiscoveryResultStore.Artifact markApplied(
            GeneratedClassModel graphClass, GraphDiscoveryResultStore.Artifact result) {
        GraphDiscoveryResultStore.Artifact applied = GraphDiscoveryResultStore.applied(result);
        if (graphClass != null && applied != null) held.put(key(graphClass), applied);
        return applied;
    }

    /** Forgets a class's result because its configuration changed. Nothing else does. */
    void invalidate(GeneratedClassModel graphClass) {
        if (graphClass != null) held.remove(key(graphClass));
    }

    /** Forgets every result, for a project loaded in place of this one. */
    void clear() {
        held.clear();
    }

    /**
     * Restores every graph class's saved result not already held: from the loaded pool,
     * where older applied annotations are reachable from their instances, and otherwise
     * from the class's own annotation file. The one load path — a project load passes an
     * empty pool, Load instances the loaded one. Each file read and each failure is named.
     */
    void restore(Collection<WikidataDynamicObject> loadedObjects, Consumer<String> report) {
        restore(loadedObjects, this::loadSaved, report);
    }

    void restore(Collection<WikidataDynamicObject> loadedObjects, SavedResultLoader loader,
                 Consumer<String> report) {
        Consumer<String> say = report == null ? ignored -> { } : report;
        for (GeneratedClassModel graphClass : model.graphClasses()) {
            if (of(graphClass) != null || graphClass.graphSource() == null) continue;
            GraphDiscoveryResultStore.Artifact restored = GraphDiscoveryResultStore.restore(
                    model.name(), graphClass.className(),
                    graphClass.graphSource().outputClassName(), loadedObjects);
            String file = GraphDiscoveryResultStore.destination(
                    storage, model.name(), graphClass.className()).getPath();
            if (restored == null && loader != null) {
                try {
                    restored = loader.load(graphClass);
                    if (restored != null) {
                        say.accept("Loaded " + restored.instances().size()
                                + " graph annotations \"" + graphClass.className()
                                + "\" from " + file + ".");
                    }
                } catch (Exception error) {
                    say.accept("Could not load graph annotations \"" + graphClass.className()
                            + "\" from " + file + ": " + error.getMessage());
                }
            }
            if (restored != null) held.put(key(graphClass), restored);
        }
    }

    /**
     * Accepted identities of applied additive results that the generated pool does not
     * yet hold as members of the output class, by output class. The annotation set is the
     * persisted fact; the next Generate projects this into its supplemental population.
     */
    Map<String, List<String>> pendingPopulationAdditions(
            Collection<WikidataDynamicObject> generated) {
        return pendingPopulationAdditions(all(), generated);
    }

    static Map<String, List<String>> pendingPopulationAdditions(
            Collection<GraphDiscoveryResultStore.Artifact> results,
            Collection<WikidataDynamicObject> generated) {
        Map<String, LinkedHashSet<String>> pending = new LinkedHashMap<>();
        for (GraphDiscoveryResultStore.Artifact result : results == null
                ? List.<GraphDiscoveryResultStore.Artifact>of() : results) {
            if (!result.applied() || result.populationOperation()
                    != GraphDiscoveryConfiguration.PopulationOperation.ADD
                    || result.outputClass().isBlank()) continue;
            pending.computeIfAbsent(result.outputClass(), ignored -> new LinkedHashSet<>())
                    .addAll(result.acceptedIdentities());
        }
        if (generated != null) {
            pending.forEach((className, qids) -> {
                Set<String> generatedIds = generated.stream().filter(Objects::nonNull)
                        .filter(value -> value.directClassNames().contains(className))
                        .map(WikidataDynamicObject::getIdentifier).filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                qids.removeAll(generatedIds);
            });
        }
        Map<String, List<String>> additions = new LinkedHashMap<>();
        pending.forEach((className, qids) -> {
            if (!qids.isEmpty()) additions.put(className, List.copyOf(qids));
        });
        return Map.copyOf(additions);
    }

    private GraphDiscoveryResultStore.Artifact loadSaved(GeneratedClassModel graphClass)
            throws Exception {
        wikidata.explore.model.GraphClassSource source = graphClass.graphSource();
        if (source == null || source.outputClassName().isBlank()) return null;
        GraphDiscoveryConfiguration.PopulationOperation operation = source.nextNodes().stream()
                .filter(node -> node.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION)
                .map(GraphDiscoveryConfiguration.NextNode::populationOperation)
                .reduce((first, second) -> second)
                .orElse(GraphDiscoveryConfiguration.PopulationOperation.NARROW);
        return GraphDiscoveryResultStore.load(model.name(), graphClass.className(),
                source.outputClassName(), operation, storage);
    }

    private static void preserveManualDecisions(
            GraphDiscoveryResultStore.Artifact previous,
            GraphDiscoveryResultStore.Artifact replacement) {
        if (previous == null || replacement == null || previous == replacement
                || !previous.type().equals(replacement.type())) return;
        Map<String, Object> decisions = new LinkedHashMap<>();
        for (WikidataDynamicObject value : previous.instances()) {
            Object decision = value.get(GraphDiscoveryResultStore.MANUAL_DECISION);
            if (decision != null) decisions.put(value.getIdentifier(), decision);
        }
        for (WikidataDynamicObject value : replacement.instances()) {
            Object decision = decisions.get(value.getIdentifier());
            if (decision != null) {
                GraphDiscoveryResultStore.manualDecision(value, String.valueOf(decision));
            }
        }
    }

    private static String key(GeneratedClassModel graphClass) {
        String id = graphClass.declarationId();
        return id.isBlank() ? graphClass.className() : id;
    }
}
