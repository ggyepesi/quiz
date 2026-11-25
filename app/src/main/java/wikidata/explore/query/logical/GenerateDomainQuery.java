package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import objectview.Viewable;
import domain.compile.codegen.GeneratedViewableRuntime;
import domain.runtime.GenerationLog;
import domain.runtime.WikidataDynamicObject;
import domain.runtime.WikidataObjectRegistry;
import domain.build.GenerationPipeline;
import domain.build.GenerationRun;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import wikidata.query.core.Datasource;
import work.Query;
import work.QueryContext;
import domain.compile.rule.RuleNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wikidata.query.core.WikidataAccess;
import wikidata.api.FactDemandPlan;

/**
 * Generates EVERY generatable class in the domain as its own root, pooling all
 * runs into ONE shared registry, so the saved snapshot carries each class as
 * its own type ({@code registerAll} then serves them all). Inline references
 * (father / facetOf / characters …) resolve to the same shared, type-stamped
 * objects — an entity referenced by one class and generated as another's root
 * is a single object stamped with its class.
 */
public class GenerateDomainQuery implements Query<GenerationRun> {

    private final GeneratedProjectModel project;
    private final domain.build.CompiledPipelineRun compiledRun;
    private final process.ProcessWorkflowPipeline pipelineProgress;
    private final domain.build.GenerationExecutionSettings executionSettings;
    private final domain.build.GenerationRecoveryStore recovery;

    public GenerateDomainQuery(GeneratedProjectModel project) {
        this(domain.build.CompiledPipelineRun.compile(
                        domain.build.PipelineRequest.generateDomain(project)),
                null, new domain.build.GenerationExecutionSettings(), null);
    }

    public GenerateDomainQuery(
            domain.build.CompiledPipelineRun compiledRun,
            process.ProcessWorkflowPipeline pipelineProgress,
            domain.build.GenerationExecutionSettings executionSettings) {
        this(compiledRun, pipelineProgress, executionSettings, null);
    }

    public GenerateDomainQuery(
            domain.build.CompiledPipelineRun compiledRun,
            process.ProcessWorkflowPipeline pipelineProgress,
            domain.build.GenerationExecutionSettings executionSettings,
            domain.build.GenerationRecoveryStore recovery) {
        if (compiledRun == null) throw new IllegalArgumentException("No compiled pipeline run");
        this.compiledRun = compiledRun;
        this.project = compiledRun.request().model();
        this.pipelineProgress = pipelineProgress;
        this.executionSettings = executionSettings == null
                ? new domain.build.GenerationExecutionSettings()
                : executionSettings;
        this.recovery = recovery;
    }

    @Override public String purpose() { return "Generate domain"; }

    @Override public String skeleton() {
        return "each class as a root -> shared registry -> one multi-type snapshot";
    }

