package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import datasource.graph.GraphExpansionPolicy;

/**
 * Defines where the instances of a statement-reification class come from.
 *
 * <p>A statement class is identified by the Wikidata property whose statements
 * become its instances ({@link #isConfigured()} keys on the property, not on a
 * source class). The source class names the already-extracted members whose
 * statements are loaded.</p>
 *
 * <p>The model treats the source class as <i>structurally</i> optional — a blank
 * {@code sourceClassName} is meant to load statement subjects directly (see
 * {@link #discoversSubjectsDirectly()}). That direct-discovery load phase is NOT
 * yet implemented: generation and validation currently require a source class, so
 * a blank one is a configuration error today. The optionality is groundwork for
 * folding a backbone class into its statement class later; until the loader
 * exists, always configure a source class (e.g. {@code OscarNominations} for
 * {@code Nomination}'s {@code P1411}).</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class StatementClassSource {

    private String sourceClassName = "";
    private String propertyPid = "";

    // Optional: a VOCABULARY Selection whose values are the reify's value domain
    // (the allowed statement values + their labels), replacing the value filter
    // otherwise inherited from a source class. Blank = derive as before.
    private String valueSelectionName = "";
    // Explicit graph participation. Statement structure alone must not silently
    // turn a relation into an expandable knowledge-graph frontier.
    private GraphExpansionPolicy graphExpansionPolicy = GraphExpansionPolicy.NONE;

    public StatementClassSource() {
    }

    /**
     * Creates a direct statement source without a modeled source class.
     */
    public StatementClassSource(String propertyPid) {
        propertyPid(propertyPid);
    }

    /**
     * Creates a statement source optionally restricted to a modeled source class.
     * A blank {@code sourceClassName} means direct subject discovery.
     */
    public StatementClassSource(
            String sourceClassName,
            String propertyPid) {

        sourceClassName(sourceClassName);
        propertyPid(propertyPid);
    }

    /**
     * Optional modeled class whose loaded members restrict the statement subjects.
     * Blank means that matching subjects are discovered directly.
     */
    public String sourceClassName() {
        return sourceClassName;
    }

    public void sourceClassName(String value) {
        sourceClassName = clean(value);
    }

    /**
     * Wikidata property whose statements become instances of the statement class.
     */
    public String propertyPid() {
        return propertyPid;
    }

    public void propertyPid(String value) {
        propertyPid = clean(value);
    }

    /** A VOCABULARY Selection supplying the reify's value domain; blank = none. */
    public String valueSelectionName() {
        return valueSelectionName == null ? "" : valueSelectionName;
    }

    public void valueSelectionName(String value) {
        valueSelectionName = clean(value);
    }

    public boolean hasValueSelection() {
        return !valueSelectionName().isBlank();
    }

    public GraphExpansionPolicy graphExpansionPolicy() {
        return graphExpansionPolicy == null
                ? GraphExpansionPolicy.NONE : graphExpansionPolicy;
    }

    public void graphExpansionPolicy(GraphExpansionPolicy value) {
        graphExpansionPolicy = value == null ? GraphExpansionPolicy.NONE : value;
    }

    public boolean hasSourceClass() {
        return !sourceClassName.isBlank();
    }

    public boolean hasProperty() {
        return propertyPid.matches("(?i)P\\d+");
    }

    /**
     * A statement source is configured as soon as it has a valid statement
     * property. The source class is an optional restriction, not part of the
     * statement-class identity.
     */
    public boolean isConfigured() {
        return hasProperty();
    }

    /**
     * Whether the loader must discover statement subjects instead of reusing the
     * instances of a configured source class.
     */
    public boolean discoversSubjectsDirectly() {
        return isConfigured() && !hasSourceClass();
    }

    public StatementClassSource copy() {
        StatementClassSource c = new StatementClassSource(
                sourceClassName,
                propertyPid);
        c.valueSelectionName = valueSelectionName;
        c.graphExpansionPolicy = graphExpansionPolicy();
        return c;
    }

    @Override
    public String toString() {
        if (!hasProperty()) {
            return "(not configured)";
        }

        return hasSourceClass()
                ? sourceClassName + " / " + propertyPid
                : "direct / " + propertyPid;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
