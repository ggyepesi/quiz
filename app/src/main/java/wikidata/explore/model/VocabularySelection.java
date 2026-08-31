package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Selection.Kind#VOCABULARY} Selection: a value domain — the allowed values
 * of a field, given explicitly as {@link #valueQids()} and/or by a P31
 * {@link #valueTypeQid() type} filter (e.g. the Oscar categories). A referenced
 * vocabulary IS a reify's value domain.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VocabularySelection extends Selection {

    // The allowed values, given explicitly and/or by a P31 type filter.
    private final List<String> valueQids = new ArrayList<>();
    private String valueTypeQid = "";

    public VocabularySelection() {
        super();
        kind(Kind.VOCABULARY);
    }

    public VocabularySelection(String name) {
        super(name, Kind.VOCABULARY);
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

    @Override
    public boolean isConfigured() {
        return !name().isBlank() && (!valueQids.isEmpty() || hasValueType());
    }

    @Override
    public VocabularySelection copy() {
        VocabularySelection c = new VocabularySelection(name());
        copyIdentityTo(c);
        c.valueTypeQid = valueTypeQid;
        c.valueQids.addAll(valueQids);
        return c;
    }
}
