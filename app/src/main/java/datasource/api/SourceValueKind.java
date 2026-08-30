package datasource.api;

import datasource.schema.FieldType;

/**
 * Provider-neutral shape emitted by a datasource operation.
 *
 * <p>A third vocabulary for "what shape is this value", beside the model's
 * {@link FieldType} and objectview's {@code FieldKind}, and it earns that only because
 * sources distinguish things a model has no word for: a label that carries a language,
 * and a retrieved document. Everything else is one of the model's own types, so each
 * constant says which — and says so HERE, because the alternative is a conversion
 * written by hand at the first place a discovered value becomes a field, and a second
 * one written differently at the next.
 */
public enum SourceValueKind {

    TEXT(FieldType.STRING),
    /** Text carrying the language it is in — a Wikidata label. The model keeps no
     *  language on a value, so the language is lost when this becomes a field. */
    LANGUAGE_TEXT(FieldType.STRING),
    ENTITY_REFERENCE(FieldType.ENTITY),
    DATE_TIME(FieldType.DATE),
    QUANTITY(FieldType.NUMBER),
    MEDIA(FieldType.IMAGE),
    URL(FieldType.STRING),
    /** The source preserves its native scalar/reference shape and the configured model
     *  field decides the concrete type. */
    MODEL_VALUE(FieldType.AUTO),
    /** A retrieved source document — evidence, not a field value. */
    DOCUMENT(null),
    UNKNOWN(null);

    private final FieldType fieldType;

    SourceValueKind(FieldType fieldType) {
        this.fieldType = fieldType;
    }

    /** The model type a value of this shape becomes, or null when it does not become a
     *  field at all — which is a fact a binding needs before it is offered, not after. */
    public FieldType fieldType() {
        return fieldType;
    }

    /** Whether a value of this shape can be bound to a field. */
    public boolean bindableToField() {
        return fieldType != null;
    }
}
