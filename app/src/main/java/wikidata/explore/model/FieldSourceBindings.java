package wikidata.explore.model;

import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceRecipe;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceRegistry;
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
        });
    }

    public static void synchronizeForSave(GeneratedProjectModel project) {
        visit(project, FieldSourceBindings::synchronize);
    }

    /**
     * Bank every editor's pending changes as bindings, then prove that each one still
     * names something this application can perform — before an operation starts work.
     *
     * <p>It writes: the synchronization is what lets a model edited through the old
     * field controls be checked as bindings at all, and doing it here is what makes the
     * check cover an edit made a moment ago. The name says so, because a caller reading
     * "resolve" would not expect its model to come back changed.
     *
     * <p>Nothing executes from the returned operations yet. The legacy field sources
     * still drive every run; this proves the bindings beside them are not nonsense —
     * that they name an installed provider, sit where they claim to sit, and produce
     * something a field can hold.
     */
    public static List<DatasourceOperation> synchronizeAndResolve(
            GeneratedProjectModel project, DatasourceRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("Datasource registry is required");
        synchronizeForSave(project);
        List<DatasourceOperation> resolved = new ArrayList<>(
                ClassSourceBindings.synchronizeAndResolve(project, registry));
        visit(project, (owner, path, field) -> {
            for (SourceBinding binding : field.sourceBindings()) {
                if (!owner.equals(binding.target().className())
                        || !path.equals(binding.target().fieldPath())) {
                    throw new IllegalArgumentException("Source binding target "
                            + binding.target().className() + "."
                            + binding.target().fieldPath() + " is stored on "
                            + owner + "." + path);
                }
                DatasourceOperation operation = binding.resolve(registry);
                if (!operation.outputSchema().kind().bindableToField()) {
                    throw new IllegalArgumentException("Datasource operation "
                            + binding.recipe().providerId() + "."
                            + binding.recipe().operationId()
                            + " does not produce a field value");
                }
                resolved.add(operation);
            }
        });
        return List.copyOf(resolved);
    }

    /** Replace one semantic slot and update the execution-compatible projection. */
    public static void put(GeneratedFieldModel field, SourceBinding binding) {
        if (field == null || binding == null) return;
        if (binding.target().scope() != datasource.api.BindingScope.FIELD_VALUE) {
            throw new IllegalArgumentException("A model field needs a field-value binding");
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

    private static SourceBinding primary(
            String owner, String path, FieldSourceMapping mapping) {
        if (mapping == null || clean(mapping.propertyPid()).isBlank()) return null;
        ProviderOperation source = providerOperation(mapping.sourceType());
        if (source == null) return null;
        return binding(owner, path, SourceBindingSlot.PRIMARY_FIELD_VALUE,
                source.provider(), source.operation(),
                Map.of(PROPERTY, clean(mapping.propertyPid()),
                        LABEL, clean(mapping.propertyLabel()),
                        SOURCE_TYPE, (mapping.sourceType() == null
                                ? FieldSourceType.SPARQL : mapping.sourceType()).name()));
    }

    private static SourceBinding fallback(
            String owner, String path, FieldSourceMapping mapping) {
        if (mapping == null || clean(mapping.propertyPid()).isBlank()
                || mapping.sourceType() == null) return null;
        ProviderOperation source = providerOperation(mapping.sourceType());
        if (source == null || WikidataDatasourceProvider.ID.equals(source.provider())) return null;
        return binding(owner, path, SourceBindingSlot.FALLBACK_FIELD_VALUE,
                source.provider(), source.operation(), Map.of(PROPERTY, clean(mapping.propertyPid()),
                        LABEL, clean(mapping.propertyLabel()),
                        SOURCE_TYPE, mapping.sourceType().name()));
    }

    private static SourceBinding category(
            String owner, String path, WikipediaCategoryRule rule) {
        if (rule == null || clean(rule.pattern()).isBlank()) return null;
        return binding(owner, path, SourceBindingSlot.CATEGORY_EVIDENCE,
                WikipediaDatasourceProvider.ID, WikipediaCategoryDiscoveryOperation.ID,
                Map.of(PATTERN, clean(rule.pattern()), POLICY, rule.policy().name()));
    }

    private static SourceBinding binding(String owner, String path, SourceBindingSlot slot,
            String provider, String operation, Map<String, String> parameters) {
        return new SourceBinding(SourceBindingTarget.fieldValue(owner, path, slot),
                new SourceRecipe(provider, operation, parameters));
    }

    private static void replace(
            GeneratedFieldModel field, SourceBindingSlot slot, SourceBinding replacement) {
        field.sourceBindings().removeIf(binding -> binding.target().slot() == slot);
        if (replacement != null) field.sourceBindings().add(replacement);
    }

    private static void projectLegacy(GeneratedFieldModel field, SourceBinding binding) {
        SourceBindingSlot slot = binding.target().slot();
        SourceRecipe recipe = binding.recipe();
        if (slot == SourceBindingSlot.CATEGORY_EVIDENCE) {
            WikipediaCategoryRule rule = field.ensureWikipediaCategoryRule();
            rule.pattern(recipe.parameter(PATTERN));
            try { rule.policy(CategoryCandidatePolicy.valueOf(recipe.parameter(POLICY))); }
            catch (RuntimeException ignored) { rule.policy(CategoryCandidatePolicy.REVIEW); }
            return;
        }
        if (slot == SourceBindingSlot.FALLBACK_FIELD_VALUE) {
            FieldSourceMapping mapping = field.ensureFallbackMapping();
            mapping.sourceType(sourceType(recipe));
            mapping.propertyPid(recipe.parameter(PROPERTY));
            mapping.propertyLabel(recipe.parameter(LABEL));
        } else if (slot == SourceBindingSlot.PRIMARY_FIELD_VALUE) {
            field.mapping().sourceType(sourceType(recipe));
            field.mapping().propertyPid(recipe.parameter(PROPERTY));
            field.mapping().propertyLabel(recipe.parameter(LABEL));
        }
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
