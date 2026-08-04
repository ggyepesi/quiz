package quiz.curation;

import objectview.field.FieldKind;
import objectview.field.FieldRef;

/** Persisted schema enhancement applied before field-value corrections are rendered. */
public record FieldDeclaration(
        String type,
        String name,
        FieldKind kind,
        FieldKind valueKind,
        String typeLabel,
        boolean reference,
        boolean collection,
        String targetType,
        boolean structural,
        boolean minor,
        boolean inline,
        boolean annotatedReference) {

    public static FieldDeclaration from(String type, FieldRef field) {
        return new FieldDeclaration(type, field.name(), field.kind(), field.valueKind(),
                field.typeLabel(), field.reference(), field.collection(), field.targetType(),
                field.structural(), field.minor(), field.inline(), field.annotatedReference());
    }

    public FieldRef fieldRef() {
        return FieldRef.described(name, kind, valueKind, typeLabel,
                reference, collection, targetType, structural, minor,
                inline, false, "", annotatedReference);
    }
}
