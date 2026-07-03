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
                "SPARQL",
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
                            wikidata.explore.query.log.LogNode child =
                                    step.beginSubquery(title, request);
                            return new Running() {
                                @Override public void done(String summary) {
                                    step.completeSubquery(child, summary);
                                }
                                @Override public void failed(String error) {
                                    step.failSubquery(child, error);
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

                    List<WikidataDynamicObject> allRoots = new ArrayList<>();
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
                        allRoots.addAll(roots);
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
                    List<WikidataDynamicObject> reified =
                            wikidata.explore.transform.ModelStatementReifications.apply(
                                    project, new ArrayList<>(shared.values()),
                                    context.sparql(), genLog);
                    allRoots.addAll(reified);

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

                    // Canonicalize displayName from each class's CanonicalSpec, so the
                    // stored/served name is the configured one (e.g. a Nomination shows
                    // its nominee, not the reify "{forWork} — {category}" heuristic).
                    // Applied to the raw pool the snapshot is built from.
                    wikidata.explore.transform.Canonicalization.apply(
                            project, shared.values(), genLog);
                    wikidata.explore.transform.Canonicalization.apply(
                            project, reified, genLog);

                    // Companion-match (production = COMPANION_MATCH) boolean fields
                    // (e.g. Nomination.won): load each field's companion statements
                    // (P166/P1346 wins) for the reified subjects and mark matches.
                    // Runs before materialize so the flag is on the atom when typed.
                    wikidata.explore.transform.CompanionMatch.apply(
                            project, reified, context.sparql(), genLog);

                    // ONE shared mapper over ALL roots: each QID -> one typed
                    // instance, and cross-class references resolve to those same
                    // typed instances (no duplicates, no raw cross-refs).
                    List<Quizable> allInstances =
                            pipeline.materialize(runtime, allRoots);

                    // The snapshot is the whole shared pool (every class's roots +
                    // their referenced children) plus the reified records.
                    List<WikidataDynamicObject> pool =
                            new ArrayList<>(shared.values());
                    pool.addAll(reified);
                    step.summary(pool.size() + " objects across "
                            + classesRun + " class(es)");
                    return new GenerationRun(
                            project, 0, rootPlan, pool, runtime, allInstances);
                });
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
