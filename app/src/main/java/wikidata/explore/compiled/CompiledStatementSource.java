package wikidata.explore.compiled;

import wikidata.explore.model.StatementClassSource;

/**
 * Immutable and class-name-resolved source of a statement class.
 */
public record CompiledStatementSource(
        String configuredSourceClassName,
        String sourceClassName,
        String propertyPid,
        String valueField,
        String valueSelectionName) {

    public CompiledStatementSource {
        configuredSourceClassName = clean(configuredSourceClassName);
        sourceClassName = clean(sourceClassName);
        propertyPid = clean(propertyPid);
        valueField = clean(valueField);
        valueSelectionName = clean(valueSelectionName);
    }

    public boolean hasValueSelection() {
        return !valueSelectionName.isBlank();
    }

    public boolean configured() {
        return !sourceClassName.isBlank()
                && propertyPid.matches("(?i)P\\d+");
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
                resolvedClassName,
                source.propertyPid(),
                valueField,
                source.valueSelectionName());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
