package wikidata.explore.transform;

import wikidata.explore.model.EntityBound;
import wikidata.WikidataIds;

import wikidata.WikidataSparqlClient;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.CompiledStatementSource;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MissingQualifierPolicy;
import wikidata.explore.model.QualifierDateMode;
import wikidata.explore.model.StatementFieldSemantics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a STATEMENT-reification class into a qualifier-load + reify, so the
 * reified record is configured ON THE MODEL with qualifier-sourced fields —
 * not in a Transform file.
 *
 * <p>For example, a {@code Nomination} class can reify {@code P1411}
 * statements of {@code OscarNominations}. Its value field receives the
 * statement's {@code ps:} value and qualifier fields receive values such as
 * year ({@code P585}), for-work ({@code P1686}) and nominee ({@code P2453}).
 * The implementation uses the existing {@link QualifierLoader} and
 * {@link TransformEngine}; the model is the authoritative configuration.</p>
 */
public final class ModelStatementReifications {

    private ModelStatementReifications() {
    }

    public record Reification(
            QualifierLoadConfig load,
            ReifyConstruct reify) {
    }

    public record AcquiredStatementClass(
            String className, String propertyPid, int subjects,
            int discoveredSubjects, int statements, List<String> qualifierPids) { }

    public record AcquisitionReport(List<AcquiredStatementClass> classes) {
        public AcquisitionReport {
            classes = classes == null ? List.of() : List.copyOf(classes);
        }
        public int statements() {
            return classes.stream().mapToInt(AcquiredStatementClass::statements).sum();
        }
        public int discoveredSubjects() {
            return classes.stream().mapToInt(
                    AcquiredStatementClass::discoveredSubjects).sum();
        }
        public String summary() {
            if (classes.isEmpty()) return "No statement classes configured";
            return classes.stream().map(c -> c.className() + ": " + c.subjects()
                    + " subject(s), " + c.statements() + " " + c.propertyPid()
                    + " statement(s)" + (c.discoveredSubjects() == 0 ? ""
                    : ", " + c.discoveredSubjects() + " discovered"))
                    .collect(java.util.stream.Collectors.joining("; "));
        }
    }

    /**
     * Compatibility boundary for callers that still hold the authored model.
     * Statement semantics are derived only from the validated compiled shape.
     */
    public static List<Reification> derive(GeneratedProjectModel project) {
        return project == null ? List.of()
                : derive(ProjectModelCompiler.compile(project));
    }

