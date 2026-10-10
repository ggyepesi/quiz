package wikidata.explore.model;

import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceRecipe;
import datasource.dbpedia.DbpediaDatasourceProvider;
import datasource.wikidata.WikidataDatasourceProvider;
import datasource.wikipedia.WikipediaCategoryDiscoveryOperation;
import datasource.wikipedia.WikipediaDatasourceProvider;

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
    public static final String PATTERN = "pattern";
    public static final String POLICY = "policy";

    private FieldSourceBindings() { }

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

    /** Replace one semantic slot and update the execution-compatible projection. */
    public static void put(GeneratedFieldModel field, SourceBinding binding) {
        if (field == null || binding == null) return;
        if (binding.target().scope() != datasource.api.BindingScope.FIELD_VALUE) {
            throw new IllegalArgumentException("A model field needs a field-value binding");
        }
        if (binding.target().contextual()) {
            throw new IllegalArgumentException(
                    "A contextual binding belongs to its owned-field source override");
        }
        field.sourceBindings().removeIf(existing -> existing.sameTarget(binding));
        field.sourceBindings().add(binding);
        projectLegacy(field, binding);
    }

    public static void put(OwnedFieldSource field, SourceBinding binding) {
        if (field == null || binding == null) return;
        if (binding.target().scope() != datasource.api.BindingScope.FIELD_VALUE
                || !binding.target().contextual()) {
            throw new IllegalArgumentException(
                    "An owned-field source needs a contextual field-value binding");
        }
        field.sourceBindings().removeIf(existing -> existing.sameTarget(binding));
        field.sourceBindings().add(binding);
        projectLegacy(field, binding);
    }

    public static SourceBinding binding(
            GeneratedFieldModel field, SourceBindingSlot slot) {
        if (field == null || slot == null) return null;
        return field.sourceBindings().stream()
                .filter(value -> value.target().slot() == slot)
                .findFirst().orElse(null);
    }

    private static void synchronize(
            String owner, String path, GeneratedFieldModel field) {
        replace(field, SourceBindingSlot.PRIMARY_FIELD_VALUE,
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
            replace(override.sourceBindings(), SourceBindingSlot.PRIMARY_FIELD_VALUE,
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
        if (mapping.sourceType() == FieldSourceType.WIKIDATA_SITELINK_COUNT) {
            return binding(target,
                    WikidataDatasourceProvider.ID, WikidataDatasourceProvider.SITELINK_COUNT,
                    Map.of());
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
        return binding(target,
                source.provider(), source.operation(),
                Map.of(PROPERTY, clean(mapping.propertyPid()),
                        LABEL, clean(mapping.propertyLabel()),
                        VALUE_LANGUAGE, clean(mapping.valueLanguage()),
                        SOURCE_TYPE, (mapping.sourceType() == null
                                ? FieldSourceType.SPARQL : mapping.sourceType()).name()));
    }

    private static SourceBinding fallback(
            String owner, String path, FieldSourceMapping mapping) {
        return fallback(SourceBindingTarget.fieldValue(
                owner, path, SourceBindingSlot.FALLBACK_FIELD_VALUE), mapping);
    }

    private static SourceBinding fallback(
            SourceBindingTarget target, FieldSourceMapping mapping) {
        if (mapping == null || clean(mapping.propertyPid()).isBlank()
                || mapping.sourceType() == null) return null;
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
        if (rule == null || clean(rule.pattern()).isBlank()) return null;
        return binding(target,
                WikipediaDatasourceProvider.ID, WikipediaCategoryDiscoveryOperation.ID,
                Map.of(PATTERN, clean(rule.pattern()), POLICY, rule.policy().name()));
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
        if (!legacyProjected(recipe)) return;
        if (slot == SourceBindingSlot.CATEGORY_EVIDENCE) {
            WikipediaCategoryRule rule = category.get();
            rule.pattern(recipe.parameter(PATTERN));
            try { rule.policy(CategoryCandidatePolicy.valueOf(recipe.parameter(POLICY))); }
            catch (RuntimeException ignored) { rule.policy(CategoryCandidatePolicy.REVIEW); }
            return;
        }
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
        }
    }

    private static boolean legacyProjected(SourceRecipe recipe) {
        if (recipe == null) return false;
        if (WikidataDatasourceProvider.ID.equals(recipe.providerId())) {
            return WikidataDatasourceProvider.PROPERTY_VALUE.equals(recipe.operationId());
        }
        return DbpediaDatasourceProvider.ID.equals(recipe.providerId())
                || WikipediaDatasourceProvider.ID.equals(recipe.providerId());
    }

    private static FieldSourceType sourceType(SourceRecipe recipe) {
        if (WikidataDatasourceProvider.ID.equals(recipe.providerId())) {
            try { return FieldSourceType.valueOf(recipe.parameter(SOURCE_TYPE)); }
            catch (RuntimeException ignored) { return FieldSourceType.SPARQL; }
        }
        if (DbpediaDatasourceProvider.ID.equals(recipe.providerId())) return FieldSourceType.DBPEDIA;
        if (WikipediaDatasourceProvider.ID.equals(recipe.providerId())
                && WikipediaDatasourceProvider.INFOBOX_PARAMETER.equals(recipe.operationId())) {
            return FieldSourceType.WIKIPEDIA_INFOBOX;
        }
        try { return FieldSourceType.valueOf(recipe.parameter(SOURCE_TYPE)); }
        catch (RuntimeException ignored) { return FieldSourceType.MANUAL; }
    }

    private static ProviderOperation providerOperation(FieldSourceType type) {
        if (type == FieldSourceType.DBPEDIA) {
            return new ProviderOperation(
                    DbpediaDatasourceProvider.ID, DbpediaDatasourceProvider.PROPERTY);
        }
        if (type == FieldSourceType.WIKIPEDIA_INFOBOX) {
            return new ProviderOperation(WikipediaDatasourceProvider.ID,
                    WikipediaDatasourceProvider.INFOBOX_PARAMETER);
        }
        if (type == FieldSourceType.WIKIDATA_SITELINK_COUNT) {
            return new ProviderOperation(WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.SITELINK_COUNT);
        }
        if (type == FieldSourceType.WIKIDATA_INCOMING_COUNT) {
            return new ProviderOperation(WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.INCOMING_RELATION_COUNT);
        }
        if (type == FieldSourceType.WIKIDATA_INHERITED_INCOMING_COUNT) {
            return new ProviderOperation(WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.INHERITED_INCOMING_RELATION_COUNT);
        }
        if (type == FieldSourceType.SPARQL || type == FieldSourceType.WIKIDATA_API
                || type == null) {
            return new ProviderOperation(WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.PROPERTY_VALUE);
        }
        return null;
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
