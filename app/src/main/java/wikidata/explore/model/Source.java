package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A named SELECTION over the shared entity pool — a rule that picks a set of
 * Wikidata entities, materialized (and cached) for a production to reference. It
 * is never a served product; it only shapes how a product's fields are loaded or
 * constrained. The counterpart to a product {@link GeneratedClassModel}.
 *
 * <p>This replaces the config-classes that overloaded "class" with non-product
 * roles. The entity pool is already the cache (one instance per QID); a Source is
 * just a named, rule-defined subset of it. Two live roles (facets are a view-time
 * concern and are deliberately not modelled here; a partition that changes what is
 * loaded is a subclass, using the existing discriminator machinery):
 *
 * <ul>
 *   <li>{@link Kind#VOCABULARY} — a value domain: the allowed values of a field,
 *       given explicitly or by a P31 type filter (e.g. the Oscar categories);</li>
 *   <li>{@link Kind#POPULATION} — a subject set defined by a membership relation
 *       (e.g. the entities with P1411 into those categories) that a reify draws
 *       its subjects from.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Source {

    public enum Kind {
        /** A value domain: allowed field values (explicit QIDs and/or a P31 type). */
        VOCABULARY,
        /** A subject set: entities matching a membership relation, for a reify. */
        POPULATION
    }

    private String name = "";
    private Kind kind = Kind.VOCABULARY;

    // VOCABULARY: the allowed values, given explicitly and/or by a P31 type filter.
    private final List<String> valueQids = new ArrayList<>();
    private String valueTypeQid = "";

    // POPULATION: subjects are the entities with `relationPid` into `targetQids`
    // (e.g. P1411 into the Oscar categories). Empty targetQids = the relation alone.
    private String relationPid = "";
    private final List<String> targetQids = new ArrayList<>();

    public Source() {
    }

    public Source(String name, Kind kind) {
        name(name);
        kind(kind);
    }

    public String name() {
        return name == null ? "" : name;
    }

    public void name(String value) {
        name = value == null ? "" : value.trim();
    }

    public Kind kind() {
        return kind == null ? Kind.VOCABULARY : kind;
    }

    public void kind(Kind value) {
        kind = value == null ? Kind.VOCABULARY : value;
    }

    public List<String> valueQids() {
        return valueQids;
    }

    public void valueQids(List<String> values) {
        valueQids.clear();
        addQids(valueQids, values);
    }

    public String valueTypeQid() {
        return valueTypeQid == null ? "" : valueTypeQid;
    }

    public void valueTypeQid(String value) {
        valueTypeQid = value == null ? "" : value.trim();
    }

    public boolean hasValueType() {
        return valueTypeQid().matches("(?i)Q\\d+");
    }

    public String relationPid() {
        return relationPid == null ? "" : relationPid;
    }

    public void relationPid(String value) {
        relationPid = value == null ? "" : value.trim();
    }

    public List<String> targetQids() {
        return targetQids;
    }

    public void targetQids(List<String> values) {
        targetQids.clear();
        addQids(targetQids, values);
    }

    /** A Source is configured once its rule can select something. */
    public boolean isConfigured() {
        if (name().isBlank()) {
            return false;
        }
        return switch (kind()) {
            case VOCABULARY -> !valueQids.isEmpty() || hasValueType();
            case POPULATION -> relationPid().matches("(?i)P\\d+");
        };
    }

    public Source copy() {
        Source c = new Source(name, kind);
        c.valueTypeQid = valueTypeQid;
        c.valueQids.addAll(valueQids);
        c.relationPid = relationPid;
        c.targetQids.addAll(targetQids);
        return c;
    }

    private static void addQids(List<String> into, List<String> values) {
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
