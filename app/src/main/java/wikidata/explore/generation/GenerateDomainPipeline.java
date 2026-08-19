package wikidata.explore.generation;

import process.ProcessWorkflowPipeline;
import process.PhaseExplanation;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import java.util.ArrayList;
import java.util.List;

/** The executable Generate Domain phases and their model-derived explanation. */
public final class GenerateDomainPipeline {
    public static final String PLAN = "validate-plan";
    public static final String DISCOVER = "discover";
    public static final String ACQUIRE_STATEMENTS = "statement-facts";
    public static final String CONSTRUCT = "construct";
    public static final String SEMANTIC = "semantic-worklist";
    public static final String LABELS = "labels";
    public static final String FINALIZE = "finalize";
    public static final String MATERIALIZE = "materialize";
    // Compatibility aliases for callers/tests that address the old coarse phases.
    public static final String EXTRACT = DISCOVER;
    public static final String REIFY = CONSTRUCT;
    public static final String ROLE_EVIDENCE = SEMANTIC;
    public static final String CLASSIFY = SEMANTIC;
    public static final String OWNED = SEMANTIC;
    public static final String KIND_OWNED_FIELDS = SEMANTIC;

    private GenerateDomainPipeline() { }

    public static ProcessWorkflowPipeline configured(GeneratedProjectModel model) {
        return new ProcessWorkflowPipeline(List.of(
                phase(PLAN, "Validate & plan",
                        "Freeze the model, compile it and derive required operations.",
                        List.of(model.classes().size() + " configured classes")),
                phase(DISCOVER, "Discover populations",
                        "Discover root members and statement subjects.",
                        rootDetails(model)),
                phase(ACQUIRE_STATEMENTS, "Acquire statement facts",
                        "Load main statements and configured qualifier facts.",
                        statementAcquisitionDetails(model)),
                phase(CONSTRUCT, "Construct graph",
                        "Reify statement records, apply restrictions, inverts and projections.",
                        statementConstructionDetails(model)),
                phase(SEMANTIC, "Resolve semantic worklist",
                        "Repeat role stamping, field/evidence acquisition, kind classification "
                                + "and owned-value construction until stable.",
                        semanticDetails(model), semanticExplanation(model)),
                phase(LABELS, "Hydrate final labels",
                        "Resolve labels once for placeholder QIDs in the closed graph.",
                        List.of("English labels with multilingual fallback")),
                phase(FINALIZE, "Finalize & validate",
                        "Canonicalize, prune, apply expectations, audit consistency and build vocabularies.",
                        List.of(model.classes().size() + " configured classes")),
                phase(MATERIALIZE, "Materialize instances",
                        "Compile and map the final shared object graph.",
                        List.of(model.classes().size() + " configured classes"))));
    }

    private static ProcessWorkflowPipeline.Phase phase(
            String id, String title, String description, List<String> details) {
        return phase(id, title, description, details, new PhaseExplanation(
                description, List.of(), details, List.of(), List.of(), List.of()));
    }

    private static ProcessWorkflowPipeline.Phase phase(
            String id, String title, String description, List<String> details,
            PhaseExplanation explanation) {
        return new ProcessWorkflowPipeline.Phase(
                id, title, description, details, explanation);
    }

    private static List<String> rootDetails(GeneratedProjectModel model) {
        List<String> out = new ArrayList<>();
        for (GeneratedClassModel clazz : model.classes()) {
            MembershipPattern pattern = MembershipPattern.of(clazz, model);
            if (pattern != MembershipPattern.REFERENCED
                    && pattern != MembershipPattern.OWNED_COMPONENT
                    && pattern != MembershipPattern.EVIDENCE_KIND
                    && !clazz.reifiesStatements()) {
                out.add(clazz.className() + " — " + pattern
                        + ", depth " + clazz.generationDepth());
            }
        }
        return none(out, "No directly extracted root classes");
    }

    private static List<String> statementAcquisitionDetails(GeneratedProjectModel model) {
        List<String> out = new ArrayList<>();
        for (var recipe : wikidata.explore.transform.ModelStatementReifications.derive(model)) {
            var load = recipe.load();
            String source = load.discoverSubjects()
                    ? "discover subjects into " + load.entityType()
                    : "reuse " + load.entityType() + " members";
            String qualifiers = load.qualifiers() == null || load.qualifiers().isEmpty()
                    ? "no qualifiers"
                    : load.qualifiers().stream().map(q -> q.fieldName() + " ← " + q.pid()
                            + " (" + q.kind() + (q.multi() ? ", list" : "") + ")")
                            .collect(java.util.stream.Collectors.joining(", "));
            out.add(load.statementType() + " — " + source + "; load "
                    + load.propertyPid() + " statements; " + qualifiers
                    + "; value domain: " + load.valueDomainLabel());
        }
        return none(out, "No statement classes");
    }

