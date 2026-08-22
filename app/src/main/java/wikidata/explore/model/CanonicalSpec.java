package wikidata.explore.model;

import java.util.ArrayList;
import java.util.List;

/**
 * How a class is canonicalized: how to derive its stable {@code identifier} and
 * its human {@code displayName}. These are rules ON the class, not stored
 * {@code name}/{@code qid} data fields — see docs/canonicalization-model.md.
 *
 * <ul>
 *   <li>{@link Kind#WIKIDATA_ENTITY}: identity = the entity's {@code qid};
 *       displayName = the Wikidata label (in {@link #labelLanguage()}).</li>
 *   <li>{@link Kind#DERIVED}: identity = the natural key ({@link #keyFields()},
 *       the reification grain); displayName = a single-valued {@link #displayNameField()}
 *       or a {@link #displayNameTemplate()} over fields.</li>
 * </ul>
 */
public class CanonicalSpec {

    public enum Kind { WIKIDATA_ENTITY, DERIVED }

    public enum DisplayNameMode {
        /** Wikidata label (entities only). */
        LABEL,
        /** A single-valued field's value (or a single reference's displayName). */
        FIELD,
        /** A template composing several fields, e.g. {@code "{nominee} · {year}"}. */
        TEMPLATE
    }

    private Kind kind = Kind.WIKIDATA_ENTITY;

    // Identity for DERIVED classes: the natural-key fields (the grain), in order.
    // Empty => surrogate. Ignored for WIKIDATA_ENTITY (identity is the qid).
    private final List<String> keyFields = new ArrayList<>();

    private DisplayNameMode displayNameMode = DisplayNameMode.LABEL;
    private String displayNameField = "";       // DisplayNameMode.FIELD
    private String displayNameTemplate = "";    // DisplayNameMode.TEMPLATE
    private String labelLanguage = "en";        // DisplayNameMode.LABEL

    // Which collection field marks the CANONICAL copy of a reified statement (#92).
    // Blank => fall back to structural inference; see primaryListField().
    private String primaryListField = "";

    public CanonicalSpec() {}

    public Kind kind() { return kind; }

    public CanonicalSpec kind(Kind kind) {
        this.kind = kind == null ? Kind.WIKIDATA_ENTITY : kind;
        return this;
    }

    public boolean isEntity() { return kind == Kind.WIKIDATA_ENTITY; }
    public boolean isDerived() { return kind == Kind.DERIVED; }

    /** The natural-key fields for a DERIVED class (mutable). */
    public List<String> keyFields() { return keyFields; }

    /**
     * Which collection field marks the canonical copy of a reified statement (#92).
     *
     * <p>Wikidata records a shared award on every recipient, so the same nomination
     * arrives once per endpoint. The copy that carries the full recipient LIST is the
     * complete one; the copies that carry only an inverse reference are denormalized
     * duplicates and are dropped. Which field that is used to be inferred — "the first
     * multi-valued entity qualifier" — so a class with two such qualifiers had the
     * answer decided by field order, silently. It belongs here, next to
     * {@link #keyFields()}: both are how the class decides which record is the real one.
     *
     * <p>Blank keeps the structural inference, which is what every model saved before
     * this declaration existed relies on.
     */
    public String primaryListField() { return primaryListField; }

    public CanonicalSpec primaryListField(String field) {
        this.primaryListField = field == null ? "" : field.trim();
        return this;
    }

    public DisplayNameMode displayNameMode() { return displayNameMode; }

    public CanonicalSpec displayNameMode(DisplayNameMode mode) {
        this.displayNameMode = mode == null ? DisplayNameMode.LABEL : mode;
        return this;
    }

    public String displayNameField() { return displayNameField; }

    public CanonicalSpec displayNameField(String field) {
        this.displayNameField = field == null ? "" : field.trim();
        return this;
    }

    public String displayNameTemplate() { return displayNameTemplate; }

    public CanonicalSpec displayNameTemplate(String template) {
        this.displayNameTemplate = template == null ? "" : template;
        return this;
    }

    public String labelLanguage() { return labelLanguage; }

    public CanonicalSpec labelLanguage(String language) {
        this.labelLanguage = language == null || language.isBlank()
                ? "en" : language.trim();
        return this;
    }

    public CanonicalSpec copy() {
        CanonicalSpec c = new CanonicalSpec();
        c.kind = kind;
        c.keyFields.addAll(keyFields);
        c.displayNameMode = displayNameMode;
        c.displayNameField = displayNameField;
        c.displayNameTemplate = displayNameTemplate;
        c.labelLanguage = labelLanguage;
        return c;
    }
}
