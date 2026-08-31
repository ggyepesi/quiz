package wikidata.explore.compiled;

import wikidata.explore.model.StatementClassSource;

/**
 * Immutable and class-name-resolved source of a statement class.
 */
public record CompiledStatementSource(
        String configuredSourceClassName,
        String sourceClassId,
        String sourceClassName,
        String propertyPid,
        String valueField,
        String valueSelectionId,
        String valueSelectionName) {

    public CompiledStatementSource {
        configuredSourceClassName = clean(configuredSourceClassName);
        sourceClassId = clean(sourceClassId);
        sourceClassName = clean(sourceClassName);
        propertyPid = clean(propertyPid);
        valueField = clean(valueField);
        valueSelectionId = clean(valueSelectionId);
        valueSelectionName = clean(valueSelectionName);
    }

    public boolean hasValueSelection() {
        return !valueSelectionName.isBlank();
    }

    /** A statement class is defined by its PROPERTY; the source class is optional
     *  (blank => the reify discovers its subjects). Matches the editable model's
     *  StatementClassSource.isConfigured(). */
    public boolean configured() {
        return propertyPid.matches("(?i)P\\d+");
    }

    public boolean hasSourceClass() {
        return !sourceClassName.isBlank();
    }

    /**
     * @param valueField the field that plays the value role, resolved ONCE at compile
     *                   from the explicit value role
     *                   ({@code StatementFieldSemantics.statementValueFieldName}), so
     *                   the reify reads it here instead of re-deriving it. Blank when
     *                   the class has no value field (a validation warning).
     */
    public static CompiledStatementSource from(
            StatementClassSource source,
            String resolvedClassName,
            String valueField) {
        if (source == null) {
            return null;
        }
        return new CompiledStatementSource(
                source.sourceClassName(),
                source.sourceClassId(),
                resolvedClassName,
                source.propertyPid(),
                valueField,
                source.valueSelectionId(),
                source.valueSelectionName());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
