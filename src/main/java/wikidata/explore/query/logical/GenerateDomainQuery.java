package wikidata.explore.query.logical;

import quiz.Quizable;
import wikidata.explore.codegen.GeneratedQuizableRuntime;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataObjectRegistry;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public GenerateDomainQuery(GeneratedProjectModel project) {
        this.project = project;
    }

    @Override public String purpose() { return "Generate domain"; }

    @Override public String skeleton() {
        return "each class as a root -> shared registry -> one multi-type snapshot";
    }

    @Override public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("domain", project.name());
        p.put("classes", String.valueOf(project.classes().size()));
        return p;
    }

    @Override
    public GenerationRun execute(QueryContext context) throws Exception {
        return context.step(
                "Generate domain \"" + project.name() + "\"",
                "Domain",   // container node, not a SPARQL query (no "Open in query service")
                null,
                Map.of("domain", project.name()),
                step -> {
                    GenerationLog genLog = new GenerationLog() {
                        @Override public void message(String text) {
                            context.message(text);
                        }
                        @Override public void subquery(
                                String title, String request, String summary) {
                            step.subquery(title, request, summary);
                        }
                        @Override public void subqueryFailed(
                                String title, String request, String error) {
                            step.subqueryFailed(title, request, error);
                        }
                        @Override public Running subqueryStarted(
                                String title, String request) {
                            return running(step, title, request);
                        }
                        @Override public Group group(String title) {
                            wikidata.explore.query.log.LogStep sub =
                                    step.beginGroup(title);
                            return new Group() {
                                private int n = 0;
                                @Override public void message(String t) {
                                    context.message(t);
                                }
                                @Override public void subquery(
                                        String ti, String r, String s) {
                                    sub.subquery(ti, r, s);
                                    n++;
                                }
                                @Override public void subqueryFailed(
                                        String ti, String r, String e) {
                                    sub.subqueryFailed(ti, r, e);
                                    n++;
                                }
                                @Override public Running subqueryStarted(
                                        String ti, String r) {
                                    n++;
                                    return running(sub, ti, r);
                                }
                                @Override public void close() {
                                    sub.completeGroup(n + " request(s)");
                                }
                            };
                        }
                    };

                    GenerationPipeline pipeline = new GenerationPipeline();
                    WikidataObjectRegistry shared = new WikidataObjectRegistry();

                    // ONE runtime for the whole domain — every class compiled
                    // together in one package/loader, so typed cross-references
                    // (Character <-> Episode) resolve and map to shared typed
                    // instances rather than raw objects.
                    GeneratedQuizableRuntime runtime = pipeline.buildRuntime(project);

                    // Resolve each quantity field's unit once (the truthy value
                    // drops it), so the mapper can render "1538 K".
                    pipeline.resolveUnits(project, context.sparql(), genLog);

                    RuleNode rootPlan = null;
                    int classesRun = 0;

                    for (GeneratedClassModel cls : project.classes()) {
                        if (!generatable(cls)) {
                            genLog.message("Skip class \"" + cls.className()
                                    + "\" — no membership type or seed QIDs.\n");
                            continue;
                        }
                        GeneratedProjectModel rooted = rootedAt(cls.className());
                        RuleNode plan = pipeline.plan(rooted);
                        genLog.message("=== Class \"" + cls.className()
                                + "\" (depth " + cls.generationDepth() + ") ===\n");

                        List<WikidataDynamicObject> roots = pipeline.extract(
                                context.sparql(), plan, cls.generationDepth(),
                                genLog, shared);
                        genLog.message("  -> " + roots.size() + " "
                                + cls.className() + "\n");

                        if (rootPlan == null) {
                            rootPlan = plan;
                        }
                        classesRun++;
                    }

                    // STATEMENT-reification classes (e.g. Nomination = the P1411
                    // statements of Oscarnominations, with year/forWork/nominee
                    // qualifier fields): load the qualifiers + reify into records.
                    // The registry has no add, so carry the new records alongside.
                    // Split enrich (network) / reify (pure) so we can cache the
                    // ENRICHED pool for an offline Remap re-transform. Deep-copy it
                    // BEFORE reify mutates the statements in place.
                    List<WikidataDynamicObject> reifyPool =
                            new ArrayList<>(shared.values());
                    wikidata.explore.transform.ModelStatementReifications.enrich(
                            project, reifyPool, context.sparql(), genLog);
                    List<WikidataDynamicObject> enrichedSnapshot =
                            wikidata.explore.transform.PoolCopy.deepCopy(shared.values());
                    java.util.Set<WikidataDynamicObject> demoted =
                            java.util.Collections.newSetFromMap(
                                    new java.util.IdentityHashMap<>());
                    List<WikidataDynamicObject> reified =
                            wikidata.explore.transform.ModelStatementReifications.reify(
                                    project, reifyPool, genLog, demoted);

                    // Enforce per-field allowedQids (the query layer doesn't): e.g.
                    // the auto-injected `target` field restricted to the membership's
                    // Oscar categories drops Grammy categories that share P1411.
                    wikidata.explore.transform.FieldValueRestrictions.apply(
                            project, shared.values());

                    // Derived (production = INVERT) fields: reverse forward
                    // references already in the pool (e.g. Category.nominees =
                    // reverse of Oscarnominations.categories) — no query, no depth.
                    wikidata.explore.transform.ModelInverts.apply(
                            project, shared.values(), genLog);

                    // Year projections (a DATE field overlaid from a referent's date,
                    // e.g. Nomination.year <- YEAR(edition.date)) — a field-level
                    // transform, authoritative + year-precision. Over the base pool
                    // AND the reified records, so a reified Nomination sees the
                    // generated Edition (with its date) by qid.
                    List<WikidataDynamicObject> forYear =
                            new ArrayList<>(shared.values());
                    forYear.addAll(reified);
                    wikidata.explore.transform.ModelYearProjections.apply(
                            project, forYear, genLog);

                    // Canonicalize displayName from each class's CanonicalSpec, so the
                    // stored/served name is the configured one (e.g. a Nomination shows
                    // its nominee, not the reify "{forWork} — {category}" heuristic).
                    // Applied to the raw pool the snapshot is built from.
                    wikidata.explore.transform.Canonicalization.apply(
                            project, shared.values(), genLog);
                    wikidata.explore.transform.Canonicalization.apply(
                            project, reified, genLog);

                    // Companion-match (production = COMPANION_MATCH) boolean fields
                    // (e.g. Nomination.won). Load the sets (network), then match
                    // (pure) — caching the sets so a Remap re-matches offline.
                    java.util.Map<String, java.util.Set<java.util.List<String>>>
                            companionSets =
                            wikidata.explore.transform.CompanionMatch.loadSets(
                                    project, reified, context.sparql(), genLog);
                    wikidata.explore.transform.CompanionMatch.applyWithSets(
                            project, reified, companionSets, genLog);

                    // Prune dead-stub ghosts (a QID whose page is gone: no label,
                    // no fields) after all fields are populated but before mapping —
                    // unlinks them from every ref (e.g. a nominee's `type`) so they
                    // don't materialize as phantom types and so generation agrees
                    // with the (deduped, reachability-based) saved snapshot.
                    List<WikidataDynamicObject> everything =
                            new ArrayList<>(shared.values());
                    everything.addAll(reified);
                    java.util.Set<WikidataDynamicObject> deadStubs =
                            wikidata.explore.transform.DeadStubPrune.apply(
                                    everything, genLog);

                    // The served/saved pool: the whole shared pool (every class's
                    // roots + their referenced children) minus demoted reified
                    // duplicates and dead stubs, plus the reified records. This IS
                    // the artifact the web serves and reload maps, so build it first.
                    List<WikidataDynamicObject> pool = new ArrayList<>();
                    for (WikidataDynamicObject o : shared.values()) {
                        if (!demoted.contains(o) && !deadStubs.contains(o)) {
                            pool.add(o);
                        }
                    }
                    pool.addAll(reified);

                    // Drop reified records missing a required field (e.g. a Nomination
                    // with no ceremony edition — the ceremony-less phantom). Same
                    // restrict the Remap path runs.
                    wikidata.explore.transform.RequiredFieldRestrictions.apply(
                            project, pool, genLog);

                    // ONE shared mapper over the SERVED POOL: each QID -> one typed
                    // instance, cross-refs resolve to those same instances. Mapping
                    // the pool (rather than the extractor's roots) makes the
                    // generation preview identical to load/serve — otherwise a qid
                    // stamped as a class root but stored untyped in the pool would
                    // show at generation yet vanish on reload.
                    List<Quizable> allInstances =
                            pipeline.materialize(runtime, pool);

                    step.summary(pool.size() + " objects across "
                            + classesRun + " class(es)");
                    return new GenerationRun(
                            project, 0, rootPlan, pool, runtime, allInstances,
                            new GenerationRun.RemapState(enrichedSnapshot, companionSets));
                });
    }

    // A running sub-query node under {@code step}, finished via the handle.
    private static GenerationLog.Running running(
            wikidata.explore.query.log.LogStep step, String title, String request) {
        wikidata.explore.query.log.LogNode child = step.beginSubquery(title, request);
        return new GenerationLog.Running() {
            @Override public void done(String summary) {
                step.completeSubquery(child, summary);
            }
            @Override public void failed(String error) {
                step.failSubquery(child, error);
            }
        };
    }

    // Generatable = has something to query: a membership type, extra types, or
    // an explicit seed-QID set. (A bare reference-only class is skipped.)
    private boolean generatable(GeneratedClassModel cls) {
        // STATEMENT-reification classes aren't fetched by a normal root query —
        // they're produced by ModelStatementReifications (qualifier-load + reify).
        if (cls.reifiesStatements()) {
            return false;
        }
        FieldSourceMapping m = cls.effectiveInstanceMapping(project);
        return (m != null && !m.sourceQid().isBlank())
                || !cls.seedQids().isEmpty()
                || (m != null && !m.additionalTypeQids().isEmpty());
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

    @Override public int rowCount(GenerationRun r) { return r == null ? 0 : r.size(); }
    @Override public String summary(GenerationRun r) { return rowCount(r) + " objects"; }
}
