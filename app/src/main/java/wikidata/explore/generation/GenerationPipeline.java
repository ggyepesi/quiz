package wikidata.explore.generation;

import datasource.api.acquisition.PopulationRequest;
import wikidata.WikidataIds;

import wikidata.explore.codegen.GeneratedViewableRuntimeBuilder;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.codegen.GeneratedViewableMapper;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.FieldSourceMapping;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.WikidataBinding;
import wikidata.explore.rule.RuleTreeSerializer;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleNode;
import objectview.Viewable;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import wikidata.api.FactDemand;

/**
 * The generation pipeline as separately callable stages:
 *
 *   plan          model snapshot  -> RuleNode query plan
 *   extract       plan + depth    -> downloaded dynamic objects  (slow, network)
 *   buildRuntime  model snapshot  -> compiled generated class    (fast, local)
 *   materialize   runtime + data  -> mapped Viewable instances   (fast, local)
 *
 * fullRun() chains all four. remap() reuses a previous run's download
 * and re-runs only the local stages; callers must check
 * sameExtraction() first.
 */
public class GenerationPipeline {

    public record ExtractionResult(
            List<WikidataDynamicObject> objects, int childQueryFailures) { }

    public RuleNode plan(GeneratedProjectModel snapshot) {
        return RuleTreeCompiler.compileProject(snapshot);
    }

    public List<WikidataDynamicObject> extract(
            WikidataSparqlClient client,
            RuleNode plan,
            int depth,
            GenerationLog log) throws Exception {

        return new RuleTreeExtractor(client).load(plan, depth, log);
    }

    /** Extracts into a SHARED registry so several class runs (a whole-domain
     *  generation) pool into one graph keyed by QID — an entity referenced by
     *  one class and generated as another's root becomes a single, type-stamped
     *  object with references already linked. */
    public List<WikidataDynamicObject> extract(
            WikidataSparqlClient client,
            RuleNode plan,
            int depth,
            GenerationLog log,
            wikidata.explore.extract.WikidataObjectRegistry registry) throws Exception {

        RuleTreeExtractor extractor = new RuleTreeExtractor(client, registry);
        extractor.log(log);
        return extractor.load(plan, depth, log);
    }

    public ExtractionResult extractResult(
            WikidataSparqlClient client,
            RuleNode plan,
            int depth,
            GenerationLog log,
            wikidata.explore.extract.WikidataObjectRegistry registry,
            work.CancellationToken cancellation,
            wikidata.api.WikidataApiClient entityApi,
            List<FactDemand> factDemands) throws Exception {
        RuleTreeExtractor extractor = new RuleTreeExtractor(client, registry)
                .cancellation(cancellation)
                .api(entityApi)
                .factDemands(factDemands)
                .deferLabels(true);
        extractor.log(log);
        List<WikidataDynamicObject> objects = extractor.load(plan, depth, log);
        return new ExtractionResult(objects, extractor.childQueryFailures());
    }

    /** Shared-registry extraction using the generation run's action-API/fact store. */
    public List<WikidataDynamicObject> extract(
            WikidataSparqlClient client,
            RuleNode plan,
            int depth,
            GenerationLog log,
            wikidata.explore.extract.WikidataObjectRegistry registry,
            work.CancellationToken cancellation,
            wikidata.api.WikidataApiClient entityApi) throws Exception {
        RuleTreeExtractor extractor = new RuleTreeExtractor(client, registry)
                .cancellation(cancellation)
                .api(entityApi);
        extractor.log(log);
        return extractor.load(plan, depth, log);
    }

    /** Shared-registry extraction bound to the process's real cancellation token. */
    public List<WikidataDynamicObject> extract(
            WikidataSparqlClient client,
            RuleNode plan,
            int depth,
            GenerationLog log,
            wikidata.explore.extract.WikidataObjectRegistry registry,
            work.CancellationToken cancellation) throws Exception {

        RuleTreeExtractor extractor = new RuleTreeExtractor(client, registry)
                .cancellation(cancellation);
        extractor.log(log);
        return extractor.load(plan, depth, log);
    }

    public GeneratedViewableRuntime buildRuntime(
            GeneratedProjectModel snapshot) throws Exception {

        // Compile every class in the project (root + e.g. Star), so a
        // multi-class run maps each object to its own type.
        return new GeneratedViewableRuntimeBuilder()
                .build(snapshot);
    }

    public List<Viewable> materialize(
            GeneratedViewableRuntime runtime,
            List<WikidataDynamicObject> dynamicObjects) throws Exception {

        return new GeneratedViewableMapper(runtime)
                .mapRoots(dynamicObjects);
    }

