package wikidata.explore.generation;

import wikidata.WikidataIds;

import wikidata.explore.codegen.GeneratedViewableRuntimeBuilder;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.codegen.GeneratedViewableMapper;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.extract.DBpediaEnrichment;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldType;
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

    // Fills DBpedia-sourced root fields (Wikipedia infobox) after the Wikidata
    // extraction, joined by owl:sameAs QID. No-op unless the root class has any
    // DBpedia field; failures are logged, not fatal (the run still succeeds).


    private void enrichFromDBpedia(
            GeneratedProjectModel snapshot,
            List<WikidataDynamicObject> roots,
            GenerationLog log) {

        GeneratedClassModel root = snapshot.rootClass();
        if (root == null) {
            return;
        }
        boolean hasDbpedia = false;
        for (GeneratedFieldModel f : root.fields()) {
            if (f != null
                    && f.mapping().sourceType() == FieldSourceType.DBPEDIA
                    && !f.mapping().propertyPid().isBlank()) {
                hasDbpedia = true;
                break;
            }
        }
        if (!hasDbpedia) {
            return;
        }

        try (WikidataSparqlClient dbpedia = new WikidataSparqlClient(
                "quiz-modelbuilder (ggyepesi@gmail.com)", 2,
                WikidataSparqlClient.DBPEDIA_ENDPOINT)) {
            dbpedia.log(log::message);
            new DBpediaEnrichment().enrich(roots, root, dbpedia, log::message);
        } catch (Exception e) {
            log.message("DBpedia enrichment failed: " + e.getMessage() + "\n");
        }
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
        FieldSourceMapping im = cls.effectiveInstanceMapping(project);
        if (im != null && !im.sourceQid().isBlank() && !im.propertyPid().isBlank()) {
            return "?value wdt:" + RuleNode.cleanPid(im.propertyPid())
                    + " wd:" + im.sourceQid() + " .";
        }
        return null;
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
     * client; interactive Generate instances supplies both dependencies. */
    public GenerationRun fullRun(
            GeneratedProjectModel snapshot,
            int depth,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi,
            work.CancellationToken cancellation) throws Exception {

        RuleNode plan = plan(snapshot);

        List<WikidataDynamicObject> dynamicObjects =
                extract(client, plan, depth, log);

        enrichFromDBpedia(snapshot, dynamicObjects, log);
        if (entityApi != null) {
            WikipediaInfoboxAcquisition.apply(snapshot, dynamicObjects,
                    log == null ? GenerationLog.NOOP : log,
                    cancellation == null ? new work.CancellationToken() : cancellation,
                    entityApi);
        }

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
                snapshot, dynamicObjects, log);

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

        steps = steps == null ? RunSteps.SILENT : steps;
        steps.started(GenerateDomainPipeline.PLAN,
                "Compile the model and stage the saved graph");
        RuleNode plan = plan(snapshot);
        GeneratedViewableRuntime runtime = buildRuntime(snapshot);

        GenerationRun.RemapState rs = previous.remapState();
        RemapScope scope = RemapScope.of(previous);
        if (scope.retransform()) {
            return retransform(previous, snapshot, plan, runtime, rs, log, steps);
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
                snapshot, pool, log, projectedRecords);
        steps.completed(GenerateDomainPipeline.CONSTRUCT,
                filled + " projected field value(s) changed");
        steps.started(GenerateDomainPipeline.SEMANTIC,
                "Settle entity kinds and compose owned parts");
        wikidata.explore.transform.SnapshotEntityKindClassifier.Result kinds =
                wikidata.explore.transform.SnapshotEntityKindClassifier.apply(
                        snapshot, pool, previous.dynamicObjects(), log);
        wikidata.explore.transform.OwnedComponents.Result owned =
                wikidata.explore.transform.OwnedComponents.apply(
                        snapshot, pool, previous.dynamicObjects(), log);
        owned.addTo(pool);
        wikidata.explore.transform.ReferentClassStamp.apply(snapshot, owned.components());
        steps.completed(GenerateDomainPipeline.SEMANTIC,
                kinds.classified() + " kind(s), " + owned.created() + " owned part(s)");
        steps.started(GenerateDomainPipeline.FINALIZE,
                "Finalize and validate the transformed graph");
        wikidata.explore.compiled.CompiledProjectModel compiled =
                wikidata.explore.compiled.ProjectModelCompiler.compile(snapshot);
        DomainFinalization.Result finalization = DomainFinalization.apply(
                snapshot, compiled, pool, List.of(), null, log);
        int restricted = finalization.requiredDropped();
        steps.completed(GenerateDomainPipeline.FINALIZE,
                restricted + " dropped (required-field)"
                        + ownedRenamed(finalization));
        if (log != null) {
            log.message("Remap (idempotent transforms only): "
                    + pool.size() + " objects re-materialized, "
                    + filled + " projected field(s) filled, "
                    + kinds.classified() + " entity kind(s) assigned, "
                    + restricted + " dropped (required-field).\n"
                    + scope.limitation() + "\n");
        }

        steps.started(GenerateDomainPipeline.MATERIALIZE,
                "Map the final graph into instances");
        List<Viewable> instances = materialize(runtime, pool);
        steps.completed(GenerateDomainPipeline.MATERIALIZE,
                instances.size() + " instance(s) materialized");

        return new GenerationRun(
                snapshot, previous.depth(), plan,
                pool, runtime, instances, rs,
                previous.loadedDeclarations(), previous.quality(), finalization.coverage(),
                GenerationRun.SelfReferenceAudit.notRun(),
                GenerationRun.OwnedCompositionAudit.ran(owned.createdComponents()),
                GenerationRun.KindClassificationAudit.ran(kinds.newlyClassified()),
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
        steps = steps == null ? RunSteps.SILENT : steps;

        steps.started(GenerateDomainPipeline.PLAN,
                "Compile the model and stage the saved graph");

        // Say what is happening BEFORE each slow phase, not after: compiling the
        // runtime and copying tens of thousands of objects take real time and used to
        // pass in silence, so the run looked hung until the first fetch batch.
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        sink.message("Enrich: compiling the model's classes...\n");
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
                snapshot, pool, entityApi, log, previous.loadedDeclarations(), quality);
        int loaded = convergence.loadedFields();
        steps.completed(GenerateDomainPipeline.SEMANTIC,
                convergence.loadedFields() + " field value(s), "
                        + convergence.classifiedKinds() + " kind(s), "
                        + convergence.ownedCreated() + " owned part(s)");
        steps.started(GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                "Acquire Wikipedia category memberships and native infobox values");
        WikipediaCategoryAcquisition.Result categories =
                WikipediaCategoryAcquisition.apply(snapshot, pool, sink,
                        cancellation == null ? new work.CancellationToken() : cancellation,
                        entityApi);
        WikipediaInfoboxAcquisition.Result infoboxes = WikipediaInfoboxAcquisition.apply(
                snapshot, pool, sink,
                cancellation == null ? new work.CancellationToken() : cancellation,
                entityApi);

        // Finalization is deliberately after semantic convergence: names, expectations
        // and vocabularies describe the final classes/fields rather than iteration one.
        wikidata.explore.compiled.CompiledProjectModel compiled =
                wikidata.explore.compiled.ProjectModelCompiler.compile(snapshot);
        steps.completed(GenerateDomainPipeline.EXTERNAL_EVIDENCE,
                categories.memberships() + " category membership(s), "
                        + infoboxes.values() + " infobox value(s)");
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
                FinalLabelHydration.apply(pool, entityApi, log, quality);
        steps.completed(GenerateDomainPipeline.LABELS,
                labels.resolved() + " resolved, " + labels.missing() + " missing, "
                        + labels.unavailableQids().size() + " unavailable");
        steps.started(GenerateDomainPipeline.FINALIZE,
                "Canonicalize, prune, check expectations and build vocabularies");
        DomainFinalization.Result finalization = DomainFinalization.apply(
                snapshot, compiled, pool, List.of(), entityApi, log);

        steps.completed(GenerateDomainPipeline.FINALIZE,
                finalization.requiredDropped() + " dropped (required-field)"
                        + ownedRenamed(finalization));
        sink.message("Enrich: " + convergence.ownedCreated() + " component(s) materialized, "
                + loaded + " declared field value(s) loaded over "
                + pool.size() + " objects, " + categories.memberships()
                + " Wikipedia category membership(s) and " + infoboxes.values()
                + " native infobox value(s) acquired (no re-extraction). "
                + "Re-materializing...\n");

        steps.started(GenerateDomainPipeline.MATERIALIZE,
                "Map the final graph into instances");
        List<Viewable> instances = materialize(runtime, pool);
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
                snapshot, previous.depth(), plan, pool, runtime, instances, null,
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
            RuleNode plan, GeneratedViewableRuntime runtime,
            GenerationRun.RemapState rs, GenerationLog log,
            RunSteps steps) throws Exception {

        List<WikidataDynamicObject> pool =
                wikidata.explore.transform.PoolCopy.deepCopy(rs.enrichedPool());
        steps.completed(GenerateDomainPipeline.PLAN,
                pool.size() + " pre-reification object(s) staged");
        steps.started(GenerateDomainPipeline.CONSTRUCT,
                "Reify statements and replay local transforms");

        // Reify from the compiled model — parity-proven with the editable one.
        wikidata.explore.compiled.CompiledProjectModel compiledSnapshot =
                wikidata.explore.compiled.ProjectModelCompiler.compile(snapshot);
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
        return renamed == 0 ? "" : ", " + renamed + " owned part(s) renamed";
    }

}
