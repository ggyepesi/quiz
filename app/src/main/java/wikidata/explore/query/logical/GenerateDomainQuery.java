package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataObjectRegistry;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;
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
    private final process.ProcessWorkflowPipeline pipelineProgress;
    private final wikidata.explore.generation.GenerationExecutionSettings executionSettings;

    public GenerateDomainQuery(GeneratedProjectModel project) {
        this(project, null, new wikidata.explore.generation.GenerationExecutionSettings());
    }

    public GenerateDomainQuery(
            GeneratedProjectModel project,
            process.ProcessWorkflowPipeline pipelineProgress) {
        this(project, pipelineProgress,
                new wikidata.explore.generation.GenerationExecutionSettings());
    }

    public GenerateDomainQuery(
            GeneratedProjectModel project,
            process.ProcessWorkflowPipeline pipelineProgress,
            wikidata.explore.generation.GenerationExecutionSettings executionSettings) {
        this.project = project;
        this.pipelineProgress = pipelineProgress;
        this.executionSettings = executionSettings;
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
        // The plan is the single resolved inventory. Consumers still migrate one
        // operation family at a time at their existing batching/cache boundary.
        datasource.api.SourceExecutionPlan sourcePlan =
                wikidata.explore.model.ModelSourceExecutionPlan.compile(
                        project, datasource.Datasources.standard());
        context.message(wikidata.explore.model.ModelSourceExecutionPlan.generationMessage(
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
                            WikidataAccess.logRequests(context, genLog::message)) {
                    genLog.message(executionSettings.resolvedDescription());
                    phase(wikidata.explore.generation.GenerateDomainPipeline.PLAN,
                            project.classes().size() + " configured classes");

                    GenerationPipeline pipeline = new GenerationPipeline();
                    WikidataObjectRegistry shared = new WikidataObjectRegistry();
                    wikidata.api.WikidataApiClient entityApi =
                            new wikidata.api.WikidataApiClient(
                                    wikidata.api.WikidataApiClient.DEFAULT_USER_AGENT)
                                    .facts(executionSettings.newFactStore())
                                    .entityConcurrency(executionSettings.concurrency())
                                    .cancellation(context.cancellation());

                    // ONE runtime for the whole domain — every class compiled
                    // together in one package/loader, so typed cross-references
                    // (Character <-> Episode) resolve and map to shared typed
                    // instances rather than raw objects.
                    // Resolve each quantity field's unit once (the truthy value
                    // drops it), so the mapper can render "1538 K". Before building
                    // the runtime, so it holds fully-resolved field models.
                    pipeline.resolveUnits(project, WikidataAccess.sparql(context, Datasource.WIKIDATA), genLog);

                    wikidata.explore.compiled.CompiledProjectModel compiledProject =
                            wikidata.explore.compiled.ProjectModelCompiler.compile(project);
                    wikidata.explore.generation.GenerationState generationState =
                            new wikidata.explore.generation.GenerationState(
                                    project, compiledProject, entityApi.facts());
                    completePhase(wikidata.explore.generation.GenerateDomainPipeline.PLAN,
                            "Model compiled");
                    phase(wikidata.explore.generation.GenerateDomainPipeline.DISCOVER,
                            project.classes().size() + " configured classes");

                    RuleNode rootPlan = null;
                    int classesRun = 0;
                    int childQueryFailures = 0;
                    FactDemandPlan factDemandPlan =
                            wikidata.explore.generation.GenerationFactDemandPlan.compile(project);

                    for (GeneratedClassModel cls : project.classes()) {
                        datasource.api.SourceExecutionPlan.Step population = sourcePlan.step(
                                datasource.api.SourceBindingTarget.classPopulation(
                                        cls.className()));
                        if (!generatable(cls, population)) {
                            genLog.message("Skip class \"" + cls.className()
                                    + "\" — no membership type or seed QIDs.\n");
                            continue;
                        }
                        GeneratedProjectModel rooted = rootedAt(cls.className());
                        RuleNode plan = pipeline.plan(rooted);
                        wikidata.explore.generation.PopulationSourceExecution.apply(
                                plan, population);
                        genLog.message("=== Class \"" + cls.className()
                                + "\" (depth " + cls.generationDepth() + ") ===\n");

                        GenerationPipeline.ExtractionResult extraction =
                                pipeline.extractResult(
                                WikidataAccess.sparql(context, Datasource.WIKIDATA), plan, cls.generationDepth(),
                                genLog, shared, context.cancellation(), entityApi,
                                factDemandPlan.forClass(cls.className()));
                        List<WikidataDynamicObject> roots = extraction.objects();
                        childQueryFailures += extraction.childQueryFailures();
                        genLog.message("  -> " + roots.size() + " "
                                + cls.className() + "\n");

                        if (rootPlan == null) {
                            rootPlan = plan;
                        }
                        classesRun++;
                        progress(wikidata.explore.generation.GenerateDomainPipeline.DISCOVER,
                                classesRun + " root class query(ies) completed");
                    }

                    completePhase(wikidata.explore.generation.GenerateDomainPipeline.DISCOVER,
                            classesRun + " root class query(ies) completed");
                    phase(wikidata.explore.generation.GenerateDomainPipeline.ACQUIRE_STATEMENTS,
                            "Loading statements and qualifiers");
                    String statementPlan = statementAcquisitionPlan(compiledProject);
                    progress(
                            wikidata.explore.generation.GenerateDomainPipeline.ACQUIRE_STATEMENTS,
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
                    wikidata.explore.transform.ModelStatementReifications.AcquisitionReport
                            statementAcquisition =
                            wikidata.explore.transform.ModelStatementReifications
                                    .enrichWithReport(
                                            compiledProject, reifyPool,
                                            WikidataAccess.sparql(context, Datasource.WIKIDATA), genLog,
                                            entityApi, true, factDemandPlan);
                    progress(
                            wikidata.explore.generation.GenerateDomainPipeline.ACQUIRE_STATEMENTS,
                            statementAcquisition.summary());
                    completePhase(
                            wikidata.explore.generation.GenerateDomainPipeline.ACQUIRE_STATEMENTS,
                            statementAcquisition.summary());
                    phase(wikidata.explore.generation.GenerateDomainPipeline.CONSTRUCT,
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
                            wikidata.explore.transform.PoolCopy.deepCopy(shared.values());

                    // The ONE transform sequence (#97). Generate differs from Remap only
                    // in that it FETCHES the companion sets instead of replaying cached
                    // ones — and it caches them here for the next Remap.
                    wikidata.explore.transform.StatementTransforms.Result transformed =
                            wikidata.explore.transform.StatementTransforms.apply(
                                    project, compiledProject, reifyPool,
                                    records -> {
                                        try {
                                            return wikidata.explore.transform.CompanionMatch
                                                    .loadSets(compiledProject, records,
                                                            WikidataAccess.sparql(
                                                                    context, Datasource.WIKIDATA),
                                                            genLog);
                                        } catch (RuntimeException failed) {
                                            throw failed;
                                        } catch (Exception failed) {
                                            throw new RuntimeException(failed);
                                        }
                                    },
                                    genLog);
                    List<WikidataDynamicObject> reified = transformed.reified();
                    java.util.Map<String, java.util.Set<java.util.List<String>>>
                            companionSets = transformed.companionSets();

                    // reifyPool is now the served set: the reified records added, the
                    // demoted duplicates removed, no internal plumbing left. The registry
                    // keeps every object it already knew, so the snapshot still carries
                    // them; what is SERVED is this pool.
                    List<WikidataDynamicObject> pool = reifyPool;

                    completePhase(wikidata.explore.generation.GenerateDomainPipeline.CONSTRUCT,
                            reified.size() + " statement record(s)");
                    phase(wikidata.explore.generation.GenerateDomainPipeline.SEMANTIC,
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
                    wikidata.explore.generation.GenerationQualityTracker qualityTracker =
                            generationState.quality();
                    wikidata.explore.generation.SemanticConvergence.Result convergence =
                            wikidata.explore.generation.SemanticConvergence.apply(
                                    project, referentLoadRoots, entityApi, genLog,
                                    java.util.List.of(), qualityTracker);
                    java.util.Map<String, wikidata.explore.extract.LoadedDeclaration>
                            completedReferentLoads = new java.util.LinkedHashMap<>(
                                    convergence.completedDeclarations());
                    int loadedReferentFields = convergence.loadedFields();
                    if (loadedReferentFields > 0) {
                        genLog.message("Loaded " + loadedReferentFields
                                + " referent/owned field value(s) from declared PIDs.\n");
                    }
                    completePhase(wikidata.explore.generation.GenerateDomainPipeline.SEMANTIC,
                            convergence.iterations() + " semantic iteration(s)");
                    phase(wikidata.explore.generation.GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                            "DBpedia fields, Wikipedia categories and native infobox values");
                    wikidata.explore.generation.DBpediaFieldAcquisition.Result dbpedia =
                            wikidata.explore.generation.DBpediaFieldAcquisition.apply(
                                    project, pool, sourcePlan,
                                    WikidataAccess.sparql(context, Datasource.DBPEDIA), genLog);
                    wikidata.explore.generation.WikipediaCategoryAcquisition.Result categories =
                            wikidata.explore.generation.WikipediaCategoryAcquisition.apply(
                                    pool, genLog, context.cancellation(), entityApi, sourcePlan);
                    wikidata.explore.generation.WikipediaInfoboxAcquisition.Result infoboxes =
                            wikidata.explore.generation.WikipediaInfoboxAcquisition.apply(
                                    project, pool, genLog, context.cancellation(), entityApi,
                                    sourcePlan);
                    if (categories.memberships() > 0) {
                        progress(wikidata.explore.generation.GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                                categories.memberships()
                                        + " Wikipedia category membership(s) acquired");
                    }
                    completePhase(
                            wikidata.explore.generation.GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                            categories.memberships() + " category membership(s), "
                                    + infoboxes.values() + " infobox value(s), "
                                    + dbpedia.values() + " DBpedia value(s)");
                    phase(wikidata.explore.generation.GenerateDomainPipeline.LABELS,
                            "Hydrating final reachable QIDs");
                    wikidata.explore.generation.FinalLabelHydration.Result labels =
                            wikidata.explore.generation.FinalLabelHydration.apply(
                                    pool, entityApi, genLog, qualityTracker);
                    completePhase(wikidata.explore.generation.GenerateDomainPipeline.LABELS,
                            labels.resolved() + " label(s) resolved");
                    phase(wikidata.explore.generation.GenerateDomainPipeline.FINALIZE,
                            "Canonicalize, prune, validate and build vocabularies");
                    wikidata.explore.generation.DomainFinalization.Result finalization =
                            wikidata.explore.generation.DomainFinalization.apply(
                            project, compiledProject, pool, reified, entityApi, genLog);
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
                    completePhase(wikidata.explore.generation.GenerateDomainPipeline.FINALIZE,
                            pool.size() + " final object(s)"
                                    + (finalization.ownedRenamed() == 0 ? ""
                                            : ", " + finalization.ownedRenamed()
                                                    + " owned part(s) renamed")
                                    + (finalization.ownerlessParts() == 0 ? ""
                                            : ", " + finalization.ownerlessParts()
                                                    + " owned part(s) dropped as ownerless"));
                    phase(wikidata.explore.generation.GenerateDomainPipeline.MATERIALIZE,
                            pool.size() + " object(s)");

                    // ONE shared mapper over the SERVED POOL: each QID -> one typed
                    // instance, cross-refs resolve to those same instances. Mapping
                    // the pool (rather than the extractor's roots) makes the
                    // generation preview identical to load/serve — otherwise a qid
                    // stamped as a class root but stored untyped in the pool would
                    // show at generation yet vanish on reload.
                    GeneratedViewableRuntime runtime = pipeline.buildRuntime(project);
                    List<Viewable> allInstances =
                            pipeline.materialize(runtime, pool);
                    completePhase(wikidata.explore.generation.GenerateDomainPipeline.MATERIALIZE,
                            allInstances.size() + " instances materialized");

                    // Parent summary = a copy-pasteable per-class breakdown of the
                    // SERVED pool, counted by DISTINCT qid (what the save keeps), so
                    // two runs diff cleanly and the number matches the reload.
                    java.util.Map<String, java.util.Set<String>> servedByType =
                            new java.util.LinkedHashMap<>();
                    for (WikidataDynamicObject o : pool) {
                        if (o == null || o.typeName() == null || o.typeName().isBlank()
                                || "WikidataDynamicObject".equals(o.typeName())) {
                            continue;
                        }
                        String qid = o.qid();
                        if (qid == null || !WikidataIds.isQid(qid)) {
                            continue;
                        }
                        servedByType.computeIfAbsent(
                                o.typeName(), k -> new java.util.HashSet<>()).add(qid);
                    }
                    int servedTotal = servedByType.values().stream()
                            .mapToInt(java.util.Set::size).sum();
                    StringBuilder summary = new StringBuilder();
                    servedByType.entrySet().stream()
                            .sorted((a, b) -> Integer.compare(
                                    b.getValue().size(), a.getValue().size()))
                            .forEach(e -> summary.append(e.getKey()).append('\t')
                                    .append(e.getValue().size()).append('\n'));
                    summary.append("TOTAL\t").append(servedTotal)
                            .append(" distinct across ").append(servedByType.size())
                            .append(" served type(s); ").append(classesRun)
                            .append(" root class query(ies)");
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
                                wikidata.explore.generation.GenerateDomainPipeline.DISCOVER,
                                childQueryFailures + " child query failure(s)");
                    }
                    java.util.List<wikidata.explore.extract.LoadedDeclaration> failedLoads =
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
                            return c != null && wikidata.explore.model.MembershipPattern.of(
                                    c, project) == wikidata.explore.model.MembershipPattern.REFERENCED;
                        });
                        if (roleFailure) partialPhase(
                                wikidata.explore.generation.GenerateDomainPipeline.SEMANTIC,
                                failedNames);
                        boolean laterFailure = failedLoads.stream().anyMatch(d -> {
                            GeneratedClassModel c = project.findClass(d.className());
                            if (c == null) return false;
                            var pattern = wikidata.explore.model.MembershipPattern.of(c, project);
                            return pattern == wikidata.explore.model.MembershipPattern.EVIDENCE_KIND
                                    || pattern == wikidata.explore.model.MembershipPattern.OWNED_COMPONENT;
                        });
                        if (laterFailure || !roleFailure) partialPhase(
                                wikidata.explore.generation.GenerateDomainPipeline.SEMANTIC,
                                failedNames);
                    }
                    java.util.Set<String> unresolvedKindQids =
                            convergence.unresolvedKindQids();
                    int unresolvedKindEvidence = unresolvedKindQids.size();
                    if (unresolvedKindEvidence > 0) {
                        partialPhase(wikidata.explore.generation.GenerateDomainPipeline.SEMANTIC,
                                unresolvedKindEvidence + " role member(s) unavailable");
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
                                    transformed.selfReferenceFindings()),
                            GenerationRun.OwnedCompositionAudit.ran(
                                    convergence.ownedComponentsCreated()),
                            GenerationRun.KindClassificationAudit.ran(
                                    convergence.newlyClassifiedKinds()),
                            GenerationRun.ProjectionAudit.ran(
                                    transformed.projectionChangedInstances()));
                    }
                });
    }

    // A running sub-query node under {@code step}, finished via the handle.
    // Generatable = has something to query: a membership type, extra types, or
    // an explicit seed-QID set. (A bare reference-only class is skipped.)
    private boolean generatable(
            GeneratedClassModel cls,
            datasource.api.SourceExecutionPlan.Step population) {
        // STATEMENT-reification classes aren't fetched by a normal root query —
        // they're produced by ModelStatementReifications (qualifier-load + reify).
        if (cls.reifiesStatements()) {
            return false;
        }
        return population != null;
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
            wikidata.explore.compiled.CompiledProjectModel model) {
        java.util.List<String> descriptions = new java.util.ArrayList<>();
        for (var recipe :
                wikidata.explore.transform.ModelStatementReifications.derive(model)) {
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