    /**
     * Resolves each quantity (NUMBER) field's unit ONCE for the whole field —
     * the truthy value drops the unit, so one small aggregate query per field
     * fetches the dominant unit symbol (P5061) over the class's members. Sets it
     * on the field model so the mapper can render "1538 K". Best-effort: a
     * failed/empty lookup just leaves the field dimensionless.
     */
    public void resolveUnits(
            GeneratedProjectModel project,
            WikidataSparqlClient client,
            GenerationLog log) {

        if (project == null || client == null) {
            return;
        }
        for (GeneratedClassModel cls : project.classes()) {
            String members = membershipPattern(cls, project);
            if (members == null) {
                continue;
            }
            for (GeneratedFieldModel f : cls.fields()) {
                if (f == null || f.type() != FieldType.NUMBER || !f.unit().isBlank()) {
                    continue;
                }
                String pid = f.mapping() == null
                        ? "" : RuleNode.cleanPid(f.mapping().propertyPid());
                if (pid == null || !WikidataIds.isPid(pid)) {
                    continue;
                }
                // P5061 unit symbols are monolingual; prefer the English one
                // (e.g. "g/cm³", not a localized "g/sm³").
                String q = "SELECT ?sym (COUNT(*) AS ?n) WHERE { "
                        + members
                        + " ?value p:" + pid + "/psv:" + pid + "/wikibase:quantityUnit ?u ."
                        + " ?u wdt:P5061 ?sym . FILTER(LANG(?sym) = \"en\") }"
                        + " GROUP BY ?sym ORDER BY DESC(?n) LIMIT 1";
                try {
                    List<WikidataBinding> rows = client.query(q);
                    if (!rows.isEmpty()) {
                        String sym = rows.get(0).value("sym");
                        if (sym != null && !sym.isBlank()) {
                            f.unit(sym);
                            if (log != null) {
                                log.message("Unit " + f.name() + " = " + sym + "\n");
                            }
                        }
                    }
                } catch (Exception e) {
                    if (log != null) {
                        log.message("Unit lookup failed for " + f.name()
                                + ": " + e.getMessage() + "\n");
                    }
                }
            }
        }
    }

    private String membershipPattern(
            GeneratedClassModel cls, GeneratedProjectModel project) {
        wikidata.explore.model.EntityBound membership = cls.effectiveMembership(project);
        if (membership.qids().isEmpty() || membership.relationPid().isBlank()) {
            return null;
        }
        String pid = RuleNode.cleanPid(membership.relationPid());
        if (membership.qids().size() == 1) {
            return "?value wdt:" + pid + " wd:" + membership.qids().get(0) + " .";
        }
        // Every target, joined the way membership is: a bound naming four types means
        // members of any of them. Taking the first sampled one type's units and called
        // them the class's — which was invisible while the leading QID looked primary.
        StringBuilder values = new StringBuilder("VALUES ?membershipRoot {");
        for (String qid : membership.qids()) {
            values.append(" wd:").append(qid);
        }
        return values.append(" } ?value wdt:").append(pid)
                .append(" ?membershipRoot .").toString();
    }

    public GenerationRun fullRun(
            GeneratedProjectModel snapshot,
            int depth,
            WikidataSparqlClient client,
            GenerationLog log) throws Exception {
        return fullRun(snapshot, depth, client, log, null, null);
    }

    /** Single-class preview with the same post-extraction source orbit as domain
     * generation. The compatibility overload remains for offline/tests without an entity
     * client; interactive Generate class instances supplies both dependencies. */
    public GenerationRun fullRun(
            GeneratedProjectModel snapshot,
            int depth,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi,
            work.CancellationToken cancellation) throws Exception {

        datasource.api.SourceExecutionPlan sourcePlan =
                wikidata.explore.model.ModelSourceExecutionPlan.synchronizeAndCompile(
                        snapshot, datasource.Datasources.standard());
        return fullRun(snapshot, depth, client, log, entityApi, cancellation, sourcePlan);
    }

    /** Single-class generation consuming the same resolved datasource plan as the
     * whole-domain operation. */
    public GenerationRun fullRun(
            GeneratedProjectModel snapshot,
            int depth,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi,
            work.CancellationToken cancellation,
            datasource.api.SourceExecutionPlan sourcePlan) throws Exception {
        return fullRun(snapshot, depth, client, log, entityApi, cancellation,
                sourcePlan, null);
    }

    /** Interactive entry point with both endpoint clients supplied by QueryContext. */
    public GenerationRun fullRun(
            GeneratedProjectModel snapshot,
            int depth,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi,
            work.CancellationToken cancellation,
            datasource.api.SourceExecutionPlan sourcePlan,
            WikidataSparqlClient dbpedia) throws Exception {

        CompiledPipelineRun run = CompiledPipelineRun.compile(
                PipelineRequest.generateClassPreview(
                        snapshot, snapshot.rootClass().className(), depth));
        return fullRun(run, depth, client, log, entityApi, cancellation, sourcePlan, dbpedia);
    }