    private static List<String> statementConstructionDetails(GeneratedProjectModel model) {
        List<String> out = new ArrayList<>();
        for (var recipe : wikidata.explore.transform.ModelStatementReifications.derive(model)) {
            var load = recipe.load();
            var construct = recipe.reify();
            String key = construct.dedupBy().isEmpty() ? "surrogate statement identity"
                    : "canonical key " + String.join(" + ", construct.dedupBy());
            out.add(construct.targetType() + " — promote " + load.statementField()
                    + " records; source → " + construct.sourceField()
                    + ", value → " + load.valueField() + "; " + key
                    + "; " + construct.roles().size() + " fallback role(s)");
        }
        return none(out, "No statement classes");
    }

    private static List<String> roleFieldDetails(GeneratedProjectModel model) {
        List<String> out = new ArrayList<>();
        for (GeneratedClassModel clazz : model.classes()) {
            if (MembershipPattern.of(clazz, model) != MembershipPattern.REFERENCED) continue;
            addPropertyFields(out, clazz);
        }
        return none(out, "No referenced-role property fields");
    }

    private static List<String> kindDetails(GeneratedProjectModel model) {
        List<String> out = model.entityKindRules().stream().filter(r -> r.isConfigured())
                .map(r -> r.className() + " ← " + r.propertyPid() + " in "
                        + String.join(", ", r.evidenceQids())
                        + evidenceProducerSuffix(model, r.propertyPid())).toList();
        return none(out, "No entity-kind rules");
    }

