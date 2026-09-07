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

/** Datasource-declared instance fields implied by a class's stored source config. */
public final class ConfiguredInstanceFields {
    private ConfiguredInstanceFields() { }

    public record Field(String providerId, DatasourceInstanceField declaration) { }

    public static objectview.field.FieldRef toFieldRef(
            DatasourceInstanceField declaration) {
        datasource.api.SourceValueSchema schema = declaration.valueSchema();
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
                declaration.name(), declaration.label(),
                objectview.field.FieldRole.PROVENANCE,
                kind, valueKind, typeLabel, reference, schema.collection(),
                reference ? "Viewable" : null, false, false,
                false, false, "", reference);
    }

    public static List<Field> of(
            GeneratedClassModel model,
            GeneratedProjectModel project,
            DatasourceRegistry registry) {
        if (model == null || registry == null) return List.of();
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        collectProviders(model, project, providers, new LinkedHashSet<>());
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        for (String providerId : providers) {
            DatasourceProvider provider = registry.provider(providerId).orElseThrow(
                    () -> new IllegalStateException(
                            "No datasource provider '" + providerId + "'"));
            for (DatasourceInstanceField declaration : provider.instanceFields()) {
                if (declaration == null || declaration.name() == null
                        || declaration.name().isBlank()) continue;
                Field configured = new Field(providerId, declaration);
                Field previous = fields.putIfAbsent(declaration.name(), configured);
                if (previous != null && !previous.providerId().equals(providerId)) {
                    throw new IllegalArgumentException(
                            "Datasource instance field '" + declaration.name()
                                    + "' is declared by both "
                                    + previous.providerId() + " and " + providerId);
                }
            }
        }
        return List.copyOf(fields.values());
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