    /** Single-class production using the compiled run its caller displayed. */
    public GenerationRun fullRun(
            CompiledPipelineRun run,
            int depth,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi,
            work.CancellationToken cancellation,
            datasource.api.SourceExecutionPlan sourcePlan,
            WikidataSparqlClient dbpedia) throws Exception {

        if (run == null) throw new IllegalArgumentException("No compiled pipeline run");
        if (run.blocked()) throw new IllegalStateException(run.explain());
        GeneratedProjectModel snapshot = run.request().model();

        // Before ANY acquisition, for the reason Enrich compiles first — except that
        // here the model was never checked at all, so an invalid one did not merely
        // waste the fetching: it went on to build a runtime and materialize instances
        // from a model nothing had refused, and the run looked like it worked.
        wikidata.explore.compiled.CompiledProjectModel compiled = run.model();
        RuleNode plan = plan(snapshot);
        datasource.api.SourceExecutionPlan.Step population = sourcePlan == null ? null
                : sourcePlan.step(datasource.api.SourceBindingTarget.classPopulation(
                        snapshot.rootClass().className()));
        if (population != null && population.prepared().configuration(
                PopulationRequest.class) == null) {
            if (log != null) log.message("Preview skipped: "
                    + population.prepared().description() + ".\n");
            GeneratedViewableRuntime runtime = buildRuntime(snapshot);
            return new GenerationRun(snapshot, depth, plan, List.of(), runtime, List.of());
        }
        if (population != null) PopulationSourceExecution.apply(plan, population);

        List<WikidataDynamicObject> dynamicObjects =
                extract(client, plan, depth, log);

        ExternalSourceAcquisition.apply(snapshot, dynamicObjects, sourcePlan,
                StandardExternalSourceFamilies.services(dbpedia, entityApi), log, cancellation,
                ExternalSourceAcquisition.FailurePolicy.CONTINUE_OPTIONAL,
                java.util.Set.of(
                        datasource.dbpedia.DbpediaDatasourceProvider.FAMILY_FIELD,
                        datasource.wikipedia.WikipediaDatasourceProvider
                                .FAMILY_INFOBOX_FIELD));

        // NO owned components here. This is the single-class PREVIEW: it answers "who is
        // in this class, carrying what", and materializing a component per owner answers
        // neither — one empty part per instance, since nothing on this path fetches their
        // declared properties. They are produced by Generate domain and by Enrich, which
        // do. Say so, or their absence reads as a fault in the model.
        int sites = ownedComponentSites(snapshot);
        if (sites > 0 && log != null) {
            log.message("Preview: " + sites + " owned-component field(s) NOT materialized "
                    + "— parts are produced with their values by Generate domain or "
                    + "Enrich, and would be empty here.\n");
        }

        // The idempotent transforms — inverts, value restrictions, projections. Preview
        // never reifies, so those stages are simply no-ops here; running the same
        // construct is what stops preview quietly showing values a declared restriction
        // would have pruned.
        wikidata.explore.transform.StatementTransforms.applyIdempotent(
                compiled, dynamicObjects, log);

        // displayName from each class's CanonicalSpec (see Canonicalization).
        wikidata.explore.transform.Canonicalization.apply(snapshot, dynamicObjects, log);

        // Resolve units BEFORE building the runtime. The mapper reads
        // field.unit() live at materialize, and buildRuntime happens to share the
        // field-model references — but resolving first keeps this correct even if
        // buildRuntime ever snapshots the model, expressing "resolve, then build".
        resolveUnits(snapshot, client, log);
        GeneratedViewableRuntime runtime = buildRuntime(snapshot);

        List<Viewable> instances =
                materialize(runtime, dynamicObjects);

        return new GenerationRun(
                snapshot, depth, plan, dynamicObjects, runtime, instances);
    }

    public GenerationRun remap(
            GenerationRun previous,
            GeneratedProjectModel snapshot) throws Exception {
        return remap(previous, snapshot, null);
    }

    public GenerationRun remap(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            GenerationLog log) throws Exception {
        return remap(previous, snapshot, log, RunSteps.SILENT);
    }

    /** As above, reporting each step it finishes. */
    public GenerationRun remap(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            GenerationLog log,
            RunSteps steps) throws Exception {
        datasource.graph.GraphDiscoveryState observed =
                WikidataGraphDiscoveryState.compute(snapshot, previous.dynamicObjects());
        CompiledPipelineRun run = CompiledPipelineRun.compile(PipelineRequest.remap(
                snapshot, previous.remapCheckpoint(observed)));
        return remap(previous, run, log, steps);
    }

