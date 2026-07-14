package wikidata.explore.compiled;

import wikidata.explore.model.StatementClassSource;

/**
 * Immutable and class-name-resolved source of a statement class.
 */
public record CompiledStatementSource(
        String configuredSourceClassName,
        String sourceClassName,
        String propertyPid) {

    public CompiledStatementSource {
        configuredSourceClassName = clean(configuredSourceClassName);
        sourceClassName = clean(sourceClassName);
        propertyPid = clean(propertyPid);
    }

    public boolean configured() {
        return !sourceClassName.isBlank()
                && propertyPid.matches("(?i)P\\d+");
    }

    public static CompiledStatementSource from(
            StatementClassSource source,
            String resolvedClassName) {
        if (source == null) {
            return null;
        }
        return new CompiledStatementSource(
                source.sourceClassName(),
                resolvedClassName,
                source.propertyPid());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
