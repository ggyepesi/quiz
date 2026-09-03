package wikidata.explore.model;

import wikidata.LabelledId;

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
    private String sourceClassId = "";
    private String propertyPid = "";
    // The property's own name, remembered when it is chosen, exactly as a field
    // mapping remembers it. Without this a statement class can only ever explain
    // itself as "P39", and the catalogue that knows the name is a workbench concern
    // this package must not reach into.
    private String propertyLabel = "";

    // Optional: a VOCABULARY Selection whose values are the reify's value domain
    // (the allowed statement values + their labels), replacing the value filter
    // Which entities may be the OBJECT. One storage: the valueSelection accessors
    // below are VIEWS onto it, kept because a vocabulary reference is rebound by name
    // and id from several places and because saved models are written that way. They
    // read and write this field, so there is one place the object's bound lives.
    private EntityBound objectBound = EntityBound.unbounded();
    // Explicit graph participation. Statement structure alone must not silently
    // turn a relation into an expandable knowledge-graph frontier.
    // Which entities may be the SUBJECT of these statements. Absent until now: the
    // object end could be bounded by QIDs while the subject end could only be pointed
    // at another class, so the two ends of one triple were not the same question. A
    // subject bound is also the missing half of R16 — pinning both sides of the join
    // is what makes discovery deterministic.
    private EntityBound subjectBound = EntityBound.unbounded();

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
        sourceClassId = "";
    }
    public String sourceClassId() { return DeclarationIds.clean(sourceClassId); }
    public void sourceClassId(String value) { sourceClassId = DeclarationIds.clean(value); }
    void sourceClassReference(String id, String name) {
        sourceClassId = DeclarationIds.clean(id);
        sourceClassName = clean(name);
    }

    /**
     * Wikidata property whose statements become instances of the statement class.
     */
    public String propertyLabel() {
        return propertyLabel == null ? "" : propertyLabel;
    }

    public void propertyLabel(String value) {
        propertyLabel = clean(value);
    }

    /** "position held (P39)" when the name is known, otherwise just the PID. */
    public String describeProperty() {
        String pid = propertyPid();
        if (pid.isBlank()) return "";
        return LabelledId.display(propertyLabel(), pid);
    }

    public String propertyPid() {
        return propertyPid;
    }

    public void propertyPid(String value) {
        propertyPid = clean(value);
    }

    /** A VOCABULARY Selection supplying the reify's value domain; blank = none. */
    /** Which entities may be the object; unbounded unless the modeller says. */
    public EntityBound objectBound() {
        return objectBound == null ? EntityBound.unbounded() : objectBound;
    }

    public void objectBound(EntityBound value) {
        objectBound = value == null ? EntityBound.unbounded() : value;
    }

    // A view onto objectBound, not a second field. Reading it stays as it was for the
    // callers that ask by name; writing it sets a VOCABULARY bound. A saved model that
    // carries valueSelectionName therefore loads straight into the bound, which is the
    // migration — no translation layer, because the accessor IS the translation.
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String valueSelectionName() {
        return objectBound().kind() == EntityBound.Kind.VOCABULARY
                ? objectBound().selectionName() : "";
    }

    @com.fasterxml.jackson.annotation.JsonProperty("valueSelectionName")
    public void valueSelectionName(String value) {
        objectBound(EntityBound.vocabulary(value));
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String valueSelectionId() {
        return objectBound().kind() == EntityBound.Kind.VOCABULARY
                ? objectBound().selectionId() : "";
    }

    @com.fasterxml.jackson.annotation.JsonProperty("valueSelectionId")
    public void valueSelectionId(String value) {
        if (objectBound().kind() != EntityBound.Kind.VOCABULARY) return;
        objectBound(EntityBound.vocabulary(objectBound().selectionName(), value));
    }
    /** Rebinds the object's vocabulary after the Selection it names was renamed. */
    void valueSelectionReference(String id, String name) {
        objectBound(objectBound().rebound(id, name));
    }

    /** Rebinds the subject's vocabulary the same way — a rename must reach both ends,
     *  or renaming a Selection would keep bounding one and quietly stop bounding the
     *  other. */
    void subjectSelectionReference(String id, String name) {
        subjectBound(subjectBound().rebound(id, name));
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

    /** Which entities may be the subject; unbounded unless the modeller says. */
    public EntityBound subjectBound() {
        return subjectBound == null ? EntityBound.unbounded() : subjectBound;
    }

    public void subjectBound(EntityBound value) {
        subjectBound = value == null ? EntityBound.unbounded() : value;
    }

    /** Whether either end of the triple is bounded — what makes discovery safe to
     *  run at all, since an unbounded join on both sides scans Wikidata. */
    public boolean hasBoundedEnd(boolean objectBounded) {
        return objectBounded || subjectBound().bounded();
    }

    public StatementClassSource copy() {
        StatementClassSource c = new StatementClassSource(
                sourceClassName,
                propertyPid);
        c.sourceClassId = sourceClassId;
        c.propertyLabel = propertyLabel;
        c.graphExpansionPolicy = graphExpansionPolicy();
        c.subjectBound = subjectBound();
        c.objectBound = objectBound();
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
