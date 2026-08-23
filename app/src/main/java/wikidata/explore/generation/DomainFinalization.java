package wikidata.explore.generation;

import wikidata.api.WikidataApiClient;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Collection;
import java.util.List;

/** Shared post-convergence stages for Generate, Enrich and Remap. */
public final class DomainFinalization {
    public record Result(int dead, int disambiguation, int orphans, int requiredDropped,
                         int ownedRenamed,
                         List<wikidata.explore.transform.FieldExpectations.FieldCoverage>
                                 coverage) {
        public Result {
            coverage = List.copyOf(coverage == null ? List.of() : coverage);
        }

        /** Back-compat: a result with no coverage report. */
        public Result(int dead, int disambiguation, int orphans, int requiredDropped) {
            this(dead, disambiguation, orphans, requiredDropped, 0, List.of());
        }
    }

    private DomainFinalization() { }

    public static Result apply(
            GeneratedProjectModel model,
            CompiledProjectModel compiled,
            List<WikidataDynamicObject> pool,
            Collection<WikidataDynamicObject> consistencyRecords,
            WikidataApiClient api,
            GenerationLog log) throws Exception {
        return apply(model, compiled, pool, consistencyRecords, pool, api, log);
    }

    public static Result apply(
            GeneratedProjectModel model,
            CompiledProjectModel compiled,
            List<WikidataDynamicObject> pool,
            Collection<WikidataDynamicObject> consistencyRecords,
            Collection<WikidataDynamicObject> vocabularyEvidence,
            WikidataApiClient api,
            GenerationLog log) throws Exception {
        int[] counts = new int[5];
        // What EXPECTED found is the point of declaring it, so it leaves this stage as
        // a value rather than only a log line (#96).
        List<wikidata.explore.transform.FieldExpectations.FieldCoverage> coverage =
                new java.util.ArrayList<>();
        List<GenerationStage> stages = List.of(
                stage("canonicalize", "Canonicalize final names",
                        () -> wikidata.explore.transform.Canonicalization.apply(
                                compiled, pool, log)),
                // After canonicalize, because that stage can move an owner's own name,
                // and a part is named for its owner. Before the prunes, so anything
                // dropped below is dropped under the name it will be reported by.
                stage("owned-part-names", "Settle owner-derived component names",
                        () -> counts[4] = wikidata.explore.transform.OwnedComponents
                                .recomposeNames(model, pool)),
                stage("dead-stubs", "Prune explicitly missing entities", () -> {
                    var dead = wikidata.explore.transform.DeadStubPrune.apply(pool, log);
                    counts[0] = dead.size();
                    pool.removeIf(dead::contains);
                }),
                stage("disambiguation", "Prune Wikimedia internal entities", () -> {
                    if (api == null) return;
                    var bad = wikidata.explore.transform.DisambiguationPrune.apply(
                            model, pool, api, log);
                    counts[1] = bad.size();
                    pool.removeIf(bad::contains);
                }),
                stage("orphans", "Prune unreachable untyped entities", () -> {
                    var orphans = wikidata.explore.transform.OrphanPrune.apply(pool, log);
                    counts[2] = orphans.size();
                    pool.removeIf(orphans::contains);
                }),
                stage("expectations", "Apply field expectations", () -> {
                    var expectations = wikidata.explore.transform.FieldExpectations.apply(
                            compiled, pool, log);
                    counts[3] = expectations.dropped().size();
                    coverage.addAll(expectations.coverage());
                }),
                stage("vocabularies", "Build descriptive vocabularies",
                        () -> wikidata.explore.transform.DescriptiveVocabularyBuild.apply(
                                model, vocabularyEvidence == null ? pool
                                        : new java.util.ArrayList<>(vocabularyEvidence),
                                log)),
                stage("consistency", "Audit final statement records", () -> {
                    if (consistencyRecords != null) {
                        wikidata.explore.transform.ConsistencyReport.check(
                                compiled, new java.util.ArrayList<>(consistencyRecords), log);
                    }
                }));
        for (GenerationStage stage : stages) stage.execute();
        return new Result(counts[0], counts[1], counts[2], counts[3], counts[4],
                coverage);
    }

    private static GenerationStage stage(String id, String title, Checked action) {
        return new GenerationStage() {
            @Override public String id() { return id; }
            @Override public String title() { return title; }
            @Override public void execute() throws Exception { action.run(); }
        };
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