    /**
     * Compatibility boundary for an editor selection. Incomplete drafts are a UI
     * concern; executable recipes exist only after the complete project compiles.
     */
    public static Reification deriveOne(
            GeneratedClassModel statementClass, GeneratedProjectModel project) {
        if (statementClass == null || project == null) return null;
        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);
        return deriveOne(compiled.findClass(statementClass.className()).orElse(null), compiled);
    }

    /** Compiled-model overload of {@link #derive(GeneratedProjectModel)}. */
    public static List<Reification> derive(CompiledProjectModel project) {
        List<Reification> out = new ArrayList<>();
        if (project == null) {
            return out;
        }
        for (CompiledClass statementClass : project.classes()) {
            Reification reification = deriveOne(statementClass, project);
            if (reification != null) {
                out.add(reification);
            }
        }
        return out;
    }

    /**
     * Compiled-model overload — the same derivation reading a {@link CompiledClass}
     * whose inheritance, references, statement source and canonical identity are
     * already resolved (so no findClass / effective* / synthesized-view calls).
     * Kept parity-identical to the editable-model overload above; OscarReifyTest
     * asserts the two produce equal Reifications.
     */
    public static Reification deriveOne(
            CompiledClass statementClass,
            CompiledProjectModel project) {

        if (statementClass == null
                || project == null
                || !statementClass.statementClass()) {
            return null;
        }

        CompiledStatementSource statementSource =
                statementClass.statementSource();
        String statementPid = clean(statementSource.propertyPid());
        if (!WikidataIds.isPid(statementPid)) {
            return null;
        }

        // No source class => DISCOVER the subjects (POPULATION): the entities that
        // carry this statement into the value domain. They are stamped an internal
        // load type, never a served class. Requires a bounded value domain (guard).
        boolean discoverSubjects = !statementSource.hasSourceClass();
        CompiledClass sourceClassModel = discoverSubjects
                ? null
                : project.findClass(statementSource.sourceClassName()).orElse(null);
        if (!discoverSubjects && sourceClassModel == null) {
            return null;
        }
        String sourceClassName = discoverSubjects
                ? internalSubjectType(statementClass.className())
                : sourceClassModel.className();

        // The value role is resolved ONCE at compile (CompiledStatementSource) from
        // the explicit statement-value role; the reify reads it here. Fall back to the
        // local resolver only if a compiled model predates the field.
        String valueField = statementSource.valueField();
        if (valueField.isBlank()) {
            valueField = findValueField(statementClass, statementPid);
        }
        List<String> valueQids = valueQids(
                statementClass, sourceClassModel, project,
                statementPid, valueField);
        List<String> discoveryValueQids = new ArrayList<>(valueQids);
        if (discoverSubjects && !statementSource.hasValueSelection()) {
            List<String> seeds = targetClassSeeds(
                    statementClass, project, valueField);
            if (!seeds.isEmpty()
                    && explicitAllowedQids(statementClass, valueField).isEmpty()) {
                discoveryValueQids = seeds;
                valueQids = List.of();
            }
        }

        List<QualifierLoadConfig.Qualifier> qualifiers = new ArrayList<>();
        List<String> listQualifiers = new ArrayList<>();
        List<String> subjectParticipantFields = new ArrayList<>();

        for (CompiledField field : statementClass.ownFields()) {
            if (!runtimeStatementField(field)
                    || !field.source().qualifier()) {
                continue;
            }

            QualifierLoadConfig.Kind kind = kindFor(
                    field.type(), field.source().qualifierDateMode());
            boolean multi = field.collection();

            qualifiers.add(new QualifierLoadConfig.Qualifier(
                    clean(field.source().qualifierPid()),
                    field.name(),
                    kind,
                    multi,
                    qualifierLanguage(kind, field.source().valueLanguage())));

            if (kind == QualifierLoadConfig.Kind.ENTITY && multi) {
                listQualifiers.add(field.name());
                if (field.source().productionKind()
                        == FieldProductionKind.STATEMENT_PARTICIPANTS) {
                    subjectParticipantFields.add(field.name());
                }
            }
        }
        String primaryListField = canonicalListMarker(
                statementClass.canonical().primaryListField(), listQualifiers);

        List<ReifyConstruct.Role> roles =
                fallbackRoles(statementClass, valueField);
        List<String> dedup =
                canonicalKey(statementClass.canonical().keyFields());

        // The object's type filter comes from the authored objectBound, falling back to
        // the class's own sourceQid for models saved before the bound existed. The
        // fallback is a READ of old data, not a second place to write one.
        EntityBound authoredObject = statementSource.objectBound();
        String valueTypeQid =
                authoredObject.kind() == EntityBound.Kind.RELATION
                        && "P31".equalsIgnoreCase(authoredObject.relationPid())
                        && !authoredObject.qids().isEmpty()
                        ? authoredObject.qids().get(0)
                        : clean(statementClass.sourceMapping().sourceQid());
        if (authoredObject.kind() == EntityBound.Kind.EXPLICIT) {
            valueQids = new ArrayList<>(authoredObject.qids());
            discoveryValueQids = new ArrayList<>(authoredObject.qids());
        }

        // A referenced VOCABULARY Selection IS the value domain (production →
        // Selection): its values/type override the class-derived filter. Blank
        // valueSelectionName keeps the classic behavior.
        if (statementSource.hasValueSelection()) {
            wikidata.explore.model.Selection sel =
                    project.findSelection(statementSource.valueSelectionName())
                           .orElse(null);
            if (sel instanceof wikidata.explore.model.VocabularySelection vs) {
                if (!vs.valueQids().isEmpty()) {
                    valueQids = new ArrayList<>(vs.valueQids());
                    discoveryValueQids = new ArrayList<>(vs.valueQids());
                }
                if (vs.hasValueType()) {
                    valueTypeQid = vs.valueTypeQid();
                }
            }
        }

        // The one place two authored inputs become one bound. Compilation is where
        // that belongs: the loader used to make this choice at query time and without
        // saying so, which is why a type filter beside an explicit set did nothing.
        // An explicit set IS the bound; a type is a way of producing one. Configuring
        // both is a model error for validation to report, not something to rank here.
        EntityBound objectBound = !valueQids.isEmpty()
                ? EntityBound.explicit(valueQids)
                : EntityBound.instancesOf(valueTypeQid);

        QualifierLoadConfig load = new QualifierLoadConfig(
                sourceClassName,
                statementPid,
                "__" + statementClass.className(),
                statementClass.className(),
                valueField,
                objectBound,
                statementSource.subjectBound(),
                qualifiers,
                discoveryValueQids,
                discoverSubjects,
                statementSource.valueSelectionName());

        ReifyConstruct reify = new ReifyConstruct(
                sourceClassName,
                "__" + statementClass.className(),
                statementClass.className(),
                statementSource.subjectField().isBlank()
                        ? "source" : statementSource.subjectField(),
                "value",
                true,
                roles,
                dedup,
                primaryListField,
                subjectParticipantFields,
                statementClass.canonical().duplicatePolicy());

        return new Reification(load, reify);
    }

    /** Internal load type stamped on discovered (source-class-less) subjects — never
     *  a served class; the reify sources on it and it is un-stamped before serving. */
    private static String internalSubjectType(String statementClassName) {
        return "__subject_" + clean(statementClassName);
    }

    private static String findValueField(
            CompiledClass statementClass,
            String statementPid) {

        // The value role is explicit: the non-qualifier field on the statement PID.
        // No first-field guess (mirrors StatementFieldSemantics.statementValueFieldName
        // on the editable model) — a missing value field is a validation error.
        for (CompiledField field : statementClass.ownFields()) {
            if (!runtimeStatementField(field)
                    || field.source().qualifier()) {
                continue;
            }
            if (statementPid.equals(clean(field.source().propertyPid()))) {
                return field.name();
            }
        }

        return "value";
    }

    private static List<String> valueQids(
            CompiledClass statementClass,
            CompiledClass sourceClass,
            CompiledProjectModel project,
            String statementPid,
            String valueField) {

        LinkedHashSet<String> values = new LinkedHashSet<>();

        CompiledField valueModel = null;
        for (CompiledField field : statementClass.ownFields()) {
            if (runtimeStatementField(field)
                    && valueField.equals(field.name())) {
                valueModel = field;
                break;
            }
        }

        if (valueModel != null) {
            for (String qid : valueModel.source().allowedQids()) {
                String cleanQid = clean(qid);
                if (WikidataIds.isQid(cleanQid)) {
                    values.add(cleanQid);
                }
            }
            if (values.isEmpty() && project != null
                    && valueModel.type() == FieldType.ENTITY) {
                project.findClass(valueModel.entityClassName())
                        .ifPresent(target -> addQids(values, target.seedQids()));
            }
        }

        if (values.isEmpty() && sourceClass != null
                && statementPid.equals(
                clean(sourceClass.sourceMapping().propertyPid()))) {

            String sourceQid =
                    clean(sourceClass.sourceMapping().sourceQid());
            if (WikidataIds.isQid(sourceQid)) {
                values.add(sourceQid);
            }

            for (String qid
                    : sourceClass.sourceMapping().additionalTypeQids()) {
                String cleanQid = clean(qid);
                if (WikidataIds.isQid(cleanQid)) {
                    values.add(cleanQid);
                }
            }
        }

        return new ArrayList<>(values);
    }

    private static List<String> explicitAllowedQids(
            CompiledClass statementClass, String valueField) {
        if (statementClass == null) return List.of();
        CompiledField field = statementClass.ownFields().stream()
                .filter(ModelStatementReifications::runtimeStatementField)
                .filter(candidate -> valueField.equals(candidate.name()))
                .findFirst().orElse(null);
        return field == null ? List.of() : field.source().allowedQids().stream()
                .map(ModelStatementReifications::clean)
                .filter(WikidataIds::isQid).distinct().toList();
    }

    private static List<String> targetClassSeeds(
            CompiledClass statementClass, CompiledProjectModel project,
            String valueField) {
        if (statementClass == null || project == null) return List.of();
        CompiledField field = statementClass.ownFields().stream()
                .filter(ModelStatementReifications::runtimeStatementField)
                .filter(candidate -> valueField.equals(candidate.name()))
                .filter(candidate -> candidate.type() == FieldType.ENTITY)
                .findFirst().orElse(null);
        CompiledClass target = field == null ? null
                : project.findClass(field.entityClassName()).orElse(null);
        if (target == null) return List.of();
        LinkedHashSet<String> seeds = new LinkedHashSet<>();
        addQids(seeds, target.seedQids());
        return new ArrayList<>(seeds);
    }

    private static List<ReifyConstruct.Role> fallbackRoles(
            CompiledClass statementClass,
            String valueField) {

        List<ReifyConstruct.Role> roles = new ArrayList<>();

        for (CompiledField field : statementClass.ownFields()) {
            if (field.name().equals(statementClass.statementSource().subjectField())) {
                roles.add(new ReifyConstruct.Role(
                        field.name(), field.name(), true,
                        wikidata.explore.model.RoleKind.IDENTITY));
                continue;
            }
            if (!supportsMissingQualifierPolicy(statementClass, field)) {
                continue;
            }

            MissingQualifierPolicy policy =
                    StatementFieldSemantics.effectiveMissingQualifierPolicy(
                            field.source().missingQualifierPolicy());

            switch (policy) {
                case STATEMENT_SUBJECT ->
                        roles.add(new ReifyConstruct.Role(
                                field.name(), field.name(), true,
                                field.source().roleKind()));
                case STATEMENT_VALUE ->
                        roles.add(new ReifyConstruct.Role(
                                field.name(), valueField, false,
                                wikidata.explore.model.RoleKind.VALUE));
                case MISSING -> {
                }
            }
        }

        return roles;
    }

    /**
     * Which collection field marks the CANONICAL copy of a reified statement (#92).
     *
     * <p>The declaration on the class wins. It has to: with two multi-valued entity
     * qualifiers the old rule — "the first one" — answered by field order, so reordering
     * fields silently changed which copies were kept and which were dropped as
     * denormalized duplicates.
     *
     * <p>A blank declaration keeps that structural inference, because every model saved
     * before the declaration existed depends on it, and it is right whenever there is
     * exactly one candidate. A declaration naming a field that is not a multi-valued
     * entity qualifier cannot mark anything, so it is ignored rather than silently
     * disabling canonicalization; {@code GeneratedProjectModelValidator} is where a
     * wrong name is reported.
     */
    static String canonicalListMarker(String declared, List<String> listQualifiers) {
        String name = clean(declared);
        if (!name.isEmpty() && listQualifiers.contains(name)) {
            return name;
        }
        return listQualifiers.isEmpty() ? "" : listQualifiers.get(0);
    }

    /** Compiled fields are never name fields (the compiler drops those). */
    private static boolean runtimeStatementField(CompiledField field) {
        return field != null
                && (field.source().productionKind() == FieldProductionKind.AUTO
                    || field.source().productionKind()
                        == FieldProductionKind.STATEMENT_PARTICIPANTS);
    }

    private static boolean supportsMissingQualifierPolicy(
            CompiledClass owner, CompiledField field) {
        return owner.statementClass()
                && runtimeStatementField(field)
                && field.source().qualifier()
                && field.type() == FieldType.ENTITY
                && !field.collection();
    }

    private static void addQids(
            Set<String> target, Collection<String> candidates) {
        if (target == null || candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            String qid = clean(candidate);
            if (WikidataIds.isQid(qid)) {
                target.add(qid);
            }
        }
    }

    private static List<String> canonicalKey(List<String> storedKeyFields) {
        LinkedHashSet<String> key = new LinkedHashSet<>();
        if (storedKeyFields != null) {
            for (String fieldName : storedKeyFields) {
                String cleanName = clean(fieldName);
                if (!cleanName.isBlank()) {
                    key.add(cleanName);
                }
            }
        }
        return new ArrayList<>(key);
    }

    /**
     * A human-readable, multi-line recipe for one reification — the same
     * structure that generation executes.
     */
    public static String describe(Reification reification) {
        if (reification == null) {
            return "(not a reifying class)";
        }

        QualifierLoadConfig load = reification.load();
        ReifyConstruct reify = reification.reify();

        List<String> subjectFallbacks = new ArrayList<>();
        List<String> valueFallbacks = new ArrayList<>();

        for (ReifyConstruct.Role role : reify.roles()) {
            if (role.fallbackToSource()) {
                subjectFallbacks.add(role.field());
            } else if (load.valueField().equals(role.from())
                    && !role.field().equals(role.from())) {
                valueFallbacks.add(role.field());
            }
        }

        StringBuilder qualifierText = new StringBuilder();
        for (QualifierLoadConfig.Qualifier qualifier
                : load.qualifiers()) {
            if (qualifierText.length() > 0) {
                qualifierText.append(", ");
            }
            qualifierText.append(qualifier.fieldName())
                         .append("←")
                         .append(qualifier.pid());

            if (qualifier.multi()) {
                qualifierText.append("(list)");
            }
            if (qualifier.kind() == QualifierLoadConfig.Kind.DATE) {
                qualifierText.append("(date)");
            } else if (qualifier.kind() == QualifierLoadConfig.Kind.YEAR) {
                qualifierText.append("(year)");
            }
        }

        return "Reify " + load.propertyPid()
                + " of " + load.entityType()
                + " → " + reify.targetType()
                + "\n value: " + load.valueField()
                + "\n canonical list: "
                + (reify.canonicalizesByList()
                ? reify.primaryListField()
                : "—")
                + "\n subject-participant fields: "
                + display(reify.subjectParticipantFields())
                + "\n subject-fallback fields: "
                + display(subjectFallbacks)
                + "\n statement-value-fallback fields: "
                + display(valueFallbacks)
                + "\n canonical key: "
                + display(reify.dedupBy(), " + ")
                + "\n duplicate policy: "
                + reify.duplicatePolicy()
                + "\n qualifiers: "
                + (qualifierText.length() == 0
                ? "—"
                : qualifierText);
    }

    // ---- Compiled-model pipeline ----

    /** Compiled-model overload: enrich then reify. */
    public static List<WikidataDynamicObject> apply(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {

        enrich(project, pool, client, log);
        return reify(project, pool, log);
    }

    /** Compiled-model overload of {@link #enrich(GeneratedProjectModel, List,
     *  WikidataSparqlClient, GenerationLog)}. */
    public static void enrich(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {

        enrich(project, pool, client, log, null);
    }

    /** Compiled enrichment sharing the generation run's action API and fact store. */
    public static void enrich(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi) {

        enrich(project, pool, client, log, entityApi, false);
    }

    public static void enrich(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi,
            boolean deferLabels) {
        enrichWithReport(project, pool, client, log, entityApi, deferLabels);
    }

    /** The same compiled acquisition path, returning counts for live progress. */
    public static AcquisitionReport enrichWithReport(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi,
            boolean deferLabels) {
        return enrichWithReport(project, pool, client, log, entityApi, deferLabels, null);
    }

    /** Acquisition with the generation-wide prospective demand plan. */
    public static AcquisitionReport enrichWithReport(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log,
            wikidata.api.WikidataApiClient entityApi,
            boolean deferLabels,
            wikidata.api.FactDemandPlan demandPlan) {
        if (client == null) return new AcquisitionReport(List.of());
        QualifierLoader loader = new QualifierLoader().api(entityApi)
                .deferLabels(deferLabels);
        List<AcquiredStatementClass> acquired = new ArrayList<>();
        for (Reification reification : derive(project)) {
            loader.factDemands(StatementFactDemands.compile(
                    project, reification, demandPlan));
            QualifierLoadConfig cfg = reification.load();
            int before = pool.size();
            List<WikidataDynamicObject> statements =
                    loader.enrich(pool, cfg, client, log);
            int discovered = Math.max(0, pool.size() - before);
            int subjects = 0;
            for (WikidataDynamicObject object : pool) {
                if (object != null && cfg.entityType().equals(object.typeName())) subjects++;
            }
            List<String> qualifierPids = cfg.qualifiers() == null ? List.of()
                    : cfg.qualifiers().stream().map(QualifierLoadConfig.Qualifier::pid)
                    .filter(WikidataIds::isPid).distinct().toList();
            acquired.add(new AcquiredStatementClass(cfg.statementType(),
                    cfg.propertyPid(), subjects, discovered, statements.size(),
                    qualifierPids));
        }
        return new AcquisitionReport(acquired);
    }

    /** Compiled-model overload of {@link #reify(GeneratedProjectModel, List,
     *  GenerationLog)}. */
    public static List<WikidataDynamicObject> reify(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            GenerationLog log) {

        return reify(project, pool, log, null);
    }

    /** Compiled-model overload collecting canonicalization-demoted duplicates. */
    public static List<WikidataDynamicObject> reify(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            GenerationLog log,
            Set<WikidataDynamicObject> demotedOut) {

        return reify(project, pool, log, demotedOut, null);
    }

    /**
     * As above, also collecting the per-atom self-reference decisions.
     *
     * <p>Each finding already carries WHY one record was dropped and WHICH record
     * witnessed it. That is the sentence a reader wants — "this nomination was dropped
     * because that one references its subject through a reference role on the same
     * slot" — and it was written to the log and nowhere else, so the run could not show
     * it beside the records it names.
     */
    public static List<WikidataDynamicObject> reify(
            CompiledProjectModel project,
            List<WikidataDynamicObject> pool,
            GenerationLog log,
            Set<WikidataDynamicObject> demotedOut,
            List<TransformEngine.SelfRefFinding> findingsOut) {

        List<WikidataDynamicObject> created = new ArrayList<>();
        TransformEngine engine = new TransformEngine();
        List<TransformEngine.SelfRefFinding> findings = new ArrayList<>();

        for (Reification reification : derive(project)) {
            List<WikidataDynamicObject> records =
                    engine.applyReify(pool, reification.reify(),
                            reification.load().valueField());
            created.addAll(records);
            findings.addAll(engine.selfReferenceFindings());
            if (demotedOut != null) {
                demotedOut.addAll(engine.demoted());
            }

            if (log != null) {
                logReify(log, reification, records.size(),
                        valueFilterGaps(reification, project));
            }
        }

        logSelfReferenceFindings(log, findings);
        if (findingsOut != null) {
            findingsOut.addAll(findings);
        }

        return created;
    }

    private static void logSelfReferenceFindings(
            GenerationLog log, List<TransformEngine.SelfRefFinding> findings) {
        if (log == null || findings.isEmpty()) return;
        long dropped = findings.stream().filter(f -> f.decision()
                == TransformEngine.SelfRefDecision.DROPPED).count();
        log.message("Self-referential reify atoms (#99): " + dropped
                + " dropped (witnessed phantom), " + (findings.size() - dropped)
                + " kept (no witness). Dropped:\n");
        for (TransformEngine.SelfRefFinding finding : findings) {
            if (finding.decision() == TransformEngine.SelfRefDecision.DROPPED) {
                log.message("  " + finding + "\n");
            }
        }
    }

    private static void logReify(GenerationLog log, Reification reification,
            int recordCount, List<String> gaps) {
        log.message(describe(reification) + "\n → " + recordCount + " records\n");
        if (!gaps.isEmpty()) {
            log.message(" ⚠ consistency: the value filter misses " + gaps.size()
                    + " of the source class's membership target(s) — "
                    + gaps.stream().limit(6).collect(
                            java.util.stream.Collectors.joining(", "))
                    + (gaps.size() > 6 ? ", …" : "")
                    + " — statements to those WON'T load. Add them to the value "
                    + "field's allowed values (or align the class membership).\n");
        }
    }

    /** Finds source membership targets excluded by the statement-value filter. */
    public static List<String> valueFilterGaps(
            Reification reification,
            CompiledProjectModel project) {

        List<String> missed = new ArrayList<>();
        if (reification == null || project == null) {
            return missed;
        }

        QualifierLoadConfig config = reification.load();
        CompiledClass sourceClass =
                project.findClass(config.entityType()).orElse(null);

        if (sourceClass == null
                || !config.objectBound().bounded()
                || !config.propertyPid().equals(
                clean(sourceClass.sourceMapping().propertyPid()))) {
            return missed;
        }

        Set<String> filter = new LinkedHashSet<>(config.objectBound().qids());

        String sourceQid = clean(sourceClass.sourceMapping().sourceQid());
        if (WikidataIds.isQid(sourceQid) && !filter.contains(sourceQid)) {
            missed.add(sourceQid);
        }

        for (String qid : sourceClass.sourceMapping().additionalTypeQids()) {
            String cleanQid = clean(qid);
            if (WikidataIds.isQid(cleanQid) && !filter.contains(cleanQid)) {
                missed.add(cleanQid);
            }
        }

        return missed;
    }

    private static QualifierLoadConfig.Kind kindFor(
            FieldType type,
            QualifierDateMode dateMode) {

        if (type == FieldType.ENTITY) {
            return QualifierLoadConfig.Kind.ENTITY;
        }
        if (type == FieldType.DATE) {
            return dateMode == QualifierDateMode.DATE
                    ? QualifierLoadConfig.Kind.DATE
                    : QualifierLoadConfig.Kind.YEAR;
        }
        return QualifierLoadConfig.Kind.STRING;
    }

    private static String qualifierLanguage(
            QualifierLoadConfig.Kind kind, String configured) {
        return kind == QualifierLoadConfig.Kind.STRING
                ? wikidata.WikidataLanguageDefaults.literalCode(configured)
                : "";
    }

    private static String display(List<String> values) {
        return display(values, ", ");
    }

    private static String display(
            List<String> values,
            String separator) {

        return values == null || values.isEmpty()
                ? "—"
                : String.join(separator, values);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }

        String result = value.trim();
        int slash = result.lastIndexOf('/');
        return slash >= 0
                ? result.substring(slash + 1)
                : result;
    }
}
