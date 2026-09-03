package wikidata.explore.advisor;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The one derivation of the effective class explanation shown by ModelBuilder. */
public final class EffectiveClassExplanations {
    private EffectiveClassExplanations() { }

    public static EffectiveClassExplanation explain(
            GeneratedProjectModel project, GeneratedClassModel declaration) {
        if (project == null || declaration == null) return unavailable("", "No class selected");
        final CompiledProjectModel effective;
        final CompiledClass compiled;
        try {
            effective = ProjectModelCompiler.compile(project);
            compiled = effective
                    .findClass(declaration.className()).orElse(null);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return unavailable(declaration.className(), invalid.getMessage());
        }
        if (compiled == null) {
            return unavailable(declaration.className(), "The compiled model has no such class");
        }

        Set<String> ownNames = new LinkedHashSet<>();
        compiled.ownFields().forEach(field -> ownNames.add(field.name()));
        String inheritedFrom = compiled.hasBase() ? compiled.baseClassName() : "";
        List<EffectiveClassExplanation.Field> fields = compiled.effectiveFields().stream()
                .map(field -> new EffectiveClassExplanation.Field(
                        field.name(), fieldType(field),
                        ownNames.contains(field.name()) ? declarationOrigin(declaration)
                                : "inherited through " + inheritedFrom,
                        partOf(compiled, field),
                        filledBy(compiled, field)))
                .toList();

        return new EffectiveClassExplanation(
                compiled.displayClassName(),
                declarationDescription(declaration, compiled),
                instanceDescription(declaration, project),
                fields,
                identityDescription(declaration),
                // ModelDeclarationGraph will own the reverse declaration index. Do not
                // create a second field/rule scan here while that shared construct is
                // still only designed — and say nothing rather than guess, because
                // "no references" is a finding this cannot make yet.
                java.util.Optional.empty(),
                "");
    }

    public static EffectiveFieldExplanation explainField(
            GeneratedProjectModel project,
            GeneratedClassModel owner,
            GeneratedFieldModel declaration) {
        if (project == null || owner == null || declaration == null) {
            return unavailableField("No field selected");
        }
        try {
            CompiledClass compiledOwner = ProjectModelCompiler.compile(project)
                    .findClass(owner.className()).orElse(null);
            var field = compiledOwner == null ? null
                    : compiledOwner.field(declaration.name()).orElse(null);
            if (field == null) return unavailableField("The compiled model has no such field");
            String target = field.entityReference()
                    ? (field.entityClassName().isBlank()
                    ? "Unresolved entity class" : field.entityClassName())
                    : "Scalar value";
            return new EffectiveFieldExplanation(
                    compiledOwner.displayClassName(), field.name(),
                    field.type() + (field.collection() ? " list" : " value"),
                    sourceDescription(field.source()), target,
                    field.source().roleKind().toString().toLowerCase().replace('_', ' '), "");
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return unavailableField(invalid.getMessage());
        }
    }

    private static String sourceDescription(
            wikidata.explore.compiled.CompiledFieldSource source) {
        if (!source.inverseField().isBlank()) return "Inverse of " + source.inverseField();
        if (!source.qualifierPid().isBlank()) {
            return source.sourceType() + " qualifier " + source.displayQualifier();
        }
        if (!source.propertyPid().isBlank()) {
            String direction = source.direction() == wikidata.explore.model.RuleDirection.ITEM_TO_ROOT
                    ? "incoming" : "outgoing";
            return source.sourceType() + " · " + source.displayProperty()
                    + " · " + direction;
        }
        return "No value source declared";
    }

    private static String instanceDescription(
            GeneratedClassModel declaration, GeneratedProjectModel project) {
        String description = MembershipPattern.describe(declaration, project);
        var kindRule = MembershipPattern.kindRule(declaration, project);
        if (MembershipPattern.of(declaration, project) == MembershipPattern.REFERENCED
                && kindRule != null) {
            description += "; represented as " + declaration.className() + " when "
                    + kindRule.propertyPid() + " = "
                    + String.join(", ", kindRule.evidenceQids());
        }
        return description;
    }

    private static String declarationDescription(
            GeneratedClassModel declaration, CompiledClass compiled) {
        String owner = declaration.isImported()
                ? "Imported from model '" + declaration.importedFrom() + "'"
                : "Declared in this project";
        return compiled.hasBase() ? owner + "; extends " + compiled.baseClassName() : owner;
    }

