package wikidata.explore.generation;

import wikidata.explore.codegen.GeneratedQuizableRuntimeBuilder;
import wikidata.explore.codegen.GeneratedQuizableRuntime;
import wikidata.explore.codegen.GeneratedQuizableMapper;
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
import quiz.Quizable;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.function.Consumer;

/**
 * The generation pipeline as separately callable stages:
 *
 *   plan          model snapshot  -> RuleNode query plan
 *   extract       plan + depth    -> downloaded dynamic objects  (slow, network)
 *   buildRuntime  model snapshot  -> compiled generated class    (fast, local)
 *   materialize   runtime + data  -> mapped Quizable instances   (fast, local)
 *
 * fullRun() chains all four. remap() reuses a previous run's download
 * and re-runs only the local stages; callers must check
 * sameExtraction() first.
 */
public class GenerationPipeline {

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

    // Fills DBpedia-sourced root fields (Wikipedia infobox) after the Wikidata
    // extraction, joined by owl:sameAs QID. No-op unless the root class has any
    // DBpedia field; failures are logged, not fatal (the run still succeeds).
    // Build every production = INVERT field from the forward references already in
    // the pool (e.g. Category.nominees = reverse of Oscarnominations.categories).
    private void applyModelInverts(
            GeneratedProjectModel snapshot,
            List<WikidataDynamicObject> pool,
            GenerationLog log) {

        wikidata.explore.transform.ModelInverts.apply(snapshot, pool, log);
    }

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

    public GeneratedQuizableRuntime buildRuntime(
            GeneratedProjectModel snapshot) throws Exception {

        // Compile every class in the project (root + e.g. Star), so a
        // multi-class run maps each object to its own type.
        return new GeneratedQuizableRuntimeBuilder()
                .build(snapshot);
    }

    public List<Quizable> materialize(
            GeneratedQuizableRuntime runtime,
            List<WikidataDynamicObject> dynamicObjects) throws Exception {

        return new GeneratedQuizableMapper(runtime)
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
                if (pid == null || !pid.matches("P\\d+")) {
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

        RuleNode plan = plan(snapshot);

        List<WikidataDynamicObject> dynamicObjects =
                extract(client, plan, depth, log);

        enrichFromDBpedia(snapshot, dynamicObjects, log);

        // Derived (production = INVERT) fields: build each as the reverse of a
        // forward reference already in the pool — no query, no extra depth.
        applyModelInverts(snapshot, dynamicObjects, log);

        // displayName from each class's CanonicalSpec (see Canonicalization).
        wikidata.explore.transform.Canonicalization.apply(snapshot, dynamicObjects, log);

        GeneratedQuizableRuntime runtime = buildRuntime(snapshot);
        resolveUnits(snapshot, client, log);

        List<Quizable> instances =
                materialize(runtime, dynamicObjects);

        return new GenerationRun(
                snapshot, depth, plan, dynamicObjects, runtime, instances);
    }

    public GenerationRun remap(
            GenerationRun previous,
            GeneratedProjectModel snapshot) throws Exception {

        RuleNode plan = plan(snapshot);
        GeneratedQuizableRuntime runtime = buildRuntime(snapshot);

        GenerationRun.RemapState rs = previous.remapState();
        if (rs != null && rs.enrichedPool() != null && !rs.enrichedPool().isEmpty()) {
            return retransform(previous, snapshot, plan, runtime, rs);
        }

        // No cached transform inputs (e.g. a single-class run): display-only remap.
        // Re-apply canonicalization so a CanonicalSpec edited since the last
        // generation updates the pool's displayNames without a re-extract.
        wikidata.explore.transform.Canonicalization.apply(
                snapshot, previous.dynamicObjects(), null);

        List<Quizable> instances =
                materialize(runtime, previous.dynamicObjects());

        return new GenerationRun(
                snapshot, previous.depth(), plan,
                previous.dynamicObjects(), runtime, instances, rs);
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
            RuleNode plan, GeneratedQuizableRuntime runtime,
            GenerationRun.RemapState rs) throws Exception {

        List<WikidataDynamicObject> pool =
                wikidata.explore.transform.PoolCopy.deepCopy(rs.enrichedPool());

        java.util.Set<WikidataDynamicObject> demoted =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<WikidataDynamicObject> reified =
                wikidata.explore.transform.ModelStatementReifications.reify(
                        snapshot, pool, null, demoted);   // pool.addAll(reified) inside

        wikidata.explore.transform.FieldValueRestrictions.apply(snapshot, pool);
        wikidata.explore.transform.ModelInverts.apply(snapshot, pool, null);
        // Year projections (e.g. Nomination.year <- YEAR(edition.date)) — same
        // transform stage as the generate path; pool already holds the reified
        // records + their referenced (dated) entities.
        wikidata.explore.transform.ModelYearProjections.apply(snapshot, pool, null);
        wikidata.explore.transform.Canonicalization.apply(snapshot, pool, null);
        wikidata.explore.transform.Canonicalization.apply(snapshot, reified, null);
        wikidata.explore.transform.CompanionMatch.applyWithSets(
                snapshot, reified, rs.companionSets(), null);

        // Drop the dropped-duplicate stubs from the served pool (not just untype).
        pool.removeIf(demoted::contains);

        List<Quizable> instances = materialize(runtime, pool);

        return new GenerationRun(
                snapshot, previous.depth(), plan, pool, runtime, instances, rs);
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
}
