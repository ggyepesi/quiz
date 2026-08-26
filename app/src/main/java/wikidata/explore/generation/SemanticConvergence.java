package wikidata.explore.generation;

import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.OwnedComponents;
import wikidata.explore.transform.ReferentClassStamp;
import wikidata.explore.transform.ReferentFieldLoad;
import wikidata.explore.transform.ReferentKindClassifier;
import wikidata.explore.transform.SnapshotEntityKindClassifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit semantic worklist. Each iteration stamps roles, loads newly reachable
 * declarations/evidence, classifies kinds, constructs owned values, and repeats only
 * when that work made more graph structure reachable.
 *
 * <p><b>Why it terminates.</b> Every step here is idempotent over its own output, so a
 * pass that finds the work already done reports zero and the loop stops. That is a
 * property of the steps, not of this class, and one of them is easy to break:
 * classification RETRACTS a role stamp when it assigns a kind, so
 * {@link ReferentClassStamp} must not put that role back — otherwise stamping and
 * classifying undo each other, no pass is ever unproductive, and every generation runs
 * {@link #MAX_ITERATIONS} full passes, each with network work in it. The same holds for
 * {@link OwnedComponents}, which must find an existing part rather than compose a second
 * one. Both are guarded by tests; {@code MAX_ITERATIONS} is the backstop, not the plan.
 */
public final class SemanticConvergence {
    public static final int MAX_ITERATIONS = 12;

    public record Result(
            int iterations, int loadedFields, int classifiedKinds, int ownedCreated,
            List<WikidataDynamicObject> ownedComponentsCreated,
            List<WikidataDynamicObject> newlyClassifiedKinds,
            Map<String, LoadedDeclaration> completedDeclarations,
            List<LoadedDeclaration> unresolvedLoads,
            Set<String> unresolvedKindQids) { }

    private SemanticConvergence() { }

    public static Result apply(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            WikidataApiClient api,
            GenerationLog log,
            Collection<LoadedDeclaration> alreadyLoaded,
            GenerationQualityTracker quality) {
        return apply(model, pool, api, log, alreadyLoaded, quality, null);
    }

    public static Result apply(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            WikidataApiClient api,
            GenerationLog log,
            Collection<LoadedDeclaration> alreadyLoaded,
            GenerationQualityTracker quality,
            datasource.api.SourceExecutionPlan sourcePlan) {
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        Map<String, LoadedDeclaration> completed = new LinkedHashMap<>();
        if (alreadyLoaded != null) alreadyLoaded.forEach(d -> completed.put(d.key(), d));
        Map<String, LoadedDeclaration> failed = new LinkedHashMap<>();
        Set<String> unresolvedKinds = new LinkedHashSet<>();
        int loaded = 0, classified = 0, owned = 0;
        List<WikidataDynamicObject> ownedCreated = new ArrayList<>();
        List<WikidataDynamicObject> kindsClassified = new ArrayList<>();
        int iteration;
        int productiveIterations = 0;
        ReferentFieldLoad.AcquisitionManifest acquisition =
                ReferentFieldLoad.compileManifest(
                        model, GenerationFactDemandPlan.compile(model, sourcePlan).all());
        if (!acquisition.propertiesByClass().isEmpty()) {
            sink.message("Semantic acquisition manifest: "
                    + acquisition.propertiesByClass().entrySet().stream()
                    .map(e -> e.getKey() + "=[" + String.join(", ", e.getValue()) + "]")
                    .collect(java.util.stream.Collectors.joining("; ")) + ".\n");
        }

        for (iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            long fetchedBefore = api.facts().fetchedDocuments();
            long hitsBefore = api.facts().cacheHits();
            int stamped = ReferentClassStamp.apply(model, pool);
            ReferentFieldLoad.RetentionPlan retention =
                    ReferentFieldLoad.planRetention(
                            pool, api, acquisition, completed.values());
            sink.message("Semantic retention preflight iteration " + iteration + ": "
                    + retention.factPairs() + " planned QID/property pair(s) across "
                    + retention.entities() + " class member(s) in "
                    + retention.classes() + " class(es), registered before acquisition; "
                    + retention.coveredPairs() + " pair(s) already loaded and not "
                    + "planned again.\n");
            ReferentFieldLoad.Result fields = ReferentFieldLoad.load(
                    model, pool, api, sink, completed.values(), true, acquisition,
                    sourcePlan == null ? model.classes().stream()
                            .filter(java.util.Objects::nonNull)
                            .map(c -> c.className())
                            .collect(java.util.stream.Collectors.toSet())
                            : wikidata.explore.model.ClassNameSourcePlan.aliases(sourcePlan));
            loaded += fields.loaded();
            fields.completed().forEach(done -> {
                completed.put(done.key(), done);
                LoadedDeclaration prior = failed.get(done.key());
                if (prior != null && done.coveredQids().containsAll(prior.coveredQids())) {
                    failed.remove(done.key());
                    if (quality != null) quality.resolved(done.key(), prior.coveredQids());
                }
            });
            fields.failed().forEach(problem -> {
                failed.merge(problem.key(), problem, SemanticConvergence::union);
                if (quality != null) quality.failed(problem.key(),
                        "Unresolved field load " + problem.className() + "."
                                + problem.fieldName() + " (" + problem.propertyPid() + ")",
                        problem.coveredQids());
            });

            SnapshotEntityKindClassifier.Result stored =
                    SnapshotEntityKindClassifier.apply(model, pool, pool, sink);
            ReferentKindClassifier.Result remote = ReferentKindClassifier.apply(
                    model, pool, api, sink, stored.withoutStoredEvidenceQids(),
                    nameMetadata(sourcePlan));
            classified += stored.classified() + remote.classified();
            kindsClassified.addAll(stored.newlyClassified());
            Set<String> currentUnavailable = new LinkedHashSet<>(remote.unavailableQids());
            if (!unresolvedKinds.isEmpty() && quality != null) {
                Set<String> repaired = new LinkedHashSet<>(unresolvedKinds);
                repaired.removeAll(currentUnavailable);
                quality.resolved("entity-kind-evidence", repaired);
            }
            unresolvedKinds.clear();
            unresolvedKinds.addAll(currentUnavailable);
            if (quality != null && !currentUnavailable.isEmpty()) {
                quality.failed("entity-kind-evidence", "Entity-kind evidence unavailable",
                        currentUnavailable);
            }

            OwnedComponents.Result made = OwnedComponents.apply(model, pool, null, sink);
            made.addTo(pool);
            int componentStamps = ReferentClassStamp.apply(model, made.components());
            owned += made.created();
            ownedCreated.addAll(made.createdComponents());

            sink.message("Semantic convergence iteration " + iteration + ": "
                    + stamped + " role stamp(s), " + fields.loaded() + " field value(s), "
                    + (stored.classified() + remote.classified()) + " kind(s), "
                    + made.created() + " owned value(s).\n");
            sink.message("Semantic fact reuse iteration " + iteration + ": "
                    + (api.facts().fetchedDocuments() - fetchedBefore)
                    + " document(s) fetched, "
                    + (api.facts().cacheHits() - hitsBefore)
                    + " fetch(es) avoided.\n");
            boolean productive = stamped != 0 || fields.loaded() != 0
                    || stored.classified() + remote.classified() != 0
                    || made.created() != 0 || componentStamps != 0;
            if (!productive) {
                sink.message("Semantic convergence fixed point reached after "
                        + productiveIterations + " productive iteration(s).\n");
                break;
            }
            productiveIterations++;
        }
        if (iteration > MAX_ITERATIONS) {
            if (quality != null) quality.failed("semantic-convergence",
                    "Semantic convergence exceeded " + MAX_ITERATIONS + " iterations");
            sink.message("WARNING: semantic convergence reached its "
                    + MAX_ITERATIONS + " iteration safety limit.\n");
            productiveIterations = MAX_ITERATIONS;
        }
        return new Result(productiveIterations, loaded, classified, owned,
                List.copyOf(ownedCreated), List.copyOf(kindsClassified), Map.copyOf(completed),
                List.copyOf(failed.values()), Set.copyOf(unresolvedKinds));
    }

    private static Set<wikidata.api.FactDemand.EntityMetadata> nameMetadata(
            datasource.api.SourceExecutionPlan sourcePlan) {
        if (sourcePlan == null) return wikidata.api.FactDemand.allMetadata();
        java.util.EnumSet<wikidata.api.FactDemand.EntityMetadata> metadata =
                java.util.EnumSet.noneOf(wikidata.api.FactDemand.EntityMetadata.class);
        if (!wikidata.explore.model.ClassNameSourcePlan.labels(sourcePlan).isEmpty())
            metadata.add(wikidata.api.FactDemand.EntityMetadata.LABEL);
        if (!wikidata.explore.model.ClassNameSourcePlan.aliases(sourcePlan).isEmpty())
            metadata.add(wikidata.api.FactDemand.EntityMetadata.ALIASES);
        return metadata;
    }

    private static LoadedDeclaration union(LoadedDeclaration left, LoadedDeclaration right) {
        LinkedHashSet<String> qids = new LinkedHashSet<>(left.coveredQids());
        qids.addAll(right.coveredQids());
        return new LoadedDeclaration(left.className(), left.fieldName(), left.propertyPid(), qids);
    }
}
