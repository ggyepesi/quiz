package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataObjectRegistry;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Datasource;
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
                                // Batches within a group now record concurrently
                                // (QualifierLoader fans out), so count atomically.
                                private final java.util.concurrent.atomic
                                        .AtomicInteger n =
                                        new java.util.concurrent.atomic
                                                .AtomicInteger();
                                @Override public void message(String t) {
                                    context.message(t);
                                }
                                @Override public void subquery(
                                        String ti, String r, String s) {
                                    sub.subquery(ti, r, s);
                                    n.incrementAndGet();
                                }
                                @Override public void subqueryFailed(
                                        String ti, String r, String e) {
                                    sub.subqueryFailed(ti, r, e);
                                    n.incrementAndGet();
                                }
                                @Override public Running subqueryStarted(
                                        String ti, String r) {
                                    n.incrementAndGet();
                                    return running(sub, ti, r);
                                }
                                @Override public void close() {
                                    sub.completeGroup(n.get() + " request(s)");
                                }
                            };
                        }
                    };

                    GenerationPipeline pipeline = new GenerationPipeline();
                    WikidataObjectRegistry shared = new WikidataObjectRegistry();
                    wikidata.api.WikidataApiClient entityApi =
                            new wikidata.api.WikidataApiClient(
                                    wikidata.api.WikidataApiClient.DEFAULT_USER_AGENT)
                                    .cancellation(context.cancellation());

                    // ONE runtime for the whole domain — every class compiled
                    // together in one package/loader, so typed cross-references
                    // (Character <-> Episode) resolve and map to shared typed
                    // instances rather than raw objects.
                    // Resolve each quantity field's unit once (the truthy value
                    // drops it), so the mapper can render "1538 K". Before building
                    // the runtime, so it holds fully-resolved field models.
                    pipeline.resolveUnits(project, context.sparql(Datasource.WIKIDATA), genLog);

                    GeneratedViewableRuntime runtime = pipeline.buildRuntime(project);

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
                                context.sparql(Datasource.WIKIDATA), plan, cls.generationDepth(),
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
                    // Reify from the COMPILED model (statement source, fields and
                    // canonical identity already resolved). Byte-for-byte parity
                    // with the editable-model derivation is proven; the compile also
                    // fails fast if the model is structurally invalid.
                    wikidata.explore.compiled.CompiledProjectModel compiledProject =
                            wikidata.explore.compiled.ProjectModelCompiler.compile(project);
                    wikidata.explore.transform.ModelStatementReifications.enrich(
                            compiledProject, reifyPool, context.sparql(Datasource.WIKIDATA), genLog);
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
                    java.util.Set<WikidataDynamicObject> demoted =
                            java.util.Collections.newSetFromMap(
                                    new java.util.IdentityHashMap<>());
                    List<WikidataDynamicObject> reified =
                            wikidata.explore.transform.ModelStatementReifications.reify(
                                    compiledProject, reifyPool, genLog, demoted);

                    // Enforce per-field allowedQids (the query layer doesn't): e.g.
                    // the auto-injected `target` field restricted to the membership's
                    // Oscar categories drops Grammy categories that share P1411.
                    wikidata.explore.transform.FieldValueRestrictions.apply(
                            compiledProject, shared.values());

                    // Derived (production = INVERT) fields: reverse forward
                    // references already in the pool (e.g. Category.nominees =
                    // reverse of Oscarnominations.categories) — no query, no depth.
                    wikidata.explore.transform.ModelInverts.apply(
                            compiledProject, shared.values(), genLog);

                    // Year projections (a DATE field overlaid from a referent's date,
                    // e.g. Nomination.year <- YEAR(edition.date)) — a field-level
                    // transform, authoritative + year-precision. Over the base pool
                    // AND the reified records, so a reified Nomination sees the
                    // generated Edition (with its date) by qid.
                    List<WikidataDynamicObject> forYear =
                            new ArrayList<>(shared.values());
                    forYear.addAll(reified);
                    wikidata.explore.transform.ModelYearProjections.apply(
                            compiledProject, forYear, genLog);

                    // Canonicalize displayName from each class's CanonicalSpec, so the
                    // stored/served name is the configured one (e.g. a Nomination shows
                    // its nominee, not the reify "{forWork} — {category}" heuristic).
                    // Applied to the raw pool the snapshot is built from.
                    wikidata.explore.transform.Canonicalization.apply(
                            compiledProject, shared.values(), genLog);
                    wikidata.explore.transform.Canonicalization.apply(
                            compiledProject, reified, genLog);

                    // Companion-match (production = COMPANION_MATCH) boolean fields
                    // (e.g. Nomination.won). Load the sets (network), then match
                    // (pure) — caching the sets so a Remap re-matches offline.
                    java.util.Map<String, java.util.Set<java.util.List<String>>>
                            companionSets =
                            wikidata.explore.transform.CompanionMatch.loadSets(
                                    compiledProject, reified, context.sparql(Datasource.WIKIDATA), genLog);
                    wikidata.explore.transform.CompanionMatch.applyWithSets(
                            compiledProject, reified, companionSets, genLog);

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

                    // Clean internal plumbing off the served pool: (1) un-stamp the
                    // __subject_ load type — discovered POPULATION subjects sourced the
                    // reify but are not a served product, so they stay as plain
                    // labelled referents (nominee/forWork/source), not a served type;
                    // (2) strip __-prefixed statement-list fields (e.g. __Nomination) —
                    // the reify already promoted those to top-level records, so a
                    // referent must not carry the raw statement list (a nominee/forWork
                    // showing __Nomination). Runs after reify + transforms have used them.
                    int unstampedSubjects = 0;
                    int strippedFields = 0;
                    for (WikidataDynamicObject o : shared.values()) {
                        if (o == null) {
                            continue;
                        }
                        // RETRACT, not clear: type(null) leaves the internal name behind
                        // as a membership, and the save path then reads it back as the
                        // object's most-specific class.
                        java.util.List<String> internalTypes = o.directClassNames().stream()
                                .filter(wikidata.explore.extract.WikidataDynamicObject::isInternalClassName)
                                .toList();
                        for (String internal : internalTypes) {
                            o.removeClass(internal);
                            unstampedSubjects++;
                        }
                        java.util.List<String> internal = new java.util.ArrayList<>();
                        for (String k : o.dynamicFields().keySet()) {
                            if (k != null && k.startsWith("__")) {
                                internal.add(k);
                            }
                        }
                        for (String k : internal) {
                            o.remove(k);
                            strippedFields++;
                        }
                    }
                    if (unstampedSubjects > 0 || strippedFields > 0) {
                        genLog.message("Un-stamped " + unstampedSubjects
                                + " discovered subject(s), stripped " + strippedFields
                                + " internal statement field(s) — kept as referents, "
                                + "not served.\n");
                    }

                    // Stamp each statement instance's ENTITY-field referents with the
                    // (bare) class the field declares — e.g. Nomination.nominee ->
                    // Nominee, forWork -> ForWork, category -> Category. This resolves
                    // the reference (typeName reads the class instead of falling back,
                    // and it is no longer collapsed to a bare label) and readies the
                    // class to grow fields later. Runs AFTER the __subject_ un-stamp so
                    // a discovered subject that is also a referent gets its real class.
                    int stampedReferents =
                            wikidata.explore.transform.ReferentClassStamp.apply(
                                    project, reified);
                    if (stampedReferents > 0) {
                        genLog.message("Stamped " + stampedReferents
                                + " referent(s) with their declared class.\n");
                    }

                    // Field-defined composition: Person.name -> Name creates one Name
                    // carrying the Person QID. Name's own PIDs are loaded by the same
                    // referent-field loader immediately below.
                    List<WikidataDynamicObject> ownedRoots =
                            new ArrayList<>(shared.values());
                    ownedRoots.addAll(reified);
                    wikidata.explore.transform.OwnedComponents.Result owned =
                            wikidata.explore.transform.OwnedComponents.apply(
                                    project, ownedRoots, null, genLog);

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
                    List<WikidataDynamicObject> referentLoadRoots =
                            new ArrayList<>(shared.values());
                    referentLoadRoots.addAll(reified);
                    int loadedReferentFields =
                            wikidata.explore.transform.ReferentFieldLoad.apply(
                                    project, referentLoadRoots,
                                    entityApi,
                                    genLog);
                    if (loadedReferentFields > 0) {
                        genLog.message("Loaded " + loadedReferentFields
                                + " referent field value(s) from declared PIDs.\n");
                    }
                    wikidata.explore.transform.ReferentClassStamp.apply(
                            project, owned.components());
                    wikidata.explore.transform.Canonicalization.apply(
                            project, owned.components(), genLog);

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
                    owned.addTo(pool);

                    wikidata.explore.transform.ReferentKindClassifier.Result kindResult =
                            wikidata.explore.transform.ReferentKindClassifier.apply(
                                    project, pool,
                                    entityApi,
                                    genLog);
                    if (kindResult.classified() > 0 || kindResult.unknown() > 0
                            || kindResult.unavailable() > 0) {
                        genLog.message("Evidence-classified " + kindResult.classified()
                                + " role member(s); " + kindResult.unknown()
                                + " remain of unknown kind; " + kindResult.unavailable()
                                + " could not be checked.\n");
                    }

                    // Drop Wikimedia-internal non-entities (a disambiguation / duplicated
                    // / category page that slipped in as a wrong statement or qualifier
                    // value, e.g. a P805 ceremony pointing at the "1968 Academy Awards"
                    // DISAMBIGUATION page). The bad referent is removed and inbound
                    // references scrubbed, but the referencing record is KEPT (it merely
                    // loses that one wrong link). Before prune + vocab derivation so it
                    // neither counts nor pollutes a descriptive vocabulary.
                    java.util.Set<WikidataDynamicObject> disambig =
                            wikidata.explore.transform.DisambiguationPrune.apply(
                                    project, pool,
                                    entityApi,
                                    genLog);
                    if (!disambig.isEmpty()) {
                        pool.removeIf(disambig::contains);
                    }

                    // Drop orphans: labelled but untyped nodes referenced by nothing
                    // — subjects of nominations that were dropped (phantom / disallowed
                    // value), left floating in the pool. Not roots, not referenced, no
                    // fields; DeadStubPrune misses them because they resolved a label.
                    java.util.Set<WikidataDynamicObject> orphans =
                            wikidata.explore.transform.OrphanPrune.apply(pool, genLog);
                    if (!orphans.isEmpty()) {
                        pool.removeIf(orphans::contains);
                    }

                    // Field expectations (#96): coverage report + drop REQUIRED-missing
                    // records + keep EXPECTED-missing ones. Same pass the Remap runs.
                    wikidata.explore.transform.FieldExpectations.apply(
                            compiledProject, pool, genLog);

                    // Descriptive vocabularies (NomineeType, WorkGenre) derived from the
                    // now-final SERVED pool — so a vocab lists exactly the type tags that
                    // survive (a type whose only bearer was pruned must not linger). Uses
                    // the editable `project` so the built values ride out on the run's
                    // modelSnapshot and are folded back into the live model on accept.
                    wikidata.explore.transform.DescriptiveVocabularyBuild.apply(
                            project, pool, genLog);

                    // Data-quality audit (#99): now that only the finest atom is
                    // served, probe for inconsistencies (surviving witnessed phantoms)
                    // explicitly — they no longer surface as a weird member card.
                    wikidata.explore.transform.ConsistencyReport.check(
                            compiledProject, reified, genLog);

                    // ONE shared mapper over the SERVED POOL: each QID -> one typed
                    // instance, cross-refs resolve to those same instances. Mapping
                    // the pool (rather than the extractor's roots) makes the
                    // generation preview identical to load/serve — otherwise a qid
                    // stamped as a class root but stored untyped in the pool would
                    // show at generation yet vanish on reload.
                    List<Viewable> allInstances =
                            pipeline.materialize(runtime, pool);

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
                            .append(" distinct across ").append(classesRun)
                            .append(" class(es)");
                    step.summary(summary.toString());
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