    public GenerationRun remap(
            GenerationRun previous,
            CompiledPipelineRun run,
            GenerationLog log,
            RunSteps steps) throws Exception {

        steps = steps == null ? RunSteps.SILENT : steps;
        if (run == null) throw new IllegalArgumentException("No compiled pipeline run");
        if (run.blocked()) throw new IllegalStateException(run.explain());
        GeneratedProjectModel snapshot = run.request().model();
        steps.started(GenerateDomainPipeline.PLAN,
                "Compile the model and stage the saved graph");
        wikidata.explore.compiled.CompiledProjectModel compiled = run.model();
        RuleNode plan = plan(snapshot);
        GeneratedViewableRuntime runtime = buildRuntime(snapshot);

        GenerationRun.RemapState rs = previous.remapState();
        RemapScope scope = RemapScope.of(previous);
        if (scope.retransform()) {
            return retransform(previous, snapshot, compiled, plan, runtime, rs, log, steps);
        }

        // No cached enriched pool (e.g. a snapshot loaded after an app restart, or a
        // single-class run): the pool is already reified/inverted, so we CAN'T re-run
        // those (they'd double-apply) — but the idempotent, overwrite-only transforms
        // still take effect, so a projection added since this snapshot was saved
        // (e.g. Nomination.year <- edition.date.year) fills on Remap rather than
        // silently no-op'ing.
        // Remap stages a new result; kind assignment can change carrier/type keys, so
        // never mutate the previous run that remains visible until Apply succeeds.
        List<WikidataDynamicObject> pool =
                wikidata.explore.transform.PoolCopy.deepCopy(previous.dynamicObjects());
        steps.completed(GenerateDomainPipeline.PLAN,
                pool.size() + " object(s) staged");
        steps.started(GenerateDomainPipeline.CONSTRUCT,
                "Replay local transforms");
        List<WikidataDynamicObject> projectedRecords = new ArrayList<>();
        int filled = wikidata.explore.transform.StatementTransforms.applyIdempotent(
                compiled, pool, log, projectedRecords);
        steps.completed(GenerateDomainPipeline.CONSTRUCT,
                filled + " projected field value(s) changed");
        steps.started(GenerateDomainPipeline.SEMANTIC,
                "Settle entity kinds and compose owned parts");
        // Through the worklist, not past it. Remap ran three of its steps by hand —
        // classify from stored evidence, compose parts, stamp the new components — and
        // so did one pass where the worklist runs to a fixed point, and never stamped
        // roles on the pool before classifying. Composition can unlock composition, so
        // one pass is a different answer, not a cheaper one.
        //
        // Remap may not acquire, so the context carries no client and the worklist takes
        // its local path. The previous run's objects are the evidence that path reads
        // instead of asking: the pool here is a staged copy, because kind assignment can
        // change a carrier's type key and the visible run must not move before Apply.
        PipelineState tailState = PipelineState.over(
                GraphCheckpoint.Stage.CONSTRUCTED_GRAPH, pool, previous.dynamicObjects());
        PipelineContext tailContext = new PipelineContext(run, null, log, null);
        tailState.useRuntime(runtime);
        new PipelineExecutor()
                .with(new SemanticWorklistStep())
                .run(tailContext, tailState);
        SemanticConvergence.Result converged = tailState.convergence();
        steps.completed(GenerateDomainPipeline.SEMANTIC,
                converged.classifiedKinds() + " kind(s), "
                        + converged.ownedCreated() + " owned part(s)");
        steps.started(GenerateDomainPipeline.FINALIZE,
                "Finalize and validate the transformed graph");
        new PipelineExecutor().with(new FinalizeStep()).run(tailContext, tailState);
        DomainFinalization.Result finalization = tailState.finalization();
        int restricted = finalization.requiredDropped();
        steps.completed(GenerateDomainPipeline.FINALIZE,
                restricted + " dropped (required-field)"
                        + ownedRenamed(finalization));
        if (log != null) {
            log.message("Remap (idempotent transforms only): "
                    + pool.size() + " objects re-materialized, "
                    + filled + " projected field(s) filled, "
                    + converged.classifiedKinds() + " entity kind(s) assigned, "
                    + restricted + " dropped (required-field).\n"
                    + scope.limitation() + "\n");
        }

        steps.started(GenerateDomainPipeline.MATERIALIZE,
                "Map the final graph into instances");
        new PipelineExecutor().with(new MaterializeStep()).run(tailContext, tailState);
        List<Viewable> instances = tailState.instances();
        steps.completed(GenerateDomainPipeline.MATERIALIZE,
                instances.size() + " instance(s) materialized");

        return new GenerationRun(
                snapshot, previous.depth(), plan,
                pool, tailState.runtime(), instances, rs,
                previous.loadedDeclarations(), previous.quality(), finalization.coverage(),
                GenerationRun.SelfReferenceAudit.notRun(),
                GenerationRun.OwnedCompositionAudit.ran(converged.ownedComponentsCreated()),
                GenerationRun.KindClassificationAudit.ran(converged.newlyClassifiedKinds()),
                GenerationRun.ProjectionAudit.ran(projectedRecords));
    }

