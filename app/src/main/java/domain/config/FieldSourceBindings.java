package domain.config;

import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceRecipe;
import domain.datasource.dbpedia.DbpediaDatasourceProvider;
import domain.datasource.wikidata.WikidataDatasourceProvider;
import domain.datasource.wikipedia.WikipediaCategoryDiscoveryOperation;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Migration boundary between typed datasource bindings and the field-source objects
 * still consumed by the rule compiler and existing editors.
 *
 * <p>New callers write through {@link #put}; old editors continue to change their
 * mapping/rule and save-time synchronization banks the equivalent binding. On load a
 * binding is projected first, so a model written by a binding-native editor remains
 * executable by the legacy compiler during the migration.
 */
public final class FieldSourceBindings {
    public static final String PROPERTY = "property";
    public static final String LABEL = "label";
    public static final String SOURCE_TYPE = "sourceType";
    public static final String VALUE_LANGUAGE = "valueLanguage";
    public static final String CONDITION_QUALIFIER = "conditionQualifier";
    public static final String CONDITION_VALUES = "conditionValues";
    public static final String PATTERN = "pattern";
    public static final String POLICY = "policy";

    private FieldSourceBindings() { }

    /** How one legacy source choice reaches the datasource registry. */
    public enum LegacySourceKind {
        /** The legacy choice names one fixed shipped provider operation. */
        REGISTERED_OPERATION,
        /** The stored binding, rather than the enum constant, names the operation. */
        REGISTRY_BINDING,
        /** The field is filled manually and has no datasource binding. */
        NO_BINDING
    }

    /**
     * Complete classification of one retained {@link FieldSourceType}.
     *
     * <p>This is the migration boundary's one answer to whether an old source choice
     * names a provider operation. New datasource capabilities do not add enum values;
     * they use {@link FieldSourceType#DATASOURCE} and store their operation in the
     * binding.</p>
     */
    public record LegacySourceCoverage(
            FieldSourceType sourceType,
            LegacySourceKind kind,
            String providerId,
            String operationId) {
        public LegacySourceCoverage {
            if (sourceType == null || kind == null) {
                throw new IllegalArgumentException("Source type and coverage kind are required");
            }
            providerId = clean(providerId);
            operationId = clean(operationId);
            boolean fixed = kind == LegacySourceKind.REGISTERED_OPERATION;
            if (fixed != (!providerId.isBlank() && !operationId.isBlank())) {
                throw new IllegalArgumentException(
                        "Only a registered-operation source names a fixed operation");
            }
        }

        public boolean registeredOperation() {
            return kind == LegacySourceKind.REGISTERED_OPERATION;
        }
    }

    /** Every retained legacy type is classified exhaustively here. */
    public static LegacySourceCoverage coverage(FieldSourceType type) {
        if (type == null) type = FieldSourceType.SPARQL;
        return switch (type) {
            case SPARQL -> registered(type, WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.PROPERTY_VALUE);
            case DBPEDIA -> registered(type, DbpediaDatasourceProvider.ID,
                    DbpediaDatasourceProvider.PROPERTY);
            case WIKIPEDIA_INFOBOX -> registered(type, WikipediaDatasourceProvider.ID,
                    WikipediaDatasourceProvider.INFOBOX_PARAMETER);
            case WIKIPEDIA_ARTICLE_INTRODUCTION,
                    WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH,
                    WIKIPEDIA_ARTICLE_LEAD_SENTENCE -> registered(type,
                    WikipediaDatasourceProvider.ID,
                    WikipediaDatasourceProvider.ARTICLE_INTRODUCTION);
            case WIKIDATA_SITELINK_COUNT -> registered(type,
                    WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.SITELINK_COUNT);
            case WIKIDATA_INCOMING_COUNT -> registered(type,
                    WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.INCOMING_RELATION_COUNT);
            case WIKIDATA_INHERITED_INCOMING_COUNT -> registered(type,
                    WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.INHERITED_INCOMING_RELATION_COUNT);
            case DATASOURCE -> new LegacySourceCoverage(type,
                    LegacySourceKind.REGISTRY_BINDING, "", "");
            case MANUAL -> new LegacySourceCoverage(type,
                    LegacySourceKind.NO_BINDING, "", "");
        };
    }

    public static List<LegacySourceCoverage> sourceTypeCoverage() {
        return java.util.Arrays.stream(FieldSourceType.values())
                .map(FieldSourceBindings::coverage).toList();
    }

    private static LegacySourceCoverage registered(
            FieldSourceType type, String provider, String operation) {
        return new LegacySourceCoverage(type, LegacySourceKind.REGISTERED_OPERATION,
                provider, operation);
    }

    public static void migrateOnLoad(GeneratedProjectModel project) {
        visit(project, (owner, path, field) -> {
            if (!field.sourceBindings().isEmpty()) {
                for (SourceBinding binding : List.copyOf(field.sourceBindings())) {
                    projectLegacy(field, binding);
                }
            }
            synchronize(owner, path, field);
            migrateOwnedOverrides(owner, path, field);
        });
    }

    public static void synchronizeForSave(GeneratedProjectModel project) {
        visit(project, (owner, path, field) -> {
            synchronize(owner, path, field);
            synchronizeOwnedOverrides(owner, path, field);
        });
    }

    /** Banks pending edits and returns the FIELD bindings, in model order. Class
     *  bindings are their own collector's to give; composing the two is the job of
     *  whoever wants the whole model. */
    static List<SourceBinding> synchronizeAndCollect(GeneratedProjectModel project) {
        synchronizeForSave(project);
        return collect(project);
    }

    /** Reads and validates already-banked bindings without changing the model. */
    static List<SourceBinding> collect(GeneratedProjectModel project) {
        List<SourceBinding> bindings = new ArrayList<>();
        visit(project, (owner, path, field) -> {
            for (SourceBinding binding : field.sourceBindings()) {
                if (!owner.equals(binding.target().className())
                        || !path.equals(binding.target().fieldPath())) {
                    throw new IllegalArgumentException("Source binding target "
                            + binding.target().className() + "."
                            + binding.target().fieldPath() + " is stored on "
                            + owner + "." + path);
                }
                bindings.add(binding);
            }
            for (OwnedFieldSource override : field.ownedFieldSources()) {
                if (override == null) continue;
                for (SourceBinding binding : override.sourceBindings()) {
                    SourceBindingTarget target = binding.target();
                    if (!field.entityClassName().equals(target.className())
                            || !override.fieldPath().equals(target.fieldPath())
                            || !owner.equals(target.contextClassName())
                            || !path.equals(target.contextFieldPath())) {
                        throw new IllegalArgumentException("Contextual source binding target "
                                + target.className() + "." + target.fieldPath()
                                + " at " + target.contextClassName() + "."
                                + target.contextFieldPath() + " is stored on "
                                + owner + "." + path + " for "
                                + field.entityClassName() + "." + override.fieldPath());
                    }
                    bindings.add(binding);
                }
            }
        });
        return List.copyOf(bindings);
    }

    /** Replace one singular semantic slot, or append one distinct candidate-evidence
     * child, and update the execution-compatible projection. */
    public static void put(GeneratedFieldModel field, SourceBinding binding) {
        if (field == null || binding == null) return;
        if (binding.target().scope() != domain.datasource.api.BindingScope.FIELD_VALUE) {
            throw new IllegalArgumentException("A model field needs a field-value binding");
        }
        if (binding.target().contextual()) {
            throw new IllegalArgumentException(
                    "A contextual binding belongs to its owned-field source override");
        }
        if (binding.target().slot().additive()) {
            if (!field.sourceBindings().contains(binding)) {
                field.sourceBindings().add(binding);
            }
        } else {
            replaceInPlace(field.sourceBindings(), binding);
        }
        projectLegacy(field, binding);
    }

    public static void put(OwnedFieldSource field, SourceBinding binding) {
        if (field == null || binding == null) return;
        if (binding.target().scope() != domain.datasource.api.BindingScope.FIELD_VALUE
                || !binding.target().contextual()) {
            throw new IllegalArgumentException(
                    "An owned-field source needs a contextual field-value binding");
        }
        replaceInPlace(field.sourceBindings(), binding);
        projectLegacy(field, binding);
    }

    /** A binding for an existing target keeps that target's place, so re-applying an
     *  unchanged editor leaves the saved order of a field's sources as it was. */
    private static void replaceInPlace(List<SourceBinding> bindings, SourceBinding binding) {
        for (int i = 0; i < bindings.size(); i++) {
            if (bindings.get(i).sameTarget(binding)) {
                bindings.set(i, binding);
                for (int later = bindings.size() - 1; later > i; later--) {
                    if (bindings.get(later).sameTarget(binding)) bindings.remove(later);
                }
                return;
            }
        }
        bindings.add(binding);
    }

    public static SourceBinding binding(
            GeneratedFieldModel field, SourceBindingSlot slot) {
        if (field == null || slot == null) return null;
        if (slot.additive()) {
            throw new IllegalArgumentException(
                    slot + " is a recipe list; use bindings(field, slot)");
        }
        return field.sourceBindings().stream()
                .filter(value -> value.target().slot() == slot)
                .findFirst().orElse(null);
    }

    /** Every configured child in one additive slot, in authored order. A singular
     *  projection must never be used for a slot whose children are independently
     *  configured and executed. */
    public static List<SourceBinding> bindings(
            GeneratedFieldModel field, SourceBindingSlot slot) {
        if (field == null || slot == null) return List.of();
        return field.sourceBindings().stream()
                .filter(value -> value.target().slot() == slot)
                .toList();
    }

    /** Replaces the visible ordered children of an additive slot without disturbing
     *  any other source configuration on the field. */
    public static void replaceBindings(GeneratedFieldModel field,
            SourceBindingSlot slot, List<SourceBinding> replacements) {
        if (field == null || slot == null) return;
        List<SourceBinding> existing = field.sourceBindings();
        int insertion = existing.size();
        for (int i = 0; i < existing.size(); i++) {
            if (existing.get(i).target().slot() == slot) {
                insertion = Math.min(insertion, i);
            }
        }
        existing.removeIf(value -> value.target().slot() == slot);
        if (replacements == null) return;
        int at = Math.min(insertion, existing.size());
        for (SourceBinding replacement : replacements) {
            if (replacement == null || replacement.target().slot() != slot) {
                throw new IllegalArgumentException(
                        "Every replacement must belong to " + slot);
            }
            existing.add(at++, replacement);
            projectLegacy(field, replacement);
        }
    }

    private static void synchronize(
            String owner, String path, GeneratedFieldModel field) {
        replacePrimary(field.sourceBindings(), field.mapping(),
                primary(owner, path, field.mapping()));
        replace(field, SourceBindingSlot.FALLBACK_FIELD_VALUE,
                fallback(owner, path, field.fallbackMapping()));
        replace(field, SourceBindingSlot.CATEGORY_EVIDENCE,
                category(owner, path, field.wikipediaCategoryRule()));
    }

    private static void migrateOwnedOverrides(
            String owner, String path, GeneratedFieldModel ownershipField) {
        for (OwnedFieldSource override : ownershipField.ownedFieldSources()) {
            if (override == null) continue;
            for (SourceBinding binding : List.copyOf(override.sourceBindings())) {
                projectLegacy(override, binding);
            }
        }
        synchronizeOwnedOverrides(owner, path, ownershipField);
    }

    private static void synchronizeOwnedOverrides(
            String owner, String path, GeneratedFieldModel ownershipField) {
        for (OwnedFieldSource override : ownershipField.ownedFieldSources()) {
            if (override == null || override.fieldPath().isBlank()) continue;
            SourceBindingTarget primaryTarget = SourceBindingTarget.ownedFieldValue(
                    ownershipField.entityClassName(), override.fieldPath(), owner, path,
                    SourceBindingSlot.PRIMARY_FIELD_VALUE);
            SourceBindingTarget fallbackTarget = SourceBindingTarget.ownedFieldValue(
                    ownershipField.entityClassName(), override.fieldPath(), owner, path,
                    SourceBindingSlot.FALLBACK_FIELD_VALUE);
            SourceBindingTarget categoryTarget = SourceBindingTarget.ownedFieldValue(
                    ownershipField.entityClassName(), override.fieldPath(), owner, path,
                    SourceBindingSlot.CATEGORY_EVIDENCE);
            replacePrimary(override.sourceBindings(), override.mapping(),
                    primary(primaryTarget, override.mapping()));
            replace(override.sourceBindings(), SourceBindingSlot.FALLBACK_FIELD_VALUE,
                    fallback(fallbackTarget, override.fallbackMapping()));
            replace(override.sourceBindings(), SourceBindingSlot.CATEGORY_EVIDENCE,
                    category(categoryTarget, override.wikipediaCategoryRule()));
        }
    }

    private static SourceBinding primary(
            String owner, String path, FieldSourceMapping mapping) {
        return primary(SourceBindingTarget.fieldValue(
                owner, path, SourceBindingSlot.PRIMARY_FIELD_VALUE), mapping);
    }

    private static SourceBinding primary(
            SourceBindingTarget target, FieldSourceMapping mapping) {
        if (mapping == null) return null;
        // The stored binding IS the configuration; there is nothing to derive from.
        if (mapping.sourceType() == FieldSourceType.DATASOURCE) return null;
        if (mapping.sourceType() == FieldSourceType.WIKIDATA_SITELINK_COUNT) {
            return binding(target,
                    WikidataDatasourceProvider.ID, WikidataDatasourceProvider.SITELINK_COUNT,
                    Map.of());
        }
        if (mapping.sourceType().wikipediaArticleText()) {
            return binding(target, WikipediaDatasourceProvider.ID,
                    WikipediaDatasourceProvider.ARTICLE_INTRODUCTION,
                    Map.of(SOURCE_TYPE, mapping.sourceType().name(),
                            WikipediaDatasourceProvider.ARTICLE_TEXT_PROJECTION,
                            articleTextProjection(mapping.sourceType()).name()));
        }
        if (mapping.sourceType() == FieldSourceType.WIKIDATA_INCOMING_COUNT) {
            if (clean(mapping.propertyPid()).isBlank()) return null;
            return binding(target,
                    WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.INCOMING_RELATION_COUNT,
                    Map.of(PROPERTY, clean(mapping.propertyPid())));
        }
        if (mapping.sourceType() == FieldSourceType.WIKIDATA_INHERITED_INCOMING_COUNT) {
            if (clean(mapping.propertyPid()).isBlank()) return null;
            return binding(target,
                    WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.INHERITED_INCOMING_RELATION_COUNT,
                    Map.of(PROPERTY, clean(mapping.propertyPid())));
        }
        if (clean(mapping.propertyPid()).isBlank()) return null;
        ProviderOperation source = providerOperation(mapping.sourceType());
        if (source == null) return null;
        Map<String, String> parameters = new java.util.LinkedHashMap<>(Map.of(
                PROPERTY, clean(mapping.propertyPid()),
                LABEL, clean(mapping.propertyLabel()),
                VALUE_LANGUAGE, clean(mapping.valueLanguage()),
                SOURCE_TYPE, (mapping.sourceType() == null
                        ? FieldSourceType.SPARQL : mapping.sourceType()).name()));
        // Written only when set: an always-present blank pair would rewrite every
        // binding of every model on its next save, and with it the model's signature.
        wikidata.QualifierCondition condition = mapping.qualifierCondition();
        if (condition != null) {
            parameters.put(CONDITION_QUALIFIER, condition.qualifierPid());
            parameters.put(CONDITION_VALUES, String.join(" ", condition.allowedQids()));
        }
        return binding(target, source.provider(), source.operation(), parameters);
    }

    private static SourceBinding fallback(
            String owner, String path, FieldSourceMapping mapping) {
        return fallback(SourceBindingTarget.fieldValue(
                owner, path, SourceBindingSlot.FALLBACK_FIELD_VALUE), mapping);
    }

    private static SourceBinding fallback(
            SourceBindingTarget target, FieldSourceMapping mapping) {
        if (mapping == null || mapping.sourceType() == null
                || mapping.sourceType().usesPropertyInput()
                        && clean(mapping.propertyPid()).isBlank()) return null;
        ProviderOperation source = providerOperation(mapping.sourceType());
        if (source == null || WikidataDatasourceProvider.ID.equals(source.provider())) return null;
        return binding(target,
                source.provider(), source.operation(), Map.of(PROPERTY, clean(mapping.propertyPid()),
                        LABEL, clean(mapping.propertyLabel()),
                        SOURCE_TYPE, mapping.sourceType().name()));
    }

    private static SourceBinding category(
            String owner, String path, WikipediaCategoryRule rule) {
        return category(SourceBindingTarget.fieldValue(
                owner, path, SourceBindingSlot.CATEGORY_EVIDENCE), rule);
    }

    private static SourceBinding category(
            SourceBindingTarget target, WikipediaCategoryRule rule) {
        if (rule == null || !rule.configured()) return null;
        return binding(target,
                WikipediaDatasourceProvider.ID, WikipediaCategoryDiscoveryOperation.ID,
                rule.recipeParameters());
    }

    private static SourceBinding binding(String owner, String path, SourceBindingSlot slot,
            String provider, String operation, Map<String, String> parameters) {
        return binding(SourceBindingTarget.fieldValue(owner, path, slot), provider,
                operation, parameters);
    }

    private static SourceBinding binding(SourceBindingTarget target,
            String provider, String operation, Map<String, String> parameters) {
        return new SourceBinding(target,
                new SourceRecipe(provider, operation, parameters));
    }

    private static void replace(
            GeneratedFieldModel field, SourceBindingSlot slot, SourceBinding replacement) {
        replace(field.sourceBindings(), slot, replacement);
    }

    private static void replace(
            List<SourceBinding> bindings, SourceBindingSlot slot,
            SourceBinding replacement) {
        bindings.removeIf(binding -> binding.target().slot() == slot
                && (replacement != null || legacyProjected(binding.recipe())));
        if (replacement != null) bindings.add(replacement);
    }

    /**
     * The primary slot follows the mapping's source type. A registry operation without a
     * {@link FieldSourceType} of its own survives exactly while the type says
     * {@link FieldSourceType#DATASOURCE}; choosing any other type replaces it, so a field
     * never carries two primary sources.
     */
    private static void replacePrimary(List<SourceBinding> bindings,
            FieldSourceMapping mapping, SourceBinding replacement) {
        replace(bindings, SourceBindingSlot.PRIMARY_FIELD_VALUE, replacement);
        if (mapping == null || mapping.sourceType() != FieldSourceType.DATASOURCE) {
            bindings.removeIf(binding -> binding.target().slot()
                    == SourceBindingSlot.PRIMARY_FIELD_VALUE
                    && registryOnly(binding.recipe()));
        }
    }

    /** Whether no {@link FieldSourceType} produces this recipe, so only the registry
     *  describes it and the field editor offers it as a datasource operation. */
    public static boolean registryOnly(SourceRecipe recipe) {
        return recipe != null && SOURCE_TYPE_OPERATIONS.stream().noneMatch(operation ->
                operation.provider().equals(recipe.providerId())
                        && operation.operation().equals(recipe.operationId()));
    }

    /** A field that says "Datasource operation" is complete only with one chosen
     * registry operation in its primary slot. The editor, validator and execution plan
     * all ask this one rule. */
    public static List<ConfigurationProblem> datasourceConfigurationProblems(
            GeneratedProjectModel project) {
        List<ConfigurationProblem> problems = new ArrayList<>();
        visit(project, (owner, path, field) -> {
            checkDatasourceMapping(owner + "." + path, field.mapping(),
                    field.sourceBindings(), problems);
            for (OwnedFieldSource override : field.ownedFieldSources()) {
                if (override == null) continue;
                checkDatasourceMapping(field.entityClassName() + "."
                                + override.fieldPath() + " at " + owner + "." + path,
                        override.mapping(), override.sourceBindings(), problems);
            }
        });
        return List.copyOf(problems);
    }

    public static void requireCompleteDatasourceMappings(GeneratedProjectModel project) {
        List<ConfigurationProblem> problems = datasourceConfigurationProblems(project);
        if (problems.isEmpty()) return;
        ConfigurationProblem first = problems.getFirst();
        throw new IllegalArgumentException(first.location() + ": " + first.message());
    }

    private static void checkDatasourceMapping(String location, FieldSourceMapping mapping,
            List<SourceBinding> bindings, List<ConfigurationProblem> problems) {
        if (mapping == null || mapping.sourceType() != FieldSourceType.DATASOURCE) return;
        long chosen = bindings.stream().filter(binding -> binding != null
                        && binding.target().slot() == SourceBindingSlot.PRIMARY_FIELD_VALUE
                        && registryOnly(binding.recipe()))
                .count();
        if (chosen != 1) {
            problems.add(new ConfigurationProblem(location,
                    "Datasource operation is selected as the load method, but "
                            + (chosen == 0 ? "no operation is chosen."
                            : chosen + " operations are chosen; exactly one is required.")));
        }
    }

    public record ConfigurationProblem(String location, String message) { }

    private static final List<ProviderOperation> SOURCE_TYPE_OPERATIONS =
            sourceTypeCoverage().stream()
                    .filter(LegacySourceCoverage::registeredOperation)
                    .map(value -> new ProviderOperation(
                            value.providerId(), value.operationId()))
                    .distinct().toList();

    private static void projectLegacy(GeneratedFieldModel field, SourceBinding binding) {
        projectLegacy(field.mapping(), field::ensureFallbackMapping,
                field::ensureWikipediaCategoryRule, binding);
    }

    private static void projectLegacy(OwnedFieldSource field, SourceBinding binding) {
        projectLegacy(field.mapping(), field::ensureFallbackMapping,
                field::ensureWikipediaCategoryRule, binding);
    }

    private static void projectLegacy(FieldSourceMapping primary,
            java.util.function.Supplier<FieldSourceMapping> fallback,
            java.util.function.Supplier<WikipediaCategoryRule> category,
            SourceBinding binding) {
        SourceBindingSlot slot = binding.target().slot();
        SourceRecipe recipe = binding.recipe();
        if (slot == SourceBindingSlot.PRIMARY_FIELD_VALUE && registryOnly(recipe)) {
            primary.sourceType(FieldSourceType.DATASOURCE);
            primary.propertyPid("");
            primary.propertyLabel("");
            return;
        }
        // The category slot has its own legacy form, independent of any source type.
        if (slot == SourceBindingSlot.CATEGORY_EVIDENCE) {
            WikipediaCategoryRule projected = WikipediaCategoryRule.fromRecipe(binding);
            if (projected == null) return;
            category.get().assign(projected);
            return;
        }
        if (!legacyProjected(recipe)) return;
        if (slot == SourceBindingSlot.FALLBACK_FIELD_VALUE) {
            FieldSourceMapping mapping = fallback.get();
            mapping.sourceType(sourceType(recipe));
            mapping.propertyPid(recipe.parameter(PROPERTY));
            mapping.propertyLabel(recipe.parameter(LABEL));
        } else if (slot == SourceBindingSlot.PRIMARY_FIELD_VALUE) {
            primary.sourceType(sourceType(recipe));
            primary.propertyPid(recipe.parameter(PROPERTY));
            primary.propertyLabel(recipe.parameter(LABEL));
            // Old bindings predate this parameter; absence must not erase the
            // mapping-side value during migration. An explicit blank still clears it.
            if (recipe.parameters().containsKey(VALUE_LANGUAGE)) {
                primary.valueLanguage(recipe.parameter(VALUE_LANGUAGE));
            }
            // The qualifier condition is written only when set, so an absent pair says
            // nothing and leaves the mapping's own persisted value standing.
            if (recipe.parameters().containsKey(CONDITION_QUALIFIER)) {
                primary.qualifierCondition(wikidata.QualifierCondition.of(
                        recipe.parameter(CONDITION_QUALIFIER),
                        List.of(recipe.parameter(CONDITION_VALUES).trim()
                                .split("\\s+"))));
            }
        }
    }

    /** A binding of an operation that a legacy source type names is also shown in that
     *  type's legacy fields; the coverage table is the one list of those operations. */
    private static boolean legacyProjected(SourceRecipe recipe) {
        return recipe != null && !registryOnly(recipe);
    }

    /** The legacy type a binding was saved from: the coverage table read backwards.
     *  Several types may share one operation (the article-text projections); the
     *  stored type, then the recipe's projection, decides between them. */
    private static FieldSourceType sourceType(SourceRecipe recipe) {
        List<FieldSourceType> candidates = sourceTypeCoverage().stream()
                .filter(LegacySourceCoverage::registeredOperation)
                .filter(value -> value.providerId().equals(recipe.providerId())
                        && value.operationId().equals(recipe.operationId()))
                .map(LegacySourceCoverage::sourceType)
                .toList();
        if (candidates.size() == 1) return candidates.getFirst();
        try {
            FieldSourceType stored = FieldSourceType.valueOf(recipe.parameter(SOURCE_TYPE));
            if (candidates.contains(stored)) return stored;
        } catch (RuntimeException ignored) { }
        if (WikipediaDatasourceProvider.ID.equals(recipe.providerId())
                && WikipediaDatasourceProvider.ARTICLE_INTRODUCTION.equals(
                        recipe.operationId())) {
            return switch (articleTextProjection(recipe)) {
                case LEAD_PARAGRAPH -> FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH;
                case LEAD_SENTENCE -> FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_SENTENCE;
                case INTRODUCTION -> FieldSourceType.WIKIPEDIA_ARTICLE_INTRODUCTION;
            };
        }
        throw new IllegalStateException("No legacy source type for "
                + recipe.providerId() + "/" + recipe.operationId());
    }

    private static ProviderOperation providerOperation(FieldSourceType type) {
        LegacySourceCoverage coverage = coverage(type);
        return coverage.registeredOperation()
                ? new ProviderOperation(coverage.providerId(), coverage.operationId())
                : null;
    }

    private static WikipediaDatasourceProvider.ArticleTextProjection articleTextProjection(
            FieldSourceType type) {
        if (type == FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH) {
            return WikipediaDatasourceProvider.ArticleTextProjection.LEAD_PARAGRAPH;
        }
        if (type == FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_SENTENCE) {
            return WikipediaDatasourceProvider.ArticleTextProjection.LEAD_SENTENCE;
        }
        return WikipediaDatasourceProvider.ArticleTextProjection.INTRODUCTION;
    }

    private static WikipediaDatasourceProvider.ArticleTextProjection articleTextProjection(
            SourceRecipe recipe) {
        String value = recipe.parameter(WikipediaDatasourceProvider.ARTICLE_TEXT_PROJECTION);
        if (value == null || value.isBlank()) {
            return WikipediaDatasourceProvider.ArticleTextProjection.INTRODUCTION;
        }
        try {
            return WikipediaDatasourceProvider.ArticleTextProjection.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Unknown Wikipedia article-text projection: " + value, invalid);
        }
    }

    private record ProviderOperation(String provider, String operation) { }

    private interface Visitor {
        void accept(String owner, String path, GeneratedFieldModel field);
    }

    private static void visit(GeneratedProjectModel project, Visitor visitor) {
        if (project == null) return;
        for (GeneratedClassModel owner : project.classes()) {
            if (owner == null) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                visit(owner.className(), "", field, visitor);
            }
        }
    }

    private static void visit(String owner, String parent, GeneratedFieldModel field,
            Visitor visitor) {
        if (field == null) return;
        String path = parent.isBlank() ? field.name() : parent + "." + field.name();
        visitor.accept(owner, path, field);
        for (GeneratedFieldModel child : new ArrayList<>(field.fields())) {
            visit(owner, path, child, visitor);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