    /**
     * Which job a field does on a reified statement.
     *
     * <p>Read from compilation, not guessed again here: compilation delegates the
     * subject/value decisions to {@code StatementFieldSemantics} and carries the
     * resulting production kind and resolved value field. The explanation consumes
     * those decisions so it cannot develop a second statement grammar.
     * Whether a qualifier also DISTINGUISHES two records is the canonical key's answer,
     * and it is a real difference: the same person held the same position twice, and
     * only the dates tell those two records apart.
     */
    private static EffectiveClassExplanation.Part partOf(
            CompiledClass owner, wikidata.explore.compiled.CompiledField field) {
        if (owner == null || field == null || !owner.statementClass()) {
            return EffectiveClassExplanation.Part.PLAIN;
        }
        // The RESOLVED subject role, not the raw production kind. A model that never
        // marked its subject explicitly still HAS one, and asking the stored kind alone
        // dropped it into "said about it" with nothing filling it.
        if (owner.statementSource().subjectField().equals(field.name())) {
            return EffectiveClassExplanation.Part.SUBJECT;
        }
        if (owner.statementSource().valueField().equals(field.name())) {
            return EffectiveClassExplanation.Part.VALUE;
        }
        if (field.source().qualifier()) {
            return owner.canonical().keyFields().contains(field.name())
                    ? EffectiveClassExplanation.Part.DISTINGUISHING
                    : EffectiveClassExplanation.Part.DESCRIBING;
        }
        return EffectiveClassExplanation.Part.DESCRIBING;
    }

    /** The class an entity field points at, which says more than "Entity/Object". */
    private static String fieldType(wikidata.explore.compiled.CompiledField field) {
        String target = field.entityClassName();
        String base = target == null || target.isBlank()
                ? String.valueOf(field.type()) : target;
        return base + (field.collection() ? " list" : "");
    }

    /**
     * What puts a value in this field — the question a reader asks of a field, and the
     * one the declaration origin ("this project") does not answer. For a statement
     * class each part is filled differently: the subject comes from the statement, the
     * value from the statement's own property, a qualifier from its own PID.
     */
    private static String filledBy(
            CompiledClass owner, wikidata.explore.compiled.CompiledField field) {
        if (owner == null || field == null) return "";
        return switch (partOf(owner, field)) {
            case SUBJECT -> "the entity the statement is about";
            case VALUE -> {
                // No null check: VALUE is only returned for a statement class, and
                // statementClass() already proved the source non-null.
                var source = owner.statementSource();
                String named = field.source().displayProperty();
                String described = named.isBlank()
                        || named.equals(field.source().propertyPid())
                        ? source.displayProperty() : named;
                yield "the statement's value · " + described;
            }
            case DISTINGUISHING, DESCRIBING -> {
                String qualifier = field.source().displayQualifier();
                yield qualifier.isBlank() ? field.source().displayProperty()
                        : "qualifier · " + qualifier;
            }
            case PLAIN -> {
                yield field.source().displayProperty();
            }
        };
    }

    private static Set<String> keyFields(GeneratedClassModel declaration) {
        var canonical = declaration.canonical();
        return canonical == null ? Set.of() : new LinkedHashSet<>(canonical.keyFields());
    }

    /**
     * What tells one record from another, and what happens when two still collide.
     * Without it a reader cannot tell why a class holds 179 records over 173 distinct
     * subject/value pairs.
     */
    private static String identityDescription(GeneratedClassModel declaration) {
        Set<String> key = keyFields(declaration);
        if (key.isEmpty()) return "";
        var canonical = declaration.canonical();
        String policy = canonical == null || canonical.duplicatePolicy() == null
                ? "" : switch (canonical.duplicatePolicy().name()) {
                    case "KEEP_ONE" -> "; two records with the same key keep one";
                    case "MERGE_RECORDS" -> "; two records with the same key are merged";
                    default -> "";
                };
        return String.join(" + ", key) + policy;
    }

    private static String declarationOrigin(GeneratedClassModel declaration) {
        return declaration.isImported()
                ? "model " + declaration.importedFrom() : "this project";
    }

    private static EffectiveClassExplanation unavailable(String name, String reason) {
        // A model that could not be compiled has said nothing about uses either.
        return new EffectiveClassExplanation(name, "", "", List.of(), "",
                java.util.Optional.empty(),
                reason == null || reason.isBlank() ? "Effective class is unavailable" : reason);
    }

    private static EffectiveFieldExplanation unavailableField(String reason) {
        return new EffectiveFieldExplanation("", "", "", "", "", "",
                reason == null || reason.isBlank() ? "Effective field is unavailable" : reason);
    }
}