    /**
     * Fills DECLARED fields that were never fetched, over the pool already downloaded.
     *
     * <p>The third mode, between the other two. Generate re-extracts; Remap re-transforms
     * offline and so can never show a property nobody fetched. But declaring
     * {@code Nominee.type} or {@code Name.familyName} changes no membership and
     * invalidates nothing already in the pool — it is ADDITIVE, and its input is just
     * the QIDs the pool already holds. Paying for a full re-extraction to add two
     * per-entity properties is the wrong price.
     *
     * <p>So: materialize any newly declared components, fetch the declared PIDs of
     * referenced-only and owned classes for the entities already present, then give the
     * new values their classes and display names. A field that already has a value is
     * left alone ({@link wikidata.explore.transform.ReferentFieldLoad}), so re-running
     * only fills gaps.
     */
    public GenerationRun enrich(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            wikidata.api.WikidataApiClient entityApi,
            GenerationLog log) throws Exception {
        return enrich(previous, snapshot, entityApi, log, new work.CancellationToken());
    }

    public GenerationRun enrich(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            wikidata.api.WikidataApiClient entityApi,
            GenerationLog log,
            work.CancellationToken cancellation) throws Exception {
        return enrich(previous, snapshot, entityApi, log, cancellation, RunSteps.SILENT);
    }

    /** As above, reporting each step it finishes. */
    public GenerationRun enrich(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            wikidata.api.WikidataApiClient entityApi,
            GenerationLog log,
            work.CancellationToken cancellation,
            RunSteps steps) throws Exception {
        return enrich(previous, snapshot, entityApi, log, cancellation, steps, null);
    }

    /**
     * As above, consuming a plan its caller already compiled and ANNOUNCED. Compiling a
     * second one here would make the plan the run obeys a different object from the one
     * the reader was shown — and compiling writes, so it would also be a second writer.
     */
    public GenerationRun enrich(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            wikidata.api.WikidataApiClient entityApi,
            GenerationLog log,
            work.CancellationToken cancellation,
            RunSteps steps,
            datasource.api.SourceExecutionPlan announcedPlan) throws Exception {
        return enrich(previous, snapshot, entityApi, log, cancellation, steps,
                announcedPlan, null);
    }

    /** Interactive entry point consuming the plan and endpoint client its caller announced. */
    public GenerationRun enrich(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            wikidata.api.WikidataApiClient entityApi,
            GenerationLog log,
            work.CancellationToken cancellation,
            RunSteps steps,
            datasource.api.SourceExecutionPlan announcedPlan,
            WikidataSparqlClient dbpediaClient) throws Exception {
        datasource.graph.GraphDiscoveryState observed =
                WikidataGraphDiscoveryState.compute(snapshot, previous.dynamicObjects());
        CompiledPipelineRun run = CompiledPipelineRun.compile(PipelineRequest.enrich(
                snapshot, previous.checkpoint(observed)));
        return enrich(previous, run, entityApi, log, cancellation, steps,
                announcedPlan, dbpediaClient);
    }

