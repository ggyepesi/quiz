package wikidata.explore.model;

import datasource.api.DatasourceInstanceField;
import datasource.api.DatasourceProvider;
import datasource.api.DatasourceRegistry;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.wikidata.WikidataDatasourceProvider;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Non-authored datasource instance fields implied by a class's stored configuration.
 *
 * <p>Model fields, including structural derived fields, belong to the class's real
 * field list. This separate path is only for fields a configured datasource declares
 * on every instance, such as provenance.</p>
 */
public final class ConfiguredInstanceFields {
    private ConfiguredInstanceFields() { }

    public record Field(
            String origin,
            String name,
            String label,
            datasource.api.SourceValueSchema valueSchema,
            objectview.field.FieldRole role,
            Function<Object, Object> reader) {

        public Field {
            origin = origin == null ? "" : origin;
            name = name == null ? "" : name;
            label = label == null ? "" : label;
            valueSchema = java.util.Objects.requireNonNull(valueSchema, "valueSchema");
            role = role == null ? objectview.field.FieldRole.NONE : role;
            reader = java.util.Objects.requireNonNull(reader, "reader");
        }

        public Object value(Object source) {
            return reader.apply(source);
        }
    }

    public static objectview.field.FieldRef toFieldRef(Field field) {
        datasource.api.SourceValueSchema schema = field.valueSchema();
        boolean reference = schema.kind()
                == datasource.api.SourceValueKind.ENTITY_REFERENCE;
        objectview.field.FieldKind valueKind = switch (schema.kind()) {
            case ENTITY_REFERENCE -> objectview.field.FieldKind.REFERENCE;
            case QUANTITY, DATE_TIME -> objectview.field.FieldKind.ORDERED;
            case MEDIA -> objectview.field.FieldKind.MEDIA;
            case TEXT, LANGUAGE_TEXT, URL, MODEL_VALUE, DOCUMENT, UNKNOWN
                    -> objectview.field.FieldKind.TEXT;
        };
        objectview.field.FieldKind kind = schema.collection()
                ? objectview.field.FieldKind.COLLECTION : valueKind;
        String typeLabel = (schema.collection() ? "List<" : "")
                + (reference ? "Viewable" : switch (schema.kind()) {
                    case QUANTITY -> "Quantity";
                    case MEDIA -> "MediaValue";
                    default -> "String";
                }) + (schema.collection() ? ">" : "");
        return objectview.field.FieldRef.described(
                field.name(), field.label(), field.role(),
                kind, valueKind, typeLabel, reference, schema.collection(),
                reference ? "Viewable" : null, false, false,
                false, false, "", reference);
    }

    public static List<Field> of(
            GeneratedClassModel model,
            GeneratedProjectModel project,
            DatasourceRegistry registry) {
        if (model == null) return List.of();
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        if (registry != null) {
            collectProviders(model, project, providers, new LinkedHashSet<>());
        }
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        for (String providerId : providers) {
            DatasourceProvider provider = registry.provider(providerId).orElseThrow(
                    () -> new IllegalStateException(
                            "No datasource provider '" + providerId + "'"));
            for (DatasourceInstanceField declaration : provider.instanceFields()) {
                if (declaration == null || declaration.name() == null
                        || declaration.name().isBlank()) continue;
                Field configured = datasourceField(providerId, declaration);
                Field previous = fields.putIfAbsent(declaration.name(), configured);
                if (previous != null && !previous.origin().equals(providerId)) {
                    throw new IllegalArgumentException(
                            "Datasource instance field '" + declaration.name()
                                    + "' is declared by both "
                                    + previous.origin() + " and " + providerId);
                }
            }
        }
        if (registry != null) {
            collectConfiguredFields(model, project, registry, fields,
                    new LinkedHashSet<>());
        }
        return List.copyOf(fields.values());
    }

    private static Field datasourceField(
            String providerId, DatasourceInstanceField declaration) {
        return new Field(providerId, declaration.name(), declaration.label(),
                declaration.valueSchema(), objectview.field.FieldRole.PROVENANCE,
                declaration::value);
    }

    private static void collectConfiguredFields(
            GeneratedClassModel model,
            GeneratedProjectModel project,
            DatasourceRegistry registry,
            LinkedHashMap<String, Field> fields,
            Set<String> seen) {
        if (model == null || !seen.add(model.className())) return;
        for (SourceBinding binding : model.sourceBindings()) {
            DatasourceProvider provider = registry.provider(
                    binding.recipe().providerId()).orElseThrow(() ->
                    new IllegalStateException("No datasource provider '"
                            + binding.recipe().providerId() + "'"));
            for (DatasourceInstanceField declaration : provider.instanceFields(binding)) {
                put(fields, datasourceField(binding.recipe().providerId(), declaration));
            }
        }
        if (project != null && model.hasBase()) {
            collectConfiguredFields(project.findClass(model.baseClassName()), project,
                    registry, fields, seen);
        }
    }

    private static void put(LinkedHashMap<String, Field> fields, Field configured) {
        if (configured == null || configured.name().isBlank()) return;
        Field previous = fields.putIfAbsent(configured.name(), configured);
        if (previous != null && !previous.origin().equals(configured.origin())) {
            throw new IllegalArgumentException(
                    "Configured instance field '" + configured.name()
                            + "' is declared by both " + previous.origin()
                            + " and " + configured.origin());
        }
    }

    private static void collectProviders(
            GeneratedClassModel model,
            GeneratedProjectModel project,
            Set<String> result,
            Set<String> seen) {
        if (model == null || !seen.add(model.className())) return;
        switch (model.classKind()) {
            case SOURCE -> {
                SourceBinding identity = ClassSourceBindings.binding(
                        model, SourceBindingSlot.CLASS_IDENTITY);
                // A source class without materialized bindings is the legacy Wikidata
                // configuration; synchronization persists that choice on the next save.
                result.add(identity == null
                        ? WikidataDatasourceProvider.ID
                        : identity.recipe().providerId());
            }
            case STATEMENT -> {
                // StatementClassSource is currently the Wikidata statement declaration.
                // When it becomes provider-neutral, that declaration will carry this id.
                if (model.statementSource() != null) {
                    result.add(WikidataDatasourceProvider.ID);
                }
            }
            case AGGREGATE -> {
                if (project != null && model.aggregateSource() != null) {
                    collectProviders(project.findClass(
                                    model.aggregateSource().sourceClassName()),
                            project, result, seen);
                }
            }
            case OWNED -> {
                if (project == null) break;
                for (GeneratedClassModel owner : project.classes()) {
                    if (owner == null) continue;
                    boolean produces = owner.effectiveFields(project).stream()
                            .filter(java.util.Objects::nonNull)
                            .anyMatch(field -> field.mapping().productionKind()
                                    == FieldProductionKind.OWNED_COMPONENT
                                    && model.className().equals(field.entityClassName()));
                    if (produces) collectProviders(owner, project, result, seen);
                }
            }
        }
    }
}