    @Override public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("domain", project.name());
        p.put("classes", String.valueOf(project.classes().size()));
        p.put("cacheMb", String.valueOf(executionSettings.resolvedMemoryMb()));
        p.put("entityConcurrency", String.valueOf(executionSettings.concurrency()));
        p.put("requireComplete", String.valueOf(executionSettings.requireComplete()));
        return p;
    }

    @Override
    public GenerationRun execute(QueryContext context) throws Exception {
        // The very first executable decision. In particular, unit lookup below can
        // reach WDQS, so an invalid plan must stop before source-plan preparation or
        // any other externally backed setup.
        if (compiledRun.blocked()) {
            throw new IllegalStateException(compiledRun.explain());
        }
        // The plan is the single resolved inventory. Consumers still migrate one
        // operation family at a time at their existing batching/cache boundary.
        domain.datasource.api.SourceExecutionPlan sourcePlan =
                domain.config.ModelSourceExecutionPlan.synchronizeAndCompile(
                        project, domain.datasource.Datasources.standard());
        context.message(domain.config.ModelSourceExecutionPlan.generationMessage(
                sourcePlan));
        return context.step(
                "Generate domain \"" + project.name() + "\"",
                "Domain",   // container node, not a SPARQL query (no "Open in query service")
                null,
                Map.of("domain", project.name()),
                step -> {
                    // One structured log for every long run (see Enrich): each
                    // request becomes its own entry, an in-flight one included.
                    GenerationLog genLog = StepGenerationLog.of(context, step);
                    // Every endpoint this run can reach reports its requests, and their
                    // timings, into THIS run's log.
                    try (WikidataAccess.RequestLogs requestLogs =
                            WikidataAccess.logRequests(context, genLog)) {
                    genLog.message(executionSettings.resolvedDescription());
                    phase(domain.build.GenerateDomainPipeline.PLAN,
                            project.classes().size() + " configured classes");

                    GenerationPipeline pipeline = new GenerationPipeline();
                    WikidataObjectRegistry shared = new WikidataObjectRegistry();
                    wikidata.api.WikidataApiClient entityApi =
                            new wikidata.api.WikidataApiClient(
                                    wikidata.api.WikidataApiClient.DEFAULT_USER_AGENT)
                                    .facts(executionSettings.newFactStore())
                                    .entityConcurrency(executionSettings.concurrency())
                                    .cancellation(context.cancellation());
                    // This client is created for the run rather than bound in
                    // WikidataAccess, so logRequests(...) cannot discover it. Without
                    // this explicit attachment the HTTP/2 version/header/body metrics
                    // are printed nowhere and the saved log can only show batch time.
                    entityApi.log(WikidataAccess.structuredRequestLog(
                            Datasource.WIKIDATA, genLog));

                    // ONE runtime for the whole domain — every class compiled
                    // together in one package/loader, so typed cross-references
                    // (Character <-> Episode) resolve and map to shared typed
                    // instances rather than raw objects.
                    // Resolve each quantity field's unit once (the truthy value
                    // drops it), so the mapper can render "1538 K". Before building
                    // the runtime, so it holds fully-resolved field models.
                    pipeline.resolveUnits(project, WikidataAccess.sparql(context, Datasource.WIKIDATA), genLog);

                    domain.compile.CompiledProjectModel compiledProject =
                            compiledRun.model();
                    domain.build.GenerationState generationState =
                            new domain.build.GenerationState(
                                    project, compiledProject, entityApi.facts());
                    completePhase(domain.build.GenerateDomainPipeline.PLAN,
                            "Model compiled");
                    phase(domain.build.GenerateDomainPipeline.DISCOVER,
                            project.classes().size() + " configured classes");

                    RuleNode rootPlan = null;
                    int classesRun = 0;
                    int childQueryFailures = 0;
                    FactDemandPlan factDemandPlan =
                            domain.build.GenerationFactDemandPlan.compile(
                                    project, sourcePlan);
                    dataset.DomainStorage domainStorage = context.optional(
                            dataset.DomainStorage.class);
                    domain.build.ImportedMaterializedPopulationInputs
                            materializedInputs =
                            domain.build.ImportedMaterializedPopulationInputs
                                    .resolve(project, domainStorage);
                    for (GeneratedClassModel cls : project.classes()) {
                        domain.build.PopulationSourceExecution.Resolution
                                populationInput =
                                domain.build.PopulationSourceExecution.resolve(
                                        project, cls, sourcePlan, materializedInputs)
                                        .acquire(context, genLog);
                        List<String> supplemental = compiledRun.request()
                                .supplementalPopulation(cls.className());
                        if (!populationInput.available() && supplemental.isEmpty()) {
                            genLog.message("Skip class \"" + cls.className()
                                    + "\" — " + populationInput.reason() + ".\n");
                            continue;
                        }
                        GeneratedProjectModel rooted = rootedAt(cls.className());
                        RuleNode configuredPlan = populationInput.available()
                                && populationInput.requiresRootQuery()
                                ? pipeline.plan(rooted) : null;
                        RuleNode supplementalPlan = supplemental.isEmpty()
                                ? null : pipeline.plan(rooted);
                        List<RuleNode> populationPlans = populationPlans(
                                configuredPlan, supplementalPlan,
                                populationInput, supplemental);
                        if (populationInput.importedPopulation()) {
                            genLog.message("Load imported population "
                                    + String.join(", ", populationInput.selectionNames())
                                    + ": " + populationInput.qids().size()
                                    + " " + cls.className() + " QIDs.\n");
                        }
                        if (populationInput.importedMaterialized()) {
                            populationInput.materializedObjects().forEach(shared::adoptIfAbsent);
                            genLog.message("Load published materialized population "
                                    + String.join(", ", populationInput.selectionNames())
                                    + ": " + populationInput.materializedObjects().size()
                                    + " typed " + cls.className() + " instances"
                                    + (populationInput.qidsRequiringAcquisition().isEmpty()
                                        ? "; no Wikidata population request.\n"
                                        : "; " + populationInput.qidsRequiringAcquisition().size()
                                            + " additional published QIDs will be acquired.\n"));
                        }
                        if (!supplemental.isEmpty()) {
                            genLog.message("Load " + supplemental.size()
                                    + " pending " + cls.className()
                                    + " QIDs from applied additive graph results.\n");
                        }
                        genLog.message("=== Class \"" + cls.className()
                                + "\" (depth " + cls.generationDepth() + ") ===\n");

                        int rootsFound = populationInput.materializedObjects().size();
                        for (RuleNode plan : populationPlans) {
                            GenerationPipeline.ExtractionResult extraction =
                                    pipeline.extractResult(
                                    WikidataAccess.sparql(context, Datasource.WIKIDATA), plan,
                                    cls.generationDepth(), genLog, shared,
                                    context.cancellation(), entityApi,
                                    factDemandPlan.forClass(cls.className()));
                            rootsFound += extraction.objects().size();
                            childQueryFailures += extraction.childQueryFailures();
                            if (rootPlan == null) rootPlan = plan;
                            classesRun++;
                        }
                        genLog.message("  -> " + rootsFound + " "
                                + cls.className() + "\n");
                        progress(domain.build.GenerateDomainPipeline.DISCOVER,
                                classesRun + " root class query(ies) completed");
                    }

                    completePhase(domain.build.GenerateDomainPipeline.DISCOVER,
                            classesRun + " root class query(ies) completed");
                    phase(domain.build.GenerateDomainPipeline.ACQUIRE_STATEMENTS,
                            "Loading statements and qualifiers");
                    String statementPlan = statementAcquisitionPlan(compiledProject);
                    progress(
                            domain.build.GenerateDomainPipeline.ACQUIRE_STATEMENTS,
                            statementPlan);
                    genLog.message("Statement acquisition plan: " + statementPlan + "\n");
                    // STATEMENT-reification classes (e.g. Nomination = the P1411
                    // statements of Oscarnominations, with year/forWork/nominee
                    // qualifier fields): load the qualifiers + reify into records.
                    // The registry has no add, so carry the new records alongside.
                    // Split enrich (network) / reify (pure) so we can cache the
                    // ENRICHED pool for an offline Remap re-transform. Deep-copy it
                    // BEFORE reify mutates the statements in place.
                    List<WikidataDynamicObject> reifyPool =
                            new ArrayList<>(shared.values());
                    // Reify from the COMPILED model (statement source, fields and
                    // canonical identity already resolved). Byte-for-byte parity
                    // with the editable-model derivation is proven; the compile also
                    // fails fast if the model is structurally invalid.
                    domain.compile.transform.ModelStatementReifications.AcquisitionReport
                            statementAcquisition =
                            domain.compile.transform.ModelStatementReifications
                                    .enrichWithReport(
                                            compiledProject, reifyPool,
                                            WikidataAccess.sparql(context, Datasource.WIKIDATA), genLog,
                                            entityApi, true, factDemandPlan,
                                            context.cancellation());
                    progress(
                            domain.build.GenerateDomainPipeline.ACQUIRE_STATEMENTS,
                            statementAcquisition.summary());
                    completePhase(
                            domain.build.GenerateDomainPipeline.ACQUIRE_STATEMENTS,
                            statementAcquisition.summary());
                    phase(domain.build.GenerateDomainPipeline.CONSTRUCT,
                            "Constructing statement records and derived fields");
                    // A source-class-less reify DISCOVERS its subjects, which enrich
                    // added to reifyPool but not to the shared registry. Fold them in
                    // (same instances) so they flow through every transform below and
                    // reach the served pool as referents — their internal __subject_
                    // type is un-stamped before save.
                    for (WikidataDynamicObject o : reifyPool) {
                        shared.adoptIfAbsent(o);
                    }
                    List<WikidataDynamicObject> enrichedSnapshot =
                            domain.compile.transform.PoolCopy.deepCopy(shared.values());

                    // The ONE transform sequence (#97). Generate differs from Remap only
                    // in that it FETCHES the companion sets instead of replaying cached
                    // ones — and it caches them here for the next Remap.
                    // One state for the whole run from here on: construction, the
                    // worklist, finalization and materialization all advance it, and each
                    // declares the stage it needs — so the order is checked rather than
                    // trusted to the order these lines happen to be written in.
                    domain.build.PipelineState runState =
                            domain.build.PipelineState.over(
                                    domain.build.GraphCheckpoint.Stage
                                            .NORMALIZED_SOURCE_GRAPH, reifyPool);
                    domain.build.PipelineContext runContext =
                            new domain.build.PipelineContext(
                                    compiledRun, entityApi, genLog, context.cancellation());
                    new domain.build.PipelineExecutor()
                            .with(domain.build.ConstructRecordsStep.acquiring(
                                    records -> {
                                        try {
                                            return domain.compile.transform.CompanionMatch
                                                    .loadSets(compiledProject, records,
                                                            WikidataAccess.sparql(
                                                                    context, Datasource.WIKIDATA),
                                                            genLog);
                                        } catch (RuntimeException failed) {
                                            throw failed;
                                        } catch (Exception failed) {
                                            throw new RuntimeException(failed);
                                        }
                                    }))
                            .run(runContext, runState);
                    domain.compile.transform.StatementTransforms.Result transformed =
                            runState.construction();
                    List<WikidataDynamicObject> reified = transformed.reified();
                    java.util.Map<String, java.util.Set<java.util.List<String>>>
                            companionSets = transformed.companionSets();

                    // reifyPool is now the served set: the reified records added, the
                    // demoted duplicates removed, no internal plumbing left. The registry
                    // keeps every object it already knew, so the snapshot still carries
                    // them; what is SERVED is this pool.
                    List<WikidataDynamicObject> pool = reifyPool;

                    completePhase(domain.build.GenerateDomainPipeline.CONSTRUCT,
                            reified.size() + " statement record(s)");
                    phase(domain.build.GenerateDomainPipeline.SEMANTIC,
                            "Role stamps, fields, kinds and owned values");
                    // First acquisition pass: load fields on the role classes that are
                    // already known — e.g. Nominee.type (P31), ForWork.genre (P136).
                    // This evidence is then reused by the snapshot classifier instead
                    // of immediately issuing the same P31 request a second time.
                    // Load a referenced-only class's DECLARED entity property-fields
                    // onto its (now class-stamped) referents — e.g. Nominee.type (P31),
                    // ForWork.genre (P136). No-op unless such fields are declared, so
                    // it costs nothing until the model opts in. Needs wbgetentities;
                    // mirror QualifierLoader's own client.
                    // Roots = the shared pool AND the reified records: a referenced
                    // class's members may live ONLY nested inside a reified record
                    // (e.g. Ceremony as a Nomination's P805 qualifier value, never a
                    // top-level subject), and ReferentFieldLoad flattens the reachable
                    // graph to find them.
                    List<WikidataDynamicObject> referentLoadRoots = pool;
                    // Through load(), not apply(): a generation fetches every declared
                    // property, and the run must RECORD which — otherwise the snapshot
                    // it saves claims nothing has been fetched, and the first enrich
                    // after a full generation re-asks Wikidata for all of it.
                    domain.build.GenerationQualityTracker qualityTracker =
                            generationState.quality();
                    new domain.build.PipelineExecutor()
                            .with(new domain.build.SemanticWorklistStep(
                                    sourcePlan, qualityTracker))
                            .run(runContext, runState);
                    domain.build.SemanticConvergence.Result convergence =
                            runState.convergence();
                    java.util.Map<String, domain.runtime.LoadedDeclaration>
                            completedReferentLoads = new java.util.LinkedHashMap<>(
                                    convergence.completedDeclarations());
                    int loadedReferentFields = convergence.loadedFields();
                    if (loadedReferentFields > 0) {
                        genLog.message("Loaded " + loadedReferentFields
                                + " referent/owned field value(s) from declared PIDs.\n");
                    }
                    completePhase(domain.build.GenerateDomainPipeline.SEMANTIC,
                            convergence.iterations() + " semantic iteration(s)");
                    phase(domain.build.GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                            domain.build.StandardExternalSourceFamilies
                                    .describe(sourcePlan));
                    domain.build.ExternalSourceAcquisition.Applied acquired =
                            domain.build.ExternalSourceAcquisition
                                    .applyWithSemanticReplay(
                                    project, pool, sourcePlan,
                                    domain.build.StandardExternalSourceFamilies
                                            .services(WikidataAccess.sparql(
                                                    context, Datasource.WIKIDATA),
                                                    WikidataAccess.sparql(
                                                    context, Datasource.DBPEDIA), entityApi),
                                    genLog, context.cancellation(),
                                    domain.build.ExternalSourceAcquisition
                                            .FailurePolicy.STRICT,
                                    completedReferentLoads.values(), qualityTracker);
                    domain.build.ExternalSourceAcquisition.Result external =
                            acquired.sources();
                    if (!external.complete()) {
                        qualityTracker.failed("external-source",
                                String.join("; ", external.failures()));
                    }
                    convergence = domain.build.SemanticConvergence.combine(
                            convergence, acquired.semantic());
                    completedReferentLoads.clear();
                    completedReferentLoads.putAll(convergence.completedDeclarations());
                    loadedReferentFields = convergence.loadedFields();
                    String acquiredExternal = external.acquiredSummary();
                    if (!acquiredExternal.isBlank()) {
                        progress(domain.build.GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                                acquiredExternal + " acquired");
                    }
                    completePhase(
                            domain.build.GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                            external.summary());
                    phase(domain.build.GenerateDomainPipeline.LABELS,
                            "Hydrating final reachable QIDs");
                    domain.build.FinalLabelHydration.Result labels =
                            domain.build.FinalLabelHydration.apply(
                                    pool, entityApi, genLog, qualityTracker,
                                    project, sourcePlan);
                    completePhase(domain.build.GenerateDomainPipeline.LABELS,
                            labels.resolved() + " label(s) resolved");
                    phase(domain.build.GenerateDomainPipeline.FINALIZE,
                            "Canonicalize, prune, validate and build vocabularies");
                    // Through the executor, which owns the order. Two calls rather than
                    // one because ~120 lines of reporting sit between the two phases and
                    // moving them would change what a reader sees mid-run; the ordering
                    // guarantee does not come from a single call but from the shared
                    // state — MaterializeStep requires a FINAL_GRAPH, and only
                    // FinalizeStep leaves one.
                    //
                    // The same state construction and the worklist advanced: pool IS
                    // reifyPool, and the records are the ones the construct step left.
                    new domain.build.PipelineExecutor()
                            .with(new domain.build.FinalizeStep())
                            .run(runContext, runState);
                    domain.build.DomainFinalization.Result finalization =
                            runState.finalization();
                    // Fetched vs avoided says whether the cache paid; the eviction
                    // figures say WHY. A poor hit rate with no evicted re-fetches means
                    // the consumers never ask about the same entity and a larger budget
                    // would buy nothing; a poor hit rate with many of them means they do
                    // meet, just not while the document is still held.
                    wikidata.api.WikidataFactStore facts = generationState.facts();
                    var aliases = entityApi.aliasMetrics();
                    if (aliases.requests() > 0) {
                        genLog.message("Alias acquisition: aliases rode "
                                + (aliases.requests() - aliases.standaloneRequests())
                                + " entity request(s), " + aliases.standaloneRequests()
                                + " standalone request(s) of which "
                                + aliases.failures() + " failed, " + aliases.entities()
                                + " entity answer(s), ~"
                                + aliases.responseBytes() / 1024
                                + " KB alias-bearing response JSON, ~"
                                + aliases.transferredBytes() / 1024
                                + " KB transferred on the wire; aliases themselves ~"
                                + aliases.valueBytes() / 1024 + " KB; "
                                + aliases.elapsedMillis()
                                + " ms aggregate request time (concurrent, not wall clock).\n");
                    }
                    genLog.message("Wikidata fact store: "
                            + facts.fetchedDocuments() + " entity document(s) fetched, "
                            + facts.cacheHits() + " later fetch(es) avoided; holding "
                            + facts.size() + " document(s) (~"
                            + (facts.estimatedBytes() / (1024 * 1024)) + " MB), "
                            + facts.evictions() + " evicted, "
                            + facts.evictedRefetches()
                            + " re-fetch(es) of an evicted document"
                            + (facts.oversized() == 0 ? "" : ", " + facts.oversized()
                                    + " too large to retain")
                            + (facts.unplannedEvictions() == 0 ? ""
                                    : ", " + facts.unplannedEvictions()
                                    + " of them holding facts nobody planned to re-read")
                            + ".\n");
                    // The budget is a profile the reader chose, so say when the run has
                    // nearly used it: a domain that fits today evicts tomorrow, and the
                    // AUTO profile never grows past its own ceiling on its own.
                    long budget = facts.maxEstimatedBytes();
                    if (budget > 0 && facts.estimatedBytes() * 10L >= budget * 9L) {
                        genLog.message("Fact store held ~"
                                + (facts.estimatedBytes() * 100L / budget)
                                + "% of its " + (budget / (1024 * 1024))
                                + " MB budget — a larger memory profile buys headroom "
                                + "before this domain starts evicting.\n");
                    }
                    genLog.message("Fact-demand timing: "
                            + facts.preplannedDemandPairs()
                            + " QID/property demand pair(s) known or retained before "
                            + "first acquisition, " + facts.lateDemandPairs()
                            + " unplanned demand pair(s) discovered after the entity "
                            + "had already been fetched; " + facts.cacheHits()
                            + " request(s) avoided by retained facts.\n");
                    genLog.message("Fact-store measurement: ~"
                            + facts.measurementEstimatedBytes() / 1024 + " KB"
                            + (facts.measurementTruncated()
                                    ? " (detail truncated at the measurement budget)"
                                    : "") + ".\n");
                    java.util.List<wikidata.api.WikidataFactStore.PropertyUsage> usage =
                            facts.propertyUsage();
                    if (!usage.isEmpty()) {
                        genLog.message("Fact-store property usage (banked / demanded / "
                                + "unused, retained bytes, cache hits, eviction re-fetches, "
                                + "late demands):\n");
                        for (var property : usage) {
                            genLog.message("  " + property.propertyPid() + ": "
                                    + property.bankedEntities() + " / "
                                    + property.demandedEntities() + " / "
                                    + property.unusedEntities() + ", ~"
                                    + property.estimatedBytes() / 1024 + " KB retained, ~"
                                    + property.unusedEstimatedBytes() / 1024
                                    + " KB unused, " + property.cacheHits()
                                    + " hit(s), " + property.evictedRefetches()
                                    + " eviction re-fetch(es), "
                                    + property.lateDemands() + " late demand(s).\n");
                        }
                    }
                    if (!facts.demandsBySource().isEmpty()) {
                        genLog.message("Fact demand by consumer: "
                                + facts.demandsBySource().entrySet().stream()
                                .map(e -> e.getKey() + " [" + e.getValue().entrySet().stream()
                                        .map(p -> p.getKey() + "=" + p.getValue())
                                        .collect(java.util.stream.Collectors.joining(", "))
                                        + "]")
                                .collect(java.util.stream.Collectors.joining("; "))
                                + ".\n");
                    }
                    if (!facts.retentionUsage().isEmpty()) {
                        genLog.message("Fact retention by producer (PID: banked entities, "
                                + "retained / unused bytes):\n");
                        for (var retained : facts.retentionUsage()) {
                            genLog.message("  " + retained.source() + " / "
                                    + retained.propertyPid() + ": "
                                    + retained.bankedEntities() + ", ~"
                                    + retained.estimatedBytes() / 1024 + " / ~"
                                    + retained.unusedEstimatedBytes() / 1024 + " KB.\n");
                        }
                    }
                    completePhase(domain.build.GenerateDomainPipeline.FINALIZE,
                            pool.size() + " final object(s)"
                                    + finalization.ownedPartsSummary());

                    // Acquisition and semantic processing are complete. Persist this
                    // exact final graph before generated-code mapping, so a local
                    // materialization defect never forces the remote work to run again.
                    if (recovery != null) {
                        GenerationRun.Quality recoveryQuality = qualityTracker.quality();
                        domain.datasource.graph.GraphDiscoveryState recoveryDiscovery =
                                domain.build.WikidataGraphDiscoveryState.compute(
                                        project, pool);
                        domain.compile.transform.SelfReferenceLedger recoverySelfReferences =
                                domain.compile.transform.SelfReferenceLedger.ran(
                                        transformed.selfReferenceFindings(),
                                        finalization.suspectedSelfReferences());
                        domain.build.GraphCheckpoint recoveryCheckpoint =
                                domain.build.GraphCheckpoint.finalGraph(
                                        pool,
                                        java.util.List.copyOf(
                                                completedReferentLoads.values()),
                                        recoveryDiscovery, recoveryQuality,
                                        domain.build.DomainSave.signature(project));
                        genLog.message("Saving finalized recovery graph: "
                                + pool.size() + " objects -> "
                                + recovery.snapshotFile().getPath() + "\n");
                        recovery.save(project, recoveryCheckpoint, recoverySelfReferences);
                    }
                    phase(domain.build.GenerateDomainPipeline.MATERIALIZE,
                            pool.size() + " object(s)");

                    // ONE shared mapper over the SERVED POOL: each QID -> one typed
                    // instance, cross-refs resolve to those same instances. Mapping
                    // the pool (rather than the extractor's roots) makes the
                    // generation preview identical to load/serve — otherwise a qid
                    // stamped as a class root but stored untyped in the pool would
                    // show at generation yet vanish on reload.
                    new domain.build.PipelineExecutor()
                            .with(new domain.build.MaterializeStep())
                            .run(runContext, runState);
                    GeneratedViewableRuntime runtime = runState.runtime();
                    List<Viewable> allInstances = runState.instances();
                    completePhase(domain.build.GenerateDomainPipeline.MATERIALIZE,
                            allInstances.size() + " instances materialized");
                    if (recovery != null) {
                        // Kept until the project save that persists this run succeeds:
                        // a refused or abandoned save must still be resumable.
                        genLog.message("Keeping finalized recovery graph until the project "
                                + "is saved: " + recovery.snapshotFile().getPath() + "\n");
                    }

                    StringBuilder summary = new StringBuilder(
                            generatedQidSummary(pool, classesRun));
                    java.util.List<String> qualityWarnings = new java.util.ArrayList<>();
                    java.util.Set<String> unavailableQids = new java.util.LinkedHashSet<>();
                    GenerationRun.Quality trackedQuality = qualityTracker.quality();
                    if (!trackedQuality.complete()) {
                        qualityWarnings.addAll(trackedQuality.warnings());
                        unavailableQids.addAll(trackedQuality.unavailableQids());
                    }
                    if (childQueryFailures > 0) {
                        qualityWarnings.add(childQueryFailures
                                + " child extraction query(ies) did not complete");
                        partialPhase(
                                domain.build.GenerateDomainPipeline.DISCOVER,
                                childQueryFailures + " child query failure(s)");
                    }
                    java.util.List<domain.runtime.LoadedDeclaration> failedLoads =
                            new java.util.ArrayList<>(convergence.unresolvedLoads());
                    if (!failedLoads.isEmpty()) {
                        String failedNames = failedLoads.stream().limit(3)
                                .map(d -> d.className() + "." + d.fieldName()
                                        + " (" + d.propertyPid() + ", "
                                        + d.coveredQids().size() + " QIDs)")
                                .collect(java.util.stream.Collectors.joining(", "));
                        if (failedLoads.size() > 3) {
                            failedNames += " … and " + (failedLoads.size() - 3) + " more";
                        }
                        boolean roleFailure = failedLoads.stream().anyMatch(d -> {
                            GeneratedClassModel c = project.findClass(d.className());
                            return c != null && domain.config.MembershipPattern.of(
                                    c, project) == domain.config.MembershipPattern.REFERENCED;
                        });
                        if (roleFailure) partialPhase(
                                domain.build.GenerateDomainPipeline.SEMANTIC,
                                failedNames);
                        boolean laterFailure = failedLoads.stream().anyMatch(d -> {
                            GeneratedClassModel c = project.findClass(d.className());
                            if (c == null) return false;
                            var pattern = domain.config.MembershipPattern.of(c, project);
                            return pattern == domain.config.MembershipPattern.EVIDENCE_KIND
                                    || pattern == domain.config.MembershipPattern.OWNED_COMPONENT;
                        });
                        if (laterFailure || !roleFailure) partialPhase(
                                domain.build.GenerateDomainPipeline.SEMANTIC,
                                failedNames);
                    }
                    java.util.Set<String> unresolvedKindQids =
                            convergence.unresolvedKindQids();
                    int unresolvedKindEvidence = unresolvedKindQids.size();
                    if (unresolvedKindEvidence > 0) {
                        partialPhase(domain.build.GenerateDomainPipeline.SEMANTIC,
                                unresolvedKindEvidence
                                        + " entity-kind referent(s) unavailable");
                    }
                    if (!qualityWarnings.isEmpty()) {
                        summary.append("\nPARTIAL\t")
                                .append(String.join("; ", qualityWarnings));
                    }
                    if (qualityWarnings.isEmpty()) {
                        step.summary(summary.toString());
                    } else {
                        step.partial(summary.toString());
                    }
                    return new GenerationRun(
                            project, 0, rootPlan, pool, runtime, allInstances,
                            new GenerationRun.RemapState(enrichedSnapshot, companionSets),
                            java.util.List.copyOf(completedReferentLoads.values()),
                            qualityWarnings.isEmpty()
                                    ? GenerationRun.Quality.completeQuality()
                                    : GenerationRun.Quality.partial(
                                            qualityWarnings,
                                            java.util.List.copyOf(unavailableQids)),
                            finalization.coverage(),
                            GenerationRun.SelfReferenceAudit.ran(
                                    transformed.selfReferenceFindings(),
                                    finalization.suspectedSelfReferences()),
                            GenerationRun.OwnedCompositionAudit.ran(
                                    convergence.ownedComponentsCreated()),
                            GenerationRun.KindClassificationAudit.ran(
                                    convergence.newlyClassifiedKinds()),
                            GenerationRun.ProjectionAudit.ran(
                                    transformed.projectionChangedInstances()));
                    }
                });
    }

    /**
     * Describes exactly the narrow census shown at the end of generation. It is not the
     * saved-snapshot census: statements have non-QID identities, and one referenced entity
     * may claim classes in addition to its primary runtime class. Save domain traverses that
     * complete field graph and writes its class-membership census to {@code *.counts.tsv}.
     */
    static String generatedQidSummary(
            java.util.Collection<WikidataDynamicObject> pool, int rootClassQueries) {
        java.util.Map<String, java.util.Set<String>> byPrimaryClass =
                new java.util.LinkedHashMap<>();
        if (pool != null) {
            for (WikidataDynamicObject o : pool) {
                if (o == null || o.typeName() == null
                        || o.typeName().isBlank()
                        || "WikidataDynamicObject".equals(o.typeName())) {
                    continue;
                }
                String qid = o.qid();
                if (qid == null || !WikidataIds.isQid(qid)) continue;
                byPrimaryClass.computeIfAbsent(o.typeName(), ignored ->
                        new java.util.HashSet<>()).add(qid);
            }
        }

        int total = byPrimaryClass.values().stream()
                .mapToInt(java.util.Set::size).sum();
        StringBuilder summary = new StringBuilder(
                "Generated QID entities by primary class "
                        + "(statement records and additional class memberships are not "
                        + "included):\n");
        byPrimaryClass.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .forEach(entry -> summary.append(entry.getKey()).append('\t')
                        .append(entry.getValue().size()).append('\n'));
        summary.append("TOTAL\t").append(total)
                .append(" QID entities across ").append(byPrimaryClass.size())
                .append(" primary class(es); ").append(rootClassQueries)
                .append(" root class query(ies)\n")
                .append("This is not the saved snapshot census. Save domain writes the "
                        + "complete referenced object graph and reports its class-membership "
                        + "counts in the domain's .counts.tsv file.");
        return summary.toString();
    }

    /** The configured population and graph-discovered additions are a union, not one
     * population rule rewritten to mean the other. Both plans enter the same extraction
     * and downstream pipeline; the shared registry performs QID deduplication. */
    static List<RuleNode> populationPlans(
            RuleNode configuredPlan,
            RuleNode supplementalPlan,
            domain.build.PopulationSourceExecution.Resolution configured,
            List<String> supplementalQids) {
        List<RuleNode> plans = new ArrayList<>();
        if (configured != null && configured.available() && configuredPlan != null) {
            configured.apply(configuredPlan);
            plans.add(configuredPlan);
        }
        if (supplementalQids != null && !supplementalQids.isEmpty()
                && supplementalPlan != null) {
            domain.build.PopulationSourceExecution.applyExact(
                    supplementalPlan, supplementalQids);
            plans.add(supplementalPlan);
        }
        return List.copyOf(plans);
    }

    private GeneratedProjectModel rootedAt(String className) {
        GeneratedProjectModel sub = project.copy();
        for (GeneratedClassModel c : sub.classes()) {
            if (c.className().equals(className)) {
                if (c != sub.rootClass()) {
                    sub.rootClass(c);
                }
                return sub;
            }
        }
        return sub;
    }

    private void phase(String id, String summary) {
        if (pipelineProgress != null) pipelineProgress.start(id, summary);
    }

    private void progress(String id, String summary) {
        if (pipelineProgress != null) pipelineProgress.progress(id, summary);
    }

    private void completePhase(String id, String summary) {
        if (pipelineProgress != null) pipelineProgress.complete(id, summary);
    }

    private void partialPhase(String id, String summary) {
        if (pipelineProgress != null) pipelineProgress.partial(id, summary);
    }

    private static String statementAcquisitionPlan(
            domain.compile.CompiledProjectModel model) {
        java.util.List<String> descriptions = new java.util.ArrayList<>();
        for (var recipe :
                domain.compile.transform.ModelStatementReifications.derive(model)) {
            var load = recipe.load();
            String source = load.discoverSubjects() ? "discover subjects" : "reuse "
                    + load.entityType() + " members";
            String qualifiers = load.qualifiers() == null ? ""
                    : load.qualifiers().stream().map(q -> q.fieldName() + "=" + q.pid())
                    .collect(java.util.stream.Collectors.joining(", "));
            descriptions.add(load.statementType() + ": " + source + ", load "
                    + load.propertyPid() + (qualifiers.isBlank() ? ""
                    : " with " + qualifiers));
        }
        return descriptions.isEmpty() ? "No statement classes configured"
                : String.join("; ", descriptions);
    }

    /** Remote failures remain unresolved only when those exact identities still lack
     * stored evidence after the retry/convergence pass. */
    static java.util.Set<String> unresolvedKindEvidenceQids(
            java.util.Collection<String> remotelyUnavailable,
            java.util.Collection<String> stillWithoutStoredEvidence) {
        java.util.Set<String> unresolved = new java.util.LinkedHashSet<>();
        if (remotelyUnavailable != null) unresolved.addAll(remotelyUnavailable);
        if (stillWithoutStoredEvidence != null) {
            unresolved.retainAll(new java.util.HashSet<>(stillWithoutStoredEvidence));
        }
        return java.util.Set.copyOf(unresolved);
    }

    @Override public int rowCount(GenerationRun r) { return r == null ? 0 : r.size(); }
    @Override public String summary(GenerationRun r) { return rowCount(r) + " objects"; }
}