    public GenerationRun enrich(
            GenerationRun previous,
            CompiledPipelineRun run,
            wikidata.api.WikidataApiClient entityApi,
            GenerationLog log,
            work.CancellationToken cancellation,
            RunSteps steps,
            datasource.api.SourceExecutionPlan announcedPlan,
            WikidataSparqlClient dbpediaClient) throws Exception {
        steps = steps == null ? RunSteps.SILENT : steps;
        if (run == null) throw new IllegalArgumentException("No compiled pipeline run");
        if (run.blocked()) throw new IllegalStateException(run.explain());
        GeneratedProjectModel snapshot = run.request().model();

        steps.started(GenerateDomainPipeline.PLAN,
                "Compile the model and stage the saved graph");

        // Say what is happening BEFORE each slow phase, not after: compiling the
        // runtime and copying tens of thousands of objects take real time and used to
        // pass in silence, so the run looked hung until the first fetch batch.
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        sink.message("Enrich: compiling the model's classes...\n");
        datasource.api.SourceExecutionPlan sourcePlan = announcedPlan != null ? announcedPlan
                : wikidata.explore.model.ModelSourceExecutionPlan.synchronizeAndCompile(
                        snapshot, datasource.Datasources.standard());
        // Compile the domain before ANY acquisition. Source-plan validation alone is
        // not enough: a broken class/field model must fail while this is still a plan,
        // not after semantic and external providers have spent minutes fetching data.
        wikidata.explore.compiled.CompiledProjectModel compiled = run.model();
        RuleNode plan = plan(snapshot);
        GeneratedViewableRuntime runtime = buildRuntime(snapshot);

        // STAGED, like every shared workflow action. Loading evidence can classify an
        // entity, change its carrier/type key, and unlock owned values; cancellation or
        // failure must not mutate the currently applied snapshot before Apply.
        sink.message("Enrich: walking " + previous.dynamicObjects().size()
                + " downloaded object(s) in a staged copy to find what the declarations "
                + "apply to...\n");
        List<WikidataDynamicObject> pool =
                wikidata.explore.transform.PoolCopy.deepCopy(previous.dynamicObjects());
        steps.completed(GenerateDomainPipeline.PLAN,
                pool.size() + " object(s) staged");
        sink.message("Enrich: fetching declared properties"
                + reportPendingLoads(snapshot) + "...\n");

        GenerationQualityTracker quality = new GenerationQualityTracker();
        steps.started(GenerateDomainPipeline.SEMANTIC,
                "Load newly declared properties, settle kinds and compose owned parts");
        SemanticConvergence.Result convergence = SemanticConvergence.apply(
                snapshot, pool, entityApi, log, previous.loadedDeclarations(), quality,
                sourcePlan);
        int loaded = convergence.loadedFields();
        steps.completed(GenerateDomainPipeline.SEMANTIC,
                convergence.loadedFields() + " field value(s), "
                        + convergence.classifiedKinds() + " kind(s), "
                        + convergence.ownedCreated() + " owned part(s)");
        steps.started(GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                "Acquire DBpedia fields, Wikipedia categories and native infobox values");
        ExternalSourceAcquisition.Result external = ExternalSourceAcquisition.apply(
                snapshot, pool, sourcePlan,
                StandardExternalSourceFamilies.services(dbpediaClient, entityApi),
                sink, cancellation,
                ExternalSourceAcquisition.FailurePolicy.CONTINUE_OPTIONAL);

        // Finalization is deliberately after semantic convergence: names, expectations
        // and vocabularies describe the final classes/fields rather than iteration one.
        steps.completed(GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                external.summary());
        steps.started(GenerateDomainPipeline.CONSTRUCT,
                "Replay the transforms that an already-reified pool can re-run");
        List<WikidataDynamicObject> projectedRecords = new ArrayList<>();
        wikidata.explore.transform.StatementTransforms.applyIdempotent(
                compiled, pool, log, projectedRecords);
        steps.completed(GenerateDomainPipeline.CONSTRUCT,
                projectedRecords.size() + " projected field value(s) changed");
        steps.started(GenerateDomainPipeline.LABELS,
                "Resolve the names of anything still showing as a bare QID");
        FinalLabelHydration.Result labels =
                FinalLabelHydration.apply(
                        pool, entityApi, log, quality, snapshot, sourcePlan);
        steps.completed(GenerateDomainPipeline.LABELS,
                labels.resolved() + " resolved, " + labels.missing() + " missing, "
                        + labels.unavailableQids().size() + " unavailable");
        steps.started(GenerateDomainPipeline.FINALIZE,
                "Canonicalize, prune, check expectations and build vocabularies");
        // Through the executor, over one state carried to materialization. Enrich MAY
        // acquire, so its context carries the client and finalization can resolve what
        // it needs — the same step, doing more because it was given more.
        PipelineState tailState =
                PipelineState.over(GraphCheckpoint.Stage.CONSTRUCTED_GRAPH, pool);
        PipelineContext tailContext =
                new PipelineContext(run, entityApi, log, cancellation);
        tailState.useRuntime(runtime);
        new PipelineExecutor().with(new FinalizeStep()).run(tailContext, tailState);
        DomainFinalization.Result finalization = tailState.finalization();

        steps.completed(GenerateDomainPipeline.FINALIZE,
                finalization.requiredDropped() + " dropped (required-field)"
                        + ownedRenamed(finalization));
        sink.message("Enrich: " + convergence.ownedCreated() + " component(s) materialized, "
                + loaded + " declared field value(s) loaded over "
                + pool.size() + " objects, " + external.summary()
                + " acquired (no re-extraction). "
                + "Re-materializing...\n");

        steps.started(GenerateDomainPipeline.MATERIALIZE,
                "Map the final graph into instances");
        new PipelineExecutor().with(new MaterializeStep()).run(tailContext, tailState);
        List<Viewable> instances = tailState.instances();
        steps.completed(GenerateDomainPipeline.MATERIALIZE,
                instances.size() + " instance(s) materialized");

        // The cached enriched pool is a SEPARATE deep copy taken at generation time, so
        // it does not hold what was just fetched — and it is the PRE-reify pool, so it
        // cannot simply be replaced with this one either (a later Remap would reify the
        // already-reified records twice). Dropping it puts Remap on its no-cached-pool
        // path, which re-materializes the enriched objects instead of re-transforming a
        // stale copy of them: the same state a snapshot has after being loaded.
        GenerationRun.Quality finalQuality = reconcileQuality(
                previous.quality(), quality.quality());
        return new GenerationRun(
                snapshot, previous.depth(), plan, pool, tailState.runtime(), instances, null,
                List.copyOf(convergence.completedDeclarations().values()), finalQuality,
                finalization.coverage(),
                // Enrich converges semantics, which composes owned parts; it never
                // reifies, so the self-reference rule genuinely did not run.
                GenerationRun.SelfReferenceAudit.notRun(),
                GenerationRun.OwnedCompositionAudit.ran(
                        convergence.ownedComponentsCreated()),
                GenerationRun.KindClassificationAudit.ran(
                        convergence.newlyClassifiedKinds()),
                GenerationRun.ProjectionAudit.ran(projectedRecords));
    }

