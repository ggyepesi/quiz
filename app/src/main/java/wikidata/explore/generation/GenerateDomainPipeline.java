package wikidata.explore.generation;

import process.ProcessWorkflowPipeline;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import java.util.ArrayList;
import java.util.List;

/** The executable Generate Domain phases and their model-derived explanation. */
public final class GenerateDomainPipeline {
    public static final String EXTRACT = "extract";
    public static final String REIFY = "reify";
    public static final String ROLE_EVIDENCE = "role-evidence";
    public static final String CLASSIFY = "classify";
    public static final String OWNED = "owned";
    public static final String KIND_OWNED_FIELDS = "kind-owned-fields";
    public static final String MATERIALIZE = "materialize";

    private GenerateDomainPipeline() { }

    public static ProcessWorkflowPipeline configured(GeneratedProjectModel model) {
        return new ProcessWorkflowPipeline(List.of(
                phase(EXTRACT, "Extract roots",
                        "Compile class membership and download root populations.",
                        rootDetails(model)),
                phase(REIFY, "Reify statements",
                        "Load statement qualifiers and create statement-class records.",
                        reifyDetails(model)),
                phase(ROLE_EVIDENCE, "Load role evidence",
                        "Load property fields already declared on referenced roles.",
                        roleFieldDetails(model)),
                phase(CLASSIFY, "Classify kinds",
                        "Assign modeled entity kinds from configured property evidence.",
                        kindDetails(model)),
                phase(OWNED, "Build owned values",
                        "Create owned-class values after owner kinds are settled.",
                        ownedDetails(model)),
                phase(KIND_OWNED_FIELDS, "Load kind/owned fields",
                        "Fill declarations made reachable by classification and ownership.",
                        kindOwnedFieldDetails(model)),
                phase(MATERIALIZE, "Validate & materialize",
                        "Canonicalize, prune, validate, build vocabularies and map instances.",
                        List.of(model.classes().size() + " configured classes"))));
    }

    private static ProcessWorkflowPipeline.Phase phase(
            String id, String title, String description, List<String> details) {
        return new ProcessWorkflowPipeline.Phase(id, title, description, details);
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

    private static List<String> reifyDetails(GeneratedProjectModel model) {
        List<String> out = model.classes().stream().filter(GeneratedClassModel::reifiesStatements)
                .map(c -> c.className() + " — statement class, "
                        + c.fields().size() + " fields").toList();
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
                        + String.join(", ", r.evidenceQids())).toList();
        return none(out, "No entity-kind rules");
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
