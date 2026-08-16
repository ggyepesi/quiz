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
    private final process.ProcessWorkflowPipeline pipelineProgress;

    public GenerateDomainQuery(GeneratedProjectModel project) {
        this(project, null);
    }

    public GenerateDomainQuery(
            GeneratedProjectModel project,
            process.ProcessWorkflowPipeline pipelineProgress) {
        this.project = project;
        this.pipelineProgress = pipelineProgress;
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
                    // One structured log for every long run (see Enrich): each
                    // request becomes its own entry, an in-flight one included.
                    GenerationLog genLog = StepGenerationLog.of(context, step);
                    phase(wikidata.explore.generation.GenerateDomainPipeline.EXTRACT,
                            project.classes().size() + " configured classes");

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
                                genLog, shared, context.cancellation());
                        genLog.message("  -> " + roots.size() + " "
                                + cls.className() + "\n");

                        if (rootPlan == null) {
                            rootPlan = plan;
                        }
                        classesRun++;
                        progress(wikidata.explore.generation.GenerateDomainPipeline.EXTRACT,
                                classesRun + " root class query(ies) completed");
                    }

                    phase(wikidata.explore.generation.GenerateDomainPipeline.REIFY,
                            "Loading statement classes and derived transforms");
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

                    // Build the initial served pool before kind classification. Owned
                    // components deliberately come later: Person.name can only be
                    // produced after a Nominee has actually been classified as Person.
                    List<WikidataDynamicObject> pool = new ArrayList<>();
                    for (WikidataDynamicObject o : shared.values()) {
                        if (!demoted.contains(o) && !deadStubs.contains(o)) {
                            pool.add(o);
                        }
                    }
                    pool.addAll(reified);

                    phase(wikidata.explore.generation.GenerateDomainPipeline.ROLE_EVIDENCE,
                            "Loading fields declared on referenced roles");
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
                    wikidata.explore.transform.ReferentFieldLoad.Result firstReferentLoad =
                            wikidata.explore.transform.ReferentFieldLoad.load(
                                    project, referentLoadRoots,
                                    entityApi,
                                    genLog, java.util.List.of());
                    phase(wikidata.explore.generation.GenerateDomainPipeline.CLASSIFY,
                            "Applying stored and remote kind evidence");
                    wikidata.explore.transform.SnapshotEntityKindClassifier.Result storedKinds =
                            wikidata.explore.transform.SnapshotEntityKindClassifier.apply(
                                    project, pool, pool, genLog);
                    // A model need not expose every kind-evidence PID as a field, and a
                    // failed first-pass declaration must not be mistaken for evidence
                    // absence. Fetch only candidates for which no stored evidence was
                    // available; candidates already classified above are excluded.
                    wikidata.explore.transform.ReferentKindClassifier.Result remoteKinds =
                            wikidata.explore.transform.ReferentKindClassifier.apply(
                                    project, pool,
                                    entityApi,
                                    genLog, storedKinds.withoutStoredEvidenceQids());
                    int classifiedKinds = storedKinds.classified() + remoteKinds.classified();
                    int unknownKinds = Math.max(0,
                            storedKinds.unknown() - remoteKinds.classified());
                    if (classifiedKinds > 0 || storedKinds.unknown() > 0
                            || remoteKinds.unavailable() > 0) {
                        genLog.message("Evidence-classified " + classifiedKinds
                                + " role member(s); " + unknownKinds
                                + " remain of unknown kind; "
                                + remoteKinds.unavailable()
                                + " could not be checked.\n");
                    }

                    phase(wikidata.explore.generation.GenerateDomainPipeline.OWNED,
                            classifiedKinds + " kind assignment(s) available");
                    // Field-defined composition now sees the settled kind memberships:
                    // Person.name -> Name creates one Name carrying the Person QID.
                    wikidata.explore.transform.OwnedComponents.Result owned =
                            wikidata.explore.transform.OwnedComponents.apply(
                                    project, pool, null, genLog);
                    owned.addTo(pool);
                    wikidata.explore.transform.ReferentClassStamp.apply(
                            project, owned.components());

                    phase(wikidata.explore.generation.GenerateDomainPipeline.KIND_OWNED_FIELDS,
                            owned.created() + " owned component(s) created");
                    // Second acquisition pass fills fields belonging to kinds and owned
                    // components discovered above (Person.dateOfBirth, Name.*). Exact
                    // declaration coverage from pass one prevents duplicate requests.
                    wikidata.explore.transform.ReferentFieldLoad.Result secondReferentLoad =
                            wikidata.explore.transform.ReferentFieldLoad.load(
                                    project, pool, entityApi, genLog,
                                    firstReferentLoad.completed());
                    java.util.Map<String, wikidata.explore.extract.LoadedDeclaration>
                            completedReferentLoads = new java.util.LinkedHashMap<>();
                    firstReferentLoad.completed().forEach(d ->
                            completedReferentLoads.put(d.key(), d));
                    secondReferentLoad.completed().forEach(d ->
                            completedReferentLoads.put(d.key(), d));

                    // A failed declaration can succeed in this second pass (the log
                    // demonstrates exactly that for Nominee.type). Kind assignment and
                    // owned composition therefore have to converge AFTER the retry,
                    // rather than freezing the first pass's "unknown" conclusion.
                    wikidata.explore.transform.SnapshotEntityKindClassifier.Result lateKinds =
                            wikidata.explore.transform.SnapshotEntityKindClassifier.apply(
                                    project, pool, pool, genLog);
                    wikidata.explore.transform.OwnedComponents.Result lateOwned =
                            wikidata.explore.transform.OwnedComponents.apply(
                                    project, pool, null, genLog);
                    lateOwned.addTo(pool);
                    wikidata.explore.transform.ReferentClassStamp.apply(
                            project, lateOwned.components());

                    // Newly classified owners may have introduced owned classes only
                    // now. One final load fills those declarations; prior exact coverage
                    // keeps the pass proportional to the newly reachable work.
                    wikidata.explore.transform.ReferentFieldLoad.Result finalReferentLoad =
                            wikidata.explore.transform.ReferentFieldLoad.load(
                                    project, pool, entityApi, genLog,
                                    completedReferentLoads.values());
                    finalReferentLoad.completed().forEach(d ->
                            completedReferentLoads.put(d.key(), d));

                    int loadedReferentFields = firstReferentLoad.loaded()
                            + secondReferentLoad.loaded() + finalReferentLoad.loaded();
                    if (loadedReferentFields > 0) {
                        genLog.message("Loaded " + loadedReferentFields
                                + " referent/owned field value(s) from declared PIDs.\n");
                    }

                    phase(wikidata.explore.generation.GenerateDomainPipeline.MATERIALIZE,
                            loadedReferentFields + " referent/owned field value(s) loaded");
                    // Canonical names depend on the final class and field set, so this
                    // is the authoritative pass. Earlier canonicalization is harmless
                    // preparation for reification, but must not be the last word.
                    wikidata.explore.transform.Canonicalization.apply(
                            project, pool, genLog);

                    // Acquisition can create new thin references and owned components;
                    // prune only after the last field load so "not fetched yet" is never
                    // confused with "dead". The earlier raw-pool pass remains a cheap
                    // guard for ghosts created during extraction/reification.
                    java.util.Set<WikidataDynamicObject> finalDeadStubs =
                            wikidata.explore.transform.DeadStubPrune.apply(pool, genLog);
                    if (!finalDeadStubs.isEmpty()) {
                        pool.removeIf(finalDeadStubs::contains);
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
                    java.util.List<wikidata.explore.extract.LoadedDeclaration> failedLoads =
                            new java.util.ArrayList<>(firstReferentLoad.failed());
                    failedLoads.addAll(secondReferentLoad.failed());
                    failedLoads.addAll(finalReferentLoad.failed());
                    // Failure events are historical; quality describes the final state.
                    // If a later pass completed the same declaration for every failed
                    // QID, it repaired the failure and must not leave the run PARTIAL.
                    failedLoads.removeIf(failed -> {
                        wikidata.explore.extract.LoadedDeclaration completed =
                                completedReferentLoads.get(failed.key());
                        return completed != null && completed.coveredQids()
                                .containsAll(failed.coveredQids());
                    });
                    if (!failedLoads.isEmpty()) {
                        String failedNames = failedLoads.stream().limit(3)
                                .map(d -> d.className() + "." + d.fieldName()
                                        + " (" + d.propertyPid() + ", "
                                        + d.coveredQids().size() + " QIDs)")
                                .collect(java.util.stream.Collectors.joining(", "));
                        if (failedLoads.size() > 3) {
                            failedNames += " … and " + (failedLoads.size() - 3) + " more";
                        }
                        qualityWarnings.add("Unresolved field load: " + failedNames);
                        failedLoads.forEach(d -> unavailableQids.addAll(d.coveredQids()));
                        boolean roleFailure = failedLoads.stream().anyMatch(d -> {
                            GeneratedClassModel c = project.findClass(d.className());
                            return c != null && wikidata.explore.model.MembershipPattern.of(
                                    c, project) == wikidata.explore.model.MembershipPattern.REFERENCED;
                        });
                        if (roleFailure) partialPhase(
                                wikidata.explore.generation.GenerateDomainPipeline.ROLE_EVIDENCE,
                                failedNames);
                        boolean laterFailure = failedLoads.stream().anyMatch(d -> {
                            GeneratedClassModel c = project.findClass(d.className());
                            if (c == null) return false;
                            var pattern = wikidata.explore.model.MembershipPattern.of(c, project);
                            return pattern == wikidata.explore.model.MembershipPattern.EVIDENCE_KIND
                                    || pattern == wikidata.explore.model.MembershipPattern.OWNED_COMPONENT;
                        });
                        if (laterFailure || !roleFailure) partialPhase(
                                wikidata.explore.generation.GenerateDomainPipeline.KIND_OWNED_FIELDS,
                                failedNames);
                    }
                    int unresolvedKindEvidence = Math.max(0,
                            remoteKinds.unavailable() - lateKinds.classified());
                    if (unresolvedKindEvidence > 0) {
                        qualityWarnings.add("Entity-kind evidence was unavailable for "
                                + unresolvedKindEvidence + " role member(s)");
                        unavailableQids.addAll(remoteKinds.unavailableQids());
                        partialPhase(wikidata.explore.generation.GenerateDomainPipeline.CLASSIFY,
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
                                            java.util.List.copyOf(unavailableQids)));
                });
    }

    // A running sub-query node under {@code step}, finished via the handle.
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

    @Override public int rowCount(GenerationRun r) { return r == null ? 0 : r.size(); }
    @Override public String summary(GenerationRun r) { return rowCount(r) + " objects"; }
}