    /**
     * Enrich re-runs every incomplete field/kind/label acquisition against the final
     * graph. Those are final-state assertions, so an old PARTIAL must not survive a
     * successful repair merely because its failure happened historically. Extraction
     * is not re-run by Enrich; its warnings therefore remain unresolved.
     */
    static GenerationRun.Quality reconcileQuality(
            GenerationRun.Quality prior, GenerationRun.Quality current) {
        java.util.LinkedHashSet<String> warnings = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> qids = new java.util.LinkedHashSet<>();
        if (prior != null && !prior.complete()) {
            prior.warnings().stream().filter(GenerationPipeline::notRepairableByEnrich)
                    .forEach(warnings::add);
        }
        if (current != null && !current.complete()) {
            warnings.addAll(current.warnings());
            qids.addAll(current.unavailableQids());
        }
        if (warnings.isEmpty() && qids.isEmpty()) {
            return GenerationRun.Quality.completeQuality();
        }
        return GenerationRun.Quality.partial(List.copyOf(warnings), List.copyOf(qids));
    }

    private static boolean notRepairableByEnrich(String warning) {
        if (warning == null) return false;
        String normalized = warning.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("child extraction")
                || normalized.contains("root extraction");
    }

    /** How many fields produce an owned component — for the preview to say what it is
     *  deliberately not doing. */
    private static int ownedComponentSites(GeneratedProjectModel snapshot) {
        int sites = 0;
        for (GeneratedClassModel clazz : snapshot.classes()) {
            if (clazz == null) continue;
            for (GeneratedFieldModel field : clazz.fields()) {
                if (field != null && field.type() == FieldType.ENTITY
                        && field.mapping().productionKind()
                                == wikidata.explore.model.FieldProductionKind.OWNED_COMPONENT) {
                    sites++;
                }
            }
        }
        return sites;
    }

    /** The class.field (PID) pairs an enrich run will try to load — named up front, so
     *  a run that turns out to fetch nothing says which declarations it considered. */
    private static String reportPendingLoads(GeneratedProjectModel snapshot) {
        List<String> declared = new ArrayList<>();
        for (GeneratedClassModel clazz : snapshot.classes()) {
            if (clazz == null) continue;
            wikidata.explore.model.MembershipPattern pattern =
                    wikidata.explore.model.MembershipPattern.of(clazz, snapshot);
            if (pattern != wikidata.explore.model.MembershipPattern.REFERENCED
                    && pattern != wikidata.explore.model.MembershipPattern.OWNED_COMPONENT) {
                continue;
            }
            for (GeneratedFieldModel field : clazz.fields()) {
                String pid = field == null || field.mapping() == null
                        ? "" : field.mapping().propertyPid();
                if (pid != null && pid.matches("(?i)P\\d+")) {
                    declared.add(clazz.className() + "." + field.name() + " (" + pid + ")");
                }
            }
        }
        return declared.isEmpty()
                ? " — none declared on a referenced-only or owned class"
                : ": " + String.join(", ", declared);
    }

