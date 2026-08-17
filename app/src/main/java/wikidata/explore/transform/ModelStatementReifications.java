package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.WikidataSparqlClient;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.CompiledStatementSource;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MissingQualifierPolicy;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.StatementFieldSemantics;

import java.util.ArrayList;
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

    public static List<Reification> derive(
            GeneratedProjectModel project) {

        List<Reification> out = new ArrayList<>();
        if (project == null) {
            return out;
        }

        for (GeneratedClassModel statementClass : project.classes()) {
            Reification reification =
                    deriveOne(statementClass, project);
            if (reification != null) {
                out.add(reification);
            }
        }
        return out;
    }

    /**
     * Derives the single {@link Reification} for one statement class, or
     * {@code null} when the class is not a statement class or its source cannot
     * be resolved.
     *
     * <p>This method is also used by the workbench to display the exact recipe
     * that generation will run. Keeping display and execution on the same
     * derivation prevents their semantics from drifting.</p>
     */
    public static Reification deriveOne(
            GeneratedClassModel statementClass,
            GeneratedProjectModel project) {

        if (statementClass == null
                || project == null
                || !statementClass.reifiesStatements()) {
            return null;
        }

        StatementClassSource statementSource =
                statementClass.statementSource();
        if (statementSource == null) {
            return null;
        }

        // Resolve to the actual class name case-insensitively, so the
        // qualifier-load entityType matches the type stamped on pool objects.
        String statementPid = clean(statementSource.propertyPid());
        if (!WikidataIds.isPid(statementPid)) {
            return null;
        }

        // No source class => DISCOVER the subjects (POPULATION). Mirrors the
        // compiled path.
        boolean discoverSubjects = !statementSource.hasSourceClass();
        GeneratedClassModel sourceClassModel = discoverSubjects
                ? null
                : project.findClass(statementSource.sourceClassName());
        if (!discoverSubjects && sourceClassModel == null) {
            return null;
        }
        String sourceClassName = discoverSubjects
                ? internalSubjectType(statementClass.className())
                : sourceClassModel.className();
        String valueField = findValueField(
                statementClass, statementPid);

        List<String> valueQids = sourceClassModel == null
                ? new ArrayList<>()
                : valueQids(
                        statementClass,
                        sourceClassModel,
                        statementPid,
                        valueField);

        List<QualifierLoadConfig.Qualifier> qualifiers =
                new ArrayList<>();
        String primaryListField = "";

        for (GeneratedFieldModel field : statementClass.fields()) {
            if (!StatementFieldSemantics.isRuntimeStatementField(field)
                    || !field.mapping().isQualifier()) {
                continue;
            }

            QualifierLoadConfig.Kind kind = kindFor(field.type());
            boolean multi = field.cardinality() != null
                    && field.cardinality().isCollection();

            qualifiers.add(new QualifierLoadConfig.Qualifier(
                    clean(field.mapping().qualifierPid()),
                    field.name(),
                    kind,
                    multi));

            if (kind == QualifierLoadConfig.Kind.ENTITY
                    && multi
                    && primaryListField.isEmpty()) {
                // A multi ENTITY qualifier (the shared-award nominee list) is
                // the canonical marker: the record that has it is the complete
                // statement copy. It is not itself a fallback role.
                primaryListField = field.name();
            }
        }

        List<ReifyConstruct.Role> roles =
                fallbackRoles(statementClass, valueField);
        List<String> dedup =
                canonicalKey(statementClass.canonical().keyFields());

        // The class sourceQid, when present, is a P31 filter for the statement
        // value (for example Q19020 for Academy Award categories).
        String valueTypeQid =
                clean(statementClass.instanceMapping().sourceQid());

        // A referenced VOCABULARY Selection IS the value domain (production →
        // Selection): its values/type override the class-derived filter. Blank
        // valueSelectionName keeps the classic behavior. Mirrors the compiled path.
        if (statementSource.hasValueSelection()) {
            wikidata.explore.model.Selection sel =
                    project.findSelection(statementSource.valueSelectionName());
            if (sel instanceof wikidata.explore.model.VocabularySelection vs) {
                if (!vs.valueQids().isEmpty()) {
                    valueQids = new ArrayList<>(vs.valueQids());
                }
                if (vs.hasValueType()) {
                    valueTypeQid = vs.valueTypeQid();
                }
            }
        }

        QualifierLoadConfig load = new QualifierLoadConfig(
                sourceClassName,
                statementPid,
                "__" + statementClass.className(),
                statementClass.className(),
                valueField,
                WikidataIds.isQid(valueTypeQid)
                        ? valueTypeQid
                        : "",
                qualifiers,
                valueQids,
                discoverSubjects,
                statementSource.valueSelectionName());

        ReifyConstruct reify = new ReifyConstruct(
                sourceClassName,
                "__" + statementClass.className(),
                statementClass.className(),
                "source",
                "value",
                true,
                roles,
                dedup,
                primaryListField);

        return new Reification(load, reify);
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
        List<String> valueQids = sourceClassModel == null
                ? new ArrayList<>()
                : valueQids(statementClass, sourceClassModel, statementPid, valueField);

        List<QualifierLoadConfig.Qualifier> qualifiers = new ArrayList<>();
        String primaryListField = "";

        for (CompiledField field : statementClass.ownFields()) {
            if (!runtimeStatementField(field)
                    || !field.source().qualifier()) {
                continue;
            }

            QualifierLoadConfig.Kind kind = kindFor(field.type());
            boolean multi = field.collection();

            qualifiers.add(new QualifierLoadConfig.Qualifier(
                    clean(field.source().qualifierPid()),
                    field.name(),
                    kind,
                    multi));

            if (kind == QualifierLoadConfig.Kind.ENTITY
                    && multi
                    && primaryListField.isEmpty()) {
                primaryListField = field.name();
            }
        }

        List<ReifyConstruct.Role> roles =
                fallbackRoles(statementClass, valueField);
        List<String> dedup =
                canonicalKey(statementClass.canonical().keyFields());

        String valueTypeQid =
                clean(statementClass.sourceMapping().sourceQid());

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
                }
                if (vs.hasValueType()) {
                    valueTypeQid = vs.valueTypeQid();
                }
            }
        }

        QualifierLoadConfig load = new QualifierLoadConfig(
                sourceClassName,
                statementPid,
                "__" + statementClass.className(),
                statementClass.className(),
                valueField,
                WikidataIds.isQid(valueTypeQid) ? valueTypeQid : "",
                qualifiers,
                valueQids,
                discoverSubjects,
                statementSource.valueSelectionName());

        ReifyConstruct reify = new ReifyConstruct(
                sourceClassName,
                "__" + statementClass.className(),
                statementClass.className(),
                "source",
                "value",
                true,
                roles,
                dedup,
                primaryListField);

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
        }

        if (values.isEmpty()
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

    private static List<ReifyConstruct.Role> fallbackRoles(
            CompiledClass statementClass,
            String valueField) {

        List<ReifyConstruct.Role> roles = new ArrayList<>();

        for (CompiledField field : statementClass.ownFields()) {
            if (!supportsMissingQualifierPolicy(statementClass, field)) {
                continue;
            }

            MissingQualifierPolicy policy =
                    field.source().missingQualifierPolicy();
            if (policy == null) {
                policy = MissingQualifierPolicy.STATEMENT_SUBJECT;
            }

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

    /** Compiled fields are never name fields (the compiler drops those), so a
     *  runtime statement field is simply an AUTO-produced one. */
    private static boolean runtimeStatementField(CompiledField field) {
        return field != null
                && field.source().productionKind() == FieldProductionKind.AUTO;
    }

    private static boolean supportsMissingQualifierPolicy(
            CompiledClass owner, CompiledField field) {
        return owner.statementClass()
                && runtimeStatementField(field)
                && field.source().qualifier()
                && field.type() == FieldType.ENTITY
                && !field.collection();
    }

    private static String findValueField(
            GeneratedClassModel statementClass,
            String statementPid) {

        // The value role is explicit: the non-qualifier field on the statement PID
        // (statementPid is what the predicate derives from the class). No first-field
        // guess — a missing value field is a validation error, not a wrong reify.
        String name =
                StatementFieldSemantics.statementValueFieldName(statementClass);
        return name.isEmpty() ? "value" : name;
    }

    private static List<String> valueQids(
            GeneratedClassModel statementClass,
            GeneratedClassModel sourceClass,
            String statementPid,
            String valueField) {

        LinkedHashSet<String> values = new LinkedHashSet<>();

        GeneratedFieldModel valueModel = statementClass.fields().stream()
                                                       .filter(StatementFieldSemantics::isRuntimeStatementField)
                                                       .filter(field -> valueField.equals(field.name()))
                                                       .findFirst()
                                                       .orElse(null);

        if (valueModel != null) {
            for (String qid : valueModel.mapping().allowedQids()) {
                String cleanQid = clean(qid);
                if (WikidataIds.isQid(cleanQid)) {
                    values.add(cleanQid);
                }
            }
        }

        // If the value field has no explicit QID set, inherit the SOURCE
        // class's membership targets when it uses the same property. The
        // reified statement value is then necessarily one of those targets.
        //
        // This keeps the qualifier-load filter aligned with the root query.
        // Falling back only to a broad P31 type can miss exceptional values,
        // such as Oscar categories that are not typed Q19020.
        if (values.isEmpty()
                && statementPid.equals(
                clean(sourceClass.instanceMapping().propertyPid()))) {

            String sourceQid =
                    clean(sourceClass.instanceMapping().sourceQid());
            if (WikidataIds.isQid(sourceQid)) {
                values.add(sourceQid);
            }

            for (String qid
                    : sourceClass.instanceMapping().additionalTypeQids()) {
                String cleanQid = clean(qid);
                if (WikidataIds.isQid(cleanQid)) {
                    values.add(cleanQid);
                }
            }
        }

        return new ArrayList<>(values);
    }

    /**
     * Compiles the explicit missing-qualifier policy into the existing generic
     * reify-role representation.
     *
     * <ul>
     *   <li>STATEMENT_SUBJECT: use the qualifier field, falling back to source.</li>
     *   <li>STATEMENT_VALUE: copy the main ps: value when the qualifier is absent.</li>
     *   <li>MISSING: create no fallback role.</li>
     *   <li>null: preserve the legacy default for single ENTITY qualifiers.</li>
     * </ul>
     */
    private static List<ReifyConstruct.Role> fallbackRoles(
            GeneratedClassModel statementClass,
            String valueField) {

        List<ReifyConstruct.Role> roles = new ArrayList<>();

        for (GeneratedFieldModel field : statementClass.fields()) {
            if (!StatementFieldSemantics
                    .supportsMissingQualifierPolicy(
                            statementClass,
                            field)) {
                continue;
            }

            MissingQualifierPolicy policy =
                    field.mapping().missingQualifierPolicy();

            if (policy == null) {
                // Legacy behavior: every single ENTITY qualifier defaulted to
                // the statement subject unless explicitly disabled.
                policy = MissingQualifierPolicy.STATEMENT_SUBJECT;
            }

            switch (policy) {
                case STATEMENT_SUBJECT ->
                        roles.add(new ReifyConstruct.Role(
                                field.name(),
                                field.name(),
                                true,
                                field.mapping().roleKind()));

                case STATEMENT_VALUE ->
                        roles.add(new ReifyConstruct.Role(
                                field.name(),
                                valueField,
                                false,
                                wikidata.explore.model.RoleKind.VALUE));

                case MISSING -> {
                    // No role: keep the qualifier absent.
                }
            }
        }

        return roles;
    }

    /**
     * Runtime identity is exactly the stored, de-duplicated canonical key, read
     * identically off the editable ({@link CanonicalSpec}) or compiled
     * ({@code CompiledCanonical}) model. Whether every key field still exists is a
     * validation/compilation concern — {@link GeneratedProjectModelValidator}
     * flags a missing one and the compiler rejects it — so this reader never
     * filters or repairs: a reader that silently dropped a field would reopen the
     * compiled-vs-editable divergence this construct exists to prevent.
     */
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
            if (qualifier.kind()
                    == QualifierLoadConfig.Kind.YEAR) {
                qualifierText.append("(date)");
            }
        }

        return "Reify " + load.propertyPid()
                + " of " + load.entityType()
                + " → " + reify.targetType()
                + "\n value: " + load.valueField()
                + "\n canonical nominee-list: "
                + (reify.canonicalizesByList()
                ? reify.primaryListField()
                : "—")
                + "\n subject-fallback fields: "
                + display(subjectFallbacks)
                + "\n statement-value-fallback fields: "
                + display(valueFallbacks)
                + "\n canonical key: "
                + display(reify.dedupBy(), " + ")
                + "\n qualifiers: "
                + (qualifierText.length() == 0
                ? "—"
                : qualifierText);
    }

    public static List<WikidataDynamicObject> apply(
            GeneratedProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {

        enrich(project, pool, client, log);
        return reify(project, pool, log);
    }

    /**
     * NETWORK stage: loads qualifier values onto statement objects in the pool.
     * It is separate from reify so remapping can repeat the pure stage without
     * issuing the network requests again.
     */
    public static void enrich(
            GeneratedProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {

        if (client == null) {
            return;
        }

        QualifierLoader loader = new QualifierLoader();
        for (Reification reification : derive(project)) {
            loader.enrich(
                    pool,
                    reification.load(),
                    client,
                    log);
        }
    }

    /**
     * PURE stage: promotes already enriched statement objects into records.
     */
    public static List<WikidataDynamicObject> reify(
            GeneratedProjectModel project,
            List<WikidataDynamicObject> pool,
            GenerationLog log) {

        return reify(project, pool, log, null);
    }

    /**
     * As {@link #reify(GeneratedProjectModel, List, GenerationLog)}, also
     * collects duplicate records dropped during canonicalization.
     */
    public static List<WikidataDynamicObject> reify(
            GeneratedProjectModel project,
            List<WikidataDynamicObject> pool,
            GenerationLog log,
            Set<WikidataDynamicObject> demotedOut) {

        List<WikidataDynamicObject> created =
                new ArrayList<>();
        TransformEngine engine = new TransformEngine();
        List<TransformEngine.SelfRefFinding> findings = new ArrayList<>();

        for (Reification reification : derive(project)) {
            List<WikidataDynamicObject> records =
                    engine.applyReify(
                            pool,
                            reification.reify(),
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

        return created;
    }

    // #99: audit the self-referential phantom drop in the generation log so each
    // decision (DROPPED with its witness, or KEPT for lack of one) is verifiable.
    private static void logSelfReferenceFindings(
            GenerationLog log, List<TransformEngine.SelfRefFinding> findings) {
        if (log == null || findings.isEmpty()) {
            return;
        }
        long dropped = findings.stream()
                .filter(f -> f.decision()
                        == TransformEngine.SelfRefDecision.DROPPED)
                .count();
        log.message("Self-referential reify atoms (#99): " + dropped
                + " dropped (witnessed phantom), " + (findings.size() - dropped)
                + " kept (no witness). Dropped:\n");
        // Only the DROPPED decisions are logged in detail — they removed a record,
        // so they're the auditable ones. KEPT (fully self-referential but legitimate,
        // e.g. a film that IS its Best-International-Feature nominee) is the normal
        // case and its count above is enough; listing all of them floods the log.
        for (TransformEngine.SelfRefFinding finding : findings) {
            if (finding.decision() == TransformEngine.SelfRefDecision.DROPPED) {
                log.message("  " + finding + "\n");
            }
        }
    }

    private static void logReify(
            GenerationLog log,
            Reification reification,
            int recordCount,
            List<String> gaps) {

        log.message(describe(reification)
                            + "\n → " + recordCount + " records\n");

        if (!gaps.isEmpty()) {
            log.message(
                    " ⚠ consistency: the value filter misses "
                            + gaps.size()
                            + " of the source class's membership "
                            + "target(s) — "
                            + gaps.stream()
                                  .limit(6)
                                  .collect(
                                          java.util.stream.Collectors
                                                  .joining(", "))
                            + (gaps.size() > 6 ? ", …" : "")
                            + " — statements to those WON'T load. "
                            + "Add them to the value field's allowed "
                            + "values (or align the class membership).\n");
        }
    }

    /**
     * Returns source-class membership targets that are not covered by a
     * reification's explicit statement-value filter.
     */
    public static List<String> valueFilterGaps(
            Reification reification,
            GeneratedProjectModel project) {

        List<String> missed = new ArrayList<>();
        if (reification == null || project == null) {
            return missed;
        }

        QualifierLoadConfig config = reification.load();
        GeneratedClassModel sourceClass =
                project.findClass(config.entityType());

        if (sourceClass == null
                || config.valueQids() == null
                || config.valueQids().isEmpty()
                || !config.propertyPid().equals(
                clean(sourceClass
                              .instanceMapping()
                              .propertyPid()))) {
            return missed;
        }

        Set<String> filter =
                new LinkedHashSet<>(config.valueQids());

        String sourceQid =
                clean(sourceClass.instanceMapping().sourceQid());
        if (WikidataIds.isQid(sourceQid)
                && !filter.contains(sourceQid)) {
            missed.add(sourceQid);
        }

        for (String qid
                : sourceClass.instanceMapping().additionalTypeQids()) {
            String cleanQid = clean(qid);
            if (WikidataIds.isQid(cleanQid)
                    && !filter.contains(cleanQid)) {
                missed.add(cleanQid);
            }
        }

        return missed;
    }

    // ---- Compiled-model pipeline overloads (parity with the raw ones above) ----

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
        if (client == null) return new AcquisitionReport(List.of());
        QualifierLoader loader = new QualifierLoader().api(entityApi)
                .deferLabels(deferLabels);
        List<AcquiredStatementClass> acquired = new ArrayList<>();
        for (Reification reification : derive(project)) {
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

        return created;
    }

    /** Compiled-model overload of {@link #valueFilterGaps(Reification,
     *  GeneratedProjectModel)}. */
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
                || config.valueQids() == null
                || config.valueQids().isEmpty()
                || !config.propertyPid().equals(
                clean(sourceClass.sourceMapping().propertyPid()))) {
            return missed;
        }

        Set<String> filter = new LinkedHashSet<>(config.valueQids());

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
            FieldType type) {

        if (type == FieldType.ENTITY) {
            return QualifierLoadConfig.Kind.ENTITY;
        }
        if (type == FieldType.DATE) {
            return QualifierLoadConfig.Kind.YEAR;
        }
        return QualifierLoadConfig.Kind.STRING;
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