    private static String evidenceProducerSuffix(
            GeneratedProjectModel model, String propertyPid) {
        List<String> producers = new ArrayList<>();
        for (GeneratedClassModel owner : model.classes()) {
            if (owner == null || MembershipPattern.of(owner, model)
                    != MembershipPattern.REFERENCED) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field != null && field.mapping() != null
                        && propertyPid.equalsIgnoreCase(field.mapping().propertyPid())) {
                    producers.add(owner.className() + "." + field.name());
                }
            }
        }
        return producers.isEmpty() ? " — all role members (no modeled producer)"
                : " — candidates from " + String.join(", ", producers);
    }

    private static List<String> ownedDetails(GeneratedProjectModel model) {
        List<String> out = new ArrayList<>();
        for (GeneratedClassModel owner : model.classes()) {
            for (GeneratedFieldModel field : owner.fields()) {
                if (field.mapping().productionKind() == FieldProductionKind.OWNED_COMPONENT) {
                    out.add(owner.className() + "." + field.name() + " → "
                            + field.entityClassName() + " (owner QID)");
                }
            }
        }
        return none(out, "No owned-component fields");
    }

    private static List<String> kindOwnedFieldDetails(GeneratedProjectModel model) {
        List<String> out = new ArrayList<>();
        for (GeneratedClassModel clazz : model.classes()) {
            MembershipPattern pattern = MembershipPattern.of(clazz, model);
            if (pattern == MembershipPattern.EVIDENCE_KIND
                    || pattern == MembershipPattern.OWNED_COMPONENT) {
                addPropertyFields(out, clazz);
            }
        }
        return none(out, "No kind/owned property fields");
    }

    private static List<String> semanticDetails(GeneratedProjectModel model) {
        List<String> out = new ArrayList<>();
        out.add("Role stamping — apply each ENTITY field's declared target class");
        out.add("Reachability — rescan the graph after every newly loaded or owned value");
        out.add("Evidence acquisition — fetch all newly reachable declared properties together");
        var manifest = wikidata.explore.transform.ReferentFieldLoad.compileManifest(model);
        manifest.propertiesByClass().forEach((className, pids) -> out.add(
                className + " acquisition slice — " + String.join(", ", pids)));
        out.add("Kind classification — combine stored evidence with missing remote evidence");
        out.add("Owned construction — materialize owner-QID components after kinds settle");
        out.addAll(roleFieldDetails(model));
        out.addAll(kindDetails(model));
        out.addAll(ownedDetails(model));
        out.addAll(kindOwnedFieldDetails(model));
        return out.stream().distinct().toList();
    }

    private static PhaseExplanation semanticExplanation(GeneratedProjectModel model) {
        List<String> inputs = new ArrayList<>(List.of(
                "Reachable entities and statement records produced so far",
                "Declared target classes on entity-valued fields",
                "Persisted field coverage and retained Wikidata facts"));
        List<String> operations = List.of(
                "Stamp role membership from configured field targets",
                "Acquire newly demanded properties in model-derived bundles",
                "Classify entity kinds from configured evidence rules",
                "Construct owned values that inherit their owner's QID",
                "Repeat until no stamp, value, kind or owned component changes");
        List<String> outputs = List.of(
                "Role and entity-kind class memberships",
                "Loaded referent and owned-field values",
                "Owned components connected to their owners",
                "Exact-QID declarations recording answered fields",
                "A fixed-point object graph ready for final labels and validation");

        List<PhaseExplanation.ModelReference> references = new ArrayList<>();
        List<PhaseExplanation.PhaseExample> examples = new ArrayList<>();
        for (GeneratedClassModel clazz : model.classes()) {
            for (GeneratedFieldModel field : clazz.effectiveFields(model)) {
                String pid = field.mapping() == null ? "" : field.mapping().propertyPid();
                boolean owned = field.mapping() != null
                        && field.mapping().productionKind()
                        == FieldProductionKind.OWNED_COMPONENT;
                if (pid != null && pid.matches("(?i)P\\d+")) {
                    references.add(PhaseExplanation.ModelReference.field(
                            clazz.className(), field.name()));
                    references.add(PhaseExplanation.ModelReference.property(pid));
                    if (examples.size() < 3) examples.add(propertyExample(clazz, field, pid));
                }
                if (owned) {
                    references.add(PhaseExplanation.ModelReference.field(
                            clazz.className(), field.name()));
                    references.add(PhaseExplanation.ModelReference.clazz(
                            field.entityClassName()));
                    examples.add(ownedExample(clazz, field));
                }
            }
        }
        for (var rule : model.entityKindRules()) {
            if (!rule.isConfigured()) continue;
            references.add(PhaseExplanation.ModelReference.kindRule(rule.className()));
            references.add(PhaseExplanation.ModelReference.property(rule.propertyPid()));
            references.add(PhaseExplanation.ModelReference.clazz(rule.className()));
            examples.add(kindExample(model, rule));
        }
        return new PhaseExplanation(
                "Close the semantic gap between records discovered from Wikidata and "
                        + "the typed object graph promised by the model.",
                inputs, operations, outputs,
                references.stream().distinct().toList(),
                examples.stream().limit(6).toList());
    }

    private static PhaseExplanation.PhaseExample propertyExample(
            GeneratedClassModel owner, GeneratedFieldModel field, String pid) {
        String target = field.entityClassName() == null || field.entityClassName().isBlank()
                ? field.type().toString() : field.entityClassName();
        return new PhaseExplanation.PhaseExample(
                PhaseExplanation.ExampleKind.PLANNED,
                "Load " + owner.className() + "." + field.name(),
                List.of("A reachable " + owner.className() + " carrying a Wikidata QID"),
                List.of(pid + " is the configured producing property"),
                List.of("Store the answered value as " + target,
                        "Record coverage even when Wikidata returns no value"),
                List.of(PhaseExplanation.ModelReference.field(
                                owner.className(), field.name()),
                        PhaseExplanation.ModelReference.property(pid)));
    }

    private static PhaseExplanation.PhaseExample ownedExample(
            GeneratedClassModel owner, GeneratedFieldModel field) {
        return new PhaseExplanation.PhaseExample(
                PhaseExplanation.ExampleKind.PLANNED,
                "Compose owned " + field.entityClassName(),
                List.of("A " + owner.className() + " instance with QID Q…"),
                List.of(owner.className() + "." + field.name()
                        + " declares owned production"),
                List.of("Create " + field.entityClassName() + "@"
                                + owner.className() + "." + field.name(),
                        "The component inherits Q… only for loading its declared fields"),
                List.of(PhaseExplanation.ModelReference.field(
                                owner.className(), field.name()),
                        PhaseExplanation.ModelReference.clazz(field.entityClassName())));
    }

    private static PhaseExplanation.PhaseExample kindExample(
            GeneratedProjectModel model, wikidata.explore.model.EntityKindRule rule) {
        String producers = evidenceProducerSuffix(model, rule.propertyPid())
                .replace(" — candidates from ", "").replace(
                        " — all role members (no modeled producer)", "role members");
        return new PhaseExplanation.PhaseExample(
                PhaseExplanation.ExampleKind.PLANNED,
                "Classify an entity as " + rule.className(),
                List.of("A candidate entity reached through " + producers),
                List.of(rule.propertyPid() + " contains one of "
                        + String.join(", ", rule.evidenceQids())),
                List.of("Add " + rule.className() + " membership",
                        "Use the deterministic most-specific kind as display carrier"),
                List.of(PhaseExplanation.ModelReference.kindRule(rule.className()),
                        PhaseExplanation.ModelReference.property(rule.propertyPid()),
                        PhaseExplanation.ModelReference.clazz(rule.className())));
    }

    private static void addPropertyFields(List<String> out, GeneratedClassModel clazz) {
        for (GeneratedFieldModel field : clazz.fields()) {
            String pid = field.mapping() == null ? "" : field.mapping().propertyPid();
            if (pid != null && pid.matches("(?i)P\\d+")) {
                String target = field.entityClassName() == null
                        || field.entityClassName().isBlank() ? "" : " → " + field.entityClassName();
                out.add(clazz.className() + "." + field.name() + " — "
                        + field.type() + " " + field.cardinality() + target + " — " + pid);
            }
        }
    }

    private static List<String> none(List<String> values, String message) {
        return values == null || values.isEmpty() ? List.of(message) : List.copyOf(values);
    }
}
