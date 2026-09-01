package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A named SELECTION over the shared entity pool — a rule that picks a set of
 * Wikidata entities, materialized (and cached) for a production to reference. It
 * is never a served product; it only shapes how a product's fields are loaded or
 * constrained. The counterpart to a product {@link GeneratedClassModel}.
 *
 * <p>This replaces the config-classes that overloaded "class" with non-product
 * roles. The entity pool is already the cache (one instance per QID); a Selection is
 * just a named, rule-defined subset of it. This base type carries only the identity
 * (name + kind); the data fields live on the concrete subtypes so each role is
 * self-contained:
 *
 * <ul>
 *   <li>{@link VocabularySelection} ({@link Kind#VOCABULARY}) — a value domain: the
 *       allowed values of a field, given explicitly or by a P31 type filter (e.g. the
 *       Oscar categories);</li>
 *   <li>{@link PopulationSelection} ({@link Kind#POPULATION}) — a subject set defined
 *       by a membership relation (e.g. the entities with P1411 into those categories)
 *       that a reify draws its subjects from;</li>
 *   <li>{@link RoleSelection} ({@link Kind#ROLE}) — the canonical entity values reached
 *       through a field of a produced class (e.g. Nomination.nominee).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Selection {

    public enum Kind {
        /** A value domain: allowed field values (explicit QIDs and/or a P31 type). */
        VOCABULARY,
        /** A subject set: entities matching a membership relation, for a reify. */
        POPULATION,
        /** Entity values occupying a field role in generated records. */
        ROLE
    }

    private String name = "";
    private String declarationId = "";
    private Kind kind = Kind.VOCABULARY;
    private String importedFrom = "";

    public Selection() {
    }

    public Selection(String name, Kind kind) {
        declarationId = DeclarationIds.create();
        name(name);
        kind(kind);
    }

    public String name() {
        return name == null ? "" : name;
    }

    public String declarationId() { return DeclarationIds.clean(declarationId); }
    public void declarationId(String value) { declarationId = DeclarationIds.clean(value); }
    void ensureDeclarationId(String projectName) {
        if (declarationId().isBlank()) {
            declarationId = DeclarationIds.legacy(projectName, "selection", name());
        }
    }
    protected void copyIdentityTo(Selection target) {
        target.declarationId = declarationId;
        target.importedFrom = importedFrom;
    }

    public String importedFrom() { return importedFrom == null ? "" : importedFrom; }
    public void importedFrom(String value) {
        importedFrom = value == null ? "" : value.trim();
    }
    public boolean isImported() { return !importedFrom().isBlank(); }

    public void name(String value) {
        name = value == null ? "" : value.trim();
    }

    public Kind kind() {
        return kind == null ? Kind.VOCABULARY : kind;
    }

    public void kind(Kind value) {
        kind = value == null ? Kind.VOCABULARY : value;
    }

    /** A Selection is configured once its rule can select something. The base type
     *  carries no rule, so it is never configured; subtypes override. */
    public boolean isConfigured() {
        return false;
    }

    /** A base copy carrying only identity; subtypes override to copy their data. */
    public Selection copy() {
        Selection copy = new Selection(name(), kind());
        copyIdentityTo(copy);
        return copy;
    }

    /** Appends the QID-shaped, trimmed entries of {@code values} to {@code into}
     *  (skipping nulls/non-QIDs). Shared by the concrete selections' setters. */
    protected static void addQids(List<String> into, List<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (v != null && v.trim().matches("(?i)Q\\d+")) {
                into.add(v.trim());
            }
        }
    }
}