    /**
     * Offline re-transform: run the PURE transforms (reify → restrictions →
     * inverts → canonicalize → companion-match) on a fresh deep copy of the cached
     * ENRICHED pool, so model edits to reify/dedup/canonical/facet config take
     * effect without re-fetching. Only the companion sets are reused (no P166
     * refetch). Mirrors GenerateDomainQuery's transform block.
     */
    private GenerationRun retransform(
            GenerationRun previous, GeneratedProjectModel snapshot,
            wikidata.explore.compiled.CompiledProjectModel compiledSnapshot,
            RuleNode plan, GeneratedViewableRuntime runtime,
            GenerationRun.RemapState rs, GenerationLog log,
            RunSteps steps) throws Exception {

        List<WikidataDynamicObject> pool =
                wikidata.explore.transform.PoolCopy.deepCopy(rs.enrichedPool());
        steps.completed(GenerateDomainPipeline.PLAN,
                pool.size() + " pre-reification object(s) staged");
        steps.started(GenerateDomainPipeline.CONSTRUCT,
                "Reify statements and replay local transforms");

        // The ONE transform sequence (#97). Remap differs from Generate only in where
        // the companion sets come from: it replays the ones Generate cached.
        wikidata.explore.transform.StatementTransforms.Result transformed =
                wikidata.explore.transform.StatementTransforms.apply(
                        snapshot, compiledSnapshot, pool,
                        records -> rs.companionSets(), log);
        List<WikidataDynamicObject> reified = transformed.reified();
        int filled = transformed.projectedFields();
        steps.completed(GenerateDomainPipeline.CONSTRUCT,
                reified.size() + " statement record(s), " + filled
                        + " projected field value(s) changed");

        steps.started(GenerateDomainPipeline.SEMANTIC,
                "Settle entity kinds and compose owned parts");
        wikidata.explore.transform.SnapshotEntityKindClassifier.Result kinds =
                wikidata.explore.transform.SnapshotEntityKindClassifier.apply(
                        snapshot, pool, previous.dynamicObjects(), log);
        // Kind membership is an input to owned composition: a Person that arrived as
        // Nominee must become Person before Person.structuredName can be produced.
        wikidata.explore.transform.OwnedComponents.Result owned =
                wikidata.explore.transform.OwnedComponents.apply(
                        snapshot, pool, previous.dynamicObjects(), log);
        owned.addTo(pool);
        wikidata.explore.transform.ReferentClassStamp.apply(
                snapshot, owned.components());
        steps.completed(GenerateDomainPipeline.SEMANTIC,
                kinds.classified() + " kind(s), " + owned.created() + " owned part(s)");
        steps.started(GenerateDomainPipeline.FINALIZE,
                "Finalize and validate the transformed graph");
        DomainFinalization.Result finalization = DomainFinalization.apply(
                snapshot, compiledSnapshot, pool, reified, previous.dynamicObjects(),
                null, log);
        int restricted = finalization.requiredDropped();
        steps.completed(GenerateDomainPipeline.FINALIZE,
                restricted + " dropped (required-field)"
                        + ownedRenamed(finalization));

        if (log != null) {
            log.message("Remap (retransform): " + pool.size()
                    + " objects, " + reified.size() + " reified, "
                    + filled + " projected field(s) filled, "
                    + kinds.classified() + " entity kind(s) assigned, "
                    + restricted + " dropped (required-field).\n");
        }

        steps.started(GenerateDomainPipeline.MATERIALIZE,
                "Map the final graph into instances");
        List<Viewable> instances = materialize(runtime, pool);
        steps.completed(GenerateDomainPipeline.MATERIALIZE,
                instances.size() + " instance(s) materialized");

        return new GenerationRun(
                snapshot, previous.depth(), plan, pool, runtime, instances, rs,
                previous.loadedDeclarations(), previous.quality(), finalization.coverage(),
                GenerationRun.SelfReferenceAudit.ran(transformed.selfReferenceFindings()),
                GenerationRun.OwnedCompositionAudit.ran(owned.createdComponents()),
                GenerationRun.KindClassificationAudit.ran(kinds.newlyClassified()),
                GenerationRun.ProjectionAudit.ran(
                        transformed.projectionChangedInstances()));
    }

    /**
     * True when the snapshot would extract exactly what the previous run
     * already downloaded. The extractor's only inputs are (plan, depth),
     * so equal plans and depths fetch the same data.
     */
    public boolean sameExtraction(
            GenerationRun previous,
            GeneratedProjectModel snapshot,
            int depth) {

        if (previous == null || depth != previous.depth()) {
            return false;
        }

        String a = planSignature(previous.plan());
        String b = planSignature(plan(snapshot));

        return a != null && a.equals(b);
    }

    private static String planSignature(RuleNode plan) {
        try {
            return new RuleTreeSerializer()
                    .mapper()
                    .writeValueAsString(plan);
        } catch (Exception e) {
            return null;
        }
    }

    /** A part is named for its owner, and an owner's name can settle after the part was
     *  made. Silent when nothing moved — a permanent "0" reads as a stage doing nothing,
     *  and the number is only interesting when it is not zero. */
    private static String ownedRenamed(DomainFinalization.Result finalization) {
        int renamed = finalization.ownedRenamed();
        int ownerless = finalization.ownerlessParts();
        return (renamed == 0 ? "" : ", " + renamed + " owned part(s) renamed")
                + (ownerless == 0 ? ""
                        : ", " + ownerless + " owned part(s) dropped as ownerless");
    }

}
