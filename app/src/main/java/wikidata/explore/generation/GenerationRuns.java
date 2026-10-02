package wikidata.explore.generation;

import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handing the workbench from one generation run to the next.
 *
 * <p>A run owns a compiled runtime — class loaders for the classes it generated — and when a
 * new run replaces it, that runtime has to be closed or the loaders leak. Except when the
 * incoming run is the outgoing one with a detail changed and carries the SAME runtime, which
 * is what happens when a fetched declaration is forgotten: closing there would shut a runtime
 * still in use. Per {@link GeneratedViewableRuntime#close()} that is not fatal — instances
 * already loaded keep working — but further class lookups through those loaders stop, so a
 * run that later maps a new type fails for no visible reason.
 *
 * <p>Both halves of the rule existed and neither was stated: one place closed and one place
 * pointedly did not, each correct, with nothing to consult when writing a third.
 */
public final class GenerationRuns {

    private GenerationRuns() { }

    /** The runtime that nothing will use once {@code next} takes over, or null. */
    public static GeneratedViewableRuntime superseded(GenerationRun previous, GenerationRun next) {
        if (previous == null || previous == next || previous.runtime() == null) return null;
        GeneratedViewableRuntime carriedOver = next == null ? null : next.runtime();
        return previous.runtime() == carriedOver ? null : previous.runtime();
    }

    /**
     * Closes whatever {@code next} supersedes and returns it, for {@code
     * lastRun = GenerationRuns.handOver(lastRun, run)} — so a caller states the handover
     * rather than remembering the rule.
     */
    public static GenerationRun handOver(GenerationRun previous, GenerationRun next) {
        GeneratedViewableRuntime finished = superseded(previous, next);
        if (finished != null) finished.close();
        return next;
    }

    /** What {@link #narrowedTo} produced: the new pool, and what the ids accounted for. */
    public record NarrowedPool(List<WikidataDynamicObject> pool, Set<String> kept,
                               Set<String> ungenerated) { }

    /**
     * Restricts one class's instances to a population given as identities, leaving every
     * other class untouched.
     *
     * <p>A population says WHICH entities belong; it never says what they contain. So the
     * instances that survive are the pool's own — kept whole, with everything acquisition
     * gave them — and an id with no instance behind it stays an id. Building a stand-in
     * object for it would put something in the pool that is indistinguishable from an
     * instance whose acquisition failed, and no later pass can tell the two apart.
     */
    public static NarrowedPool narrowedTo(
            List<WikidataDynamicObject> pool, String outputClass, Set<String> accepted) {
        List<WikidataDynamicObject> narrowed = new java.util.ArrayList<>();
        Set<String> kept = new LinkedHashSet<>();
        Set<String> population = accepted == null ? Set.of() : accepted;
        String className = outputClass == null ? "" : outputClass.trim();
        for (WikidataDynamicObject value : pool == null ? List.<WikidataDynamicObject>of() : pool) {
            if (value == null) continue;
            if (className.isBlank() || !value.directClassNames().contains(className)) {
                narrowed.add(value);
                continue;
            }
            String id = value.getIdentifier();
            if (id != null && population.contains(id)) {
                narrowed.add(value);
                kept.add(id);
            }
        }
        Set<String> ungenerated = new LinkedHashSet<>(population);
        ungenerated.removeAll(kept);
        return new NarrowedPool(List.copyOf(narrowed), Set.copyOf(kept),
                Set.copyOf(ungenerated));
    }

    /**
     * Adds accepted identities to a class without removing its existing members.
     *
     * <p>The identity alone is not enough: a graph annotation and the entity it annotates
     * intentionally share the QID. Only the carrier class can receive the membership.
     * Retracting it from other carriers also repairs snapshots written by the former
     * identity-only implementation.
     */
    public static NarrowedPool addedTo(
            List<WikidataDynamicObject> pool, String outputClass, String carrierClass,
            Set<String> accepted) {
        List<WikidataDynamicObject> expanded = new java.util.ArrayList<>();
        Set<String> kept = new LinkedHashSet<>();
        Set<String> population = accepted == null ? Set.of() : accepted;
        for (WikidataDynamicObject value : pool == null ? List.<WikidataDynamicObject>of() : pool) {
            if (value == null) continue;
            boolean eligible = carrierClass != null && !carrierClass.isBlank()
                    && carrierClass.equals(value.typeKey());
            if (!eligible) {
                value.removeClass(outputClass);
                expanded.add(value);
                continue;
            }
            String id = value.getIdentifier();
            if (id != null && population.contains(id)) {
                value.type(outputClass);
                kept.add(id);
            }
            expanded.add(value);
        }
        Set<String> ungenerated = new LinkedHashSet<>(population);
        ungenerated.removeAll(kept);
        return new NarrowedPool(List.copyOf(expanded), Set.copyOf(kept), Set.copyOf(ungenerated));
    }

    /**
     * The run as the current model names it: every class the model renamed since the run
     * was stamped is restamped on the instances, their owned parts' site keys and the
     * fetched-declaration records, and the run is materialized again against the current
     * model. Returns {@code run} itself when nothing was renamed.
     *
     * <p>A rename keeps a class's declaration id, and nothing reached the instances: they
     * kept claiming the old name, which Save took for a removed class and retracted, so a
     * renamed class's members vanished from the snapshot (#309).
     */
    public static GenerationRun renamedTo(
            GenerationRun run, wikidata.explore.model.GeneratedProjectModel current)
            throws Exception {
        if (run == null || current == null) return run;
        Map<String, String> renames = wikidata.explore.model.ConstructInventory.of(current)
                .renamesSince(wikidata.explore.model.ConstructInventory.of(run.modelSnapshot()));
        if (renames.isEmpty()) return run;
        renameClasses(run.dynamicObjects(), renames);
        List<wikidata.explore.extract.LoadedDeclaration> declarations =
                renamedDeclarations(run.loadedDeclarations(), renames);
        wikidata.explore.model.GeneratedProjectModel snapshot = current.copy();
        GenerationPipeline pipeline = new GenerationPipeline();
        GeneratedViewableRuntime runtime = pipeline.buildRuntime(snapshot);
        return new GenerationRun(snapshot, run.depth(),
                wikidata.explore.rule.RuleTreeCompiler.compileProject(snapshot),
                run.dynamicObjects(), runtime,
                pipeline.materialize(runtime, run.dynamicObjects()), run.remapState(),
                declarations, run.quality(), run.fieldCoverage(), run.selfReferenceAudit(),
                run.ownedCompositionAudit(), run.kindClassificationAudit(),
                run.projectionAudit());
    }

    /**
     * The graph-discovery ledger after {@code run}: what it observed, reconciled with what
     * was known before. The desktop and a build take this one rule.
     */
    public static datasource.graph.GraphDiscoveryState ledgerAfter(
            datasource.graph.GraphDiscoveryState previous, GenerationRun run) {
        datasource.graph.GraphDiscoveryState before = previous == null
                ? datasource.graph.GraphDiscoveryState.EMPTY : previous;
        if (run == null) return before;
        return before.reconcile(
                WikidataGraphDiscoveryState.compute(run.modelSnapshot(), run.dynamicObjects()),
                WikidataGraphExpansionPlan.compile(run.modelSnapshot()).edges(),
                run.quality().complete());
    }

    /** Fetched-declaration records name their class; they follow it like instances do. */
    public static List<wikidata.explore.extract.LoadedDeclaration> renamedDeclarations(
            List<wikidata.explore.extract.LoadedDeclaration> declarations,
            Map<String, String> renames) {
        if (declarations == null) return List.of();
        if (renames == null || renames.isEmpty()) return declarations;
        return declarations.stream()
                .map(value -> new wikidata.explore.extract.LoadedDeclaration(
                        renames.getOrDefault(value.className(), value.className()),
                        value.fieldName(), value.propertyPid(), value.covered(),
                        value.coveredQids()))
                .toList();
    }

    /**
     * Restamps every object reachable from {@code roots} — class claims, and an owned
     * part's site key at both of its ends. One walk for every holder of instances, the
     * generated pool and a graph result's annotations alike.
     */
    public static void renameClasses(
            Collection<WikidataDynamicObject> roots, Map<String, String> renames) {
        if (roots == null || renames == null || renames.isEmpty()) return;
        for (WikidataDynamicObject value
                : wikidata.explore.extract.WikidataObjectGraph.reachable(roots)) {
            String siteKey = value.isPart() ? value.typeKey() : null;
            value.renameClasses(renames);
            if (siteKey != null) {
                value.typeKey(wikidata.explore.transform.OwnedComponents
                        .renamedSiteKey(siteKey, renames));
            }
        }
    }
}
