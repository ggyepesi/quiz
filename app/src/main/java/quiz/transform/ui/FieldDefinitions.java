package quiz.transform.ui;

import objectview.field.FieldKind;
import objectview.field.FieldRef;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldDefinition;
import wikidata.explore.model.FieldRenderMode;
import wikidata.explore.model.FieldType;

/** Adapts the shared producer-neutral field definition to the runtime schema. */
public final class FieldDefinitions {
    private FieldDefinitions() {}

    public static FieldRef toFieldRef(FieldDefinition definition) {
        if (definition == null) return null;
        boolean collection = definition.cardinality() == FieldCardinality.COLLECTION;
        boolean reference = definition.type() == FieldType.ENTITY;
        FieldKind valueKind = switch (definition.type()) {
            case BOOLEAN -> FieldKind.BOOLEAN;
            case NUMBER, DATE -> FieldKind.ORDERED;
            case STRING, TEXT -> FieldKind.TEXT;
            case IMAGE -> FieldKind.MEDIA;
            case ENTITY -> FieldKind.REFERENCE;
            case AUTO -> FieldKind.UNKNOWN;
        };
        FieldKind kind = collection ? FieldKind.COLLECTION
                : reference ? FieldKind.REFERENCE : valueKind;
        String scalarLabel = switch (definition.type()) {
            case BOOLEAN -> "Boolean";
            case NUMBER -> "Number";
            case DATE -> "Date";
            case STRING -> "String";
            case TEXT -> "Text";
            case IMAGE -> "ImagePane";
            case ENTITY -> definition.entityClassName().isBlank()
                    ? "Viewable" : definition.entityClassName();
            case AUTO -> "Object";
        };
        String label = collection ? "Collection<" + scalarLabel + ">" : scalarLabel;
        return FieldRef.described(definition.name(), kind, valueKind, label,
                reference, collection,
                reference ? definition.entityClassName() : null,
                false, false,
                definition.renderMode() == FieldRenderMode.INLINE,
                false, "", false,
                definition.renderMode() == FieldRenderMode.REFERENCE);
    }

    public static FieldDefinition fromFieldRef(FieldRef field) {
        if (field == null) return null;
        FieldType type = field.reference() ? FieldType.ENTITY
                : switch (field.valueKind()) {
                    case BOOLEAN -> FieldType.BOOLEAN;
                    case ORDERED -> orderedType(field.typeLabel());
                    case TEXT -> textType(field.typeLabel());
                    case MEDIA -> FieldType.IMAGE;
                    case REFERENCE -> FieldType.ENTITY;
                    case COLLECTION, UNKNOWN -> FieldType.AUTO;
                };
        FieldCardinality cardinality = field.collection()
                ? FieldCardinality.COLLECTION : FieldCardinality.SINGLE;
        FieldRenderMode render = field.inline() ? FieldRenderMode.INLINE
                : field.annotatedReference() || field.reference()
                ? FieldRenderMode.REFERENCE : FieldRenderMode.AUTO;
        return new FieldDefinition(field.name(), type,
                field.targetType(), cardinality, render);
    }

    private static FieldType orderedType(String label) {
        String value = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        return value.contains("date") || value.contains("year")
                ? FieldType.DATE : FieldType.NUMBER;
    }

    private static FieldType textType(String label) {
        return label != null && label.toLowerCase(java.util.Locale.ROOT).contains("text")
                ? FieldType.TEXT : FieldType.STRING;
    }
}
