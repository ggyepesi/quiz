package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A named, reusable configuration of a SET of entities or values that productions
 * consume and fields reference, but which is NEVER served as a product in its own
 * right. The counterpart to a product {@link GeneratedClassModel}: a class defines
 * an entity you serve; a Source defines where a field's values — or a production's
 * subjects — come from.
 *
 * <p>This exists to stop overloading "class" with the roles that aren't products.
 * A class was quietly doing four jobs at once (the served grain, the load backbone,
 * a value vocabulary, a grouping facet); Source names the non-product ones so they
 * stop masquerading as classes with phantom "instances" (e.g. the Oscar categories,
 * which are a vocabulary, not 57 served entities). Each role is a {@link Kind}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Source {

    /** The orthogonal roles a "class" used to blur into one. */
    public enum Kind {
        /** An enumerated / type-constrained VALUE set with labels — a field is
         *  restricted to it and renders its members' labels (e.g. Oscar categories). */
        VOCABULARY,
        /** A SUBJECT set defined by a membership query — a reify draws its subjects
         *  from it (e.g. the P1411 members that back Nomination). */
        POPULATION,
        /** A value PARTITION used to group a product at view time (e.g. P31 type). */
        FACET
    }

    private String name = "";
    private Kind kind = Kind.VOCABULARY;

    // VOCABULARY: the allowed values, given explicitly and/or as a P31 type filter.
    // A field constrained to this source keeps only these values and renders their
    // labels. POPULATION / FACET config is added as those kinds are wired.
    private final List<String> valueQids = new ArrayList<>();
    private String valueTypeQid = "";

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
        if (values != null) {
            for (String v : values) {
                if (v != null && v.matches("(?i)Q\\d+")) {
                    valueQids.add(v.trim());
                }
            }
        }
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

    /** A VOCABULARY needs at least one way to bound its values; other kinds only
     *  need a name until their own config is wired. */
    public boolean isConfigured() {
        if (name().isBlank()) {
            return false;
        }
        return kind() != Kind.VOCABULARY || !valueQids.isEmpty() || hasValueType();
    }

    public Source copy() {
        Source c = new Source(name, kind);
        c.valueTypeQid = valueTypeQid;
        c.valueQids.addAll(valueQids);
        return c;
    }
}
