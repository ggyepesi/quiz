package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Defines where the instances of a statement-reification class come from.
 *
 * <p>For example, a {@code Nomination} class may reify the {@code P1411}
 * statements of every member of the {@code OscarNominations} source class.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class StatementClassSource {

    private String sourceClassName = "";
    private String propertyPid = "";

    public StatementClassSource() {
    }

    public StatementClassSource(String sourceClassName, String propertyPid) {
        sourceClassName(sourceClassName);
        propertyPid(propertyPid);
    }

    public String sourceClassName() {
        return sourceClassName;
    }

    public void sourceClassName(String value) {
        sourceClassName = value == null ? "" : value.trim();
    }

    public String propertyPid() {
        return propertyPid;
    }

    public void propertyPid(String value) {
        propertyPid = value == null ? "" : value.trim();
    }

    public boolean hasSourceClass() {
        return !sourceClassName.isBlank();
    }

    public boolean hasProperty() {
        return propertyPid.matches("(?i)P\\d+");
    }

    public boolean isConfigured() {
        return hasSourceClass() && hasProperty();
    }

    public StatementClassSource copy() {
        return new StatementClassSource(sourceClassName, propertyPid);
    }

    @Override
    public String toString() {
        if (!hasSourceClass()) {
            return "(not configured)";
        }
        return hasProperty()
                ? sourceClassName + " / " + propertyPid
                : sourceClassName;
    }
}
