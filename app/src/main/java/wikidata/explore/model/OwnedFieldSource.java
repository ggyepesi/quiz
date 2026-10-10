package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import datasource.api.SourceBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * A source override for one field of the component produced at an ownership site.
 *
 * <p>The containing {@link GeneratedFieldModel} is the ownership field, so the site is
 * stored once rather than repeated on every override. The path is relative to the owned
 * class. Shape remains on that class's field; only acquisition varies by site.
 */
public final class OwnedFieldSource {
    private String fieldPath = "";
    private final FieldSourceMapping mapping = new FieldSourceMapping();
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private FieldSourceMapping fallbackMapping;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private WikipediaCategoryRule wikipediaCategoryRule;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<SourceBinding> sourceBindings = new ArrayList<>();

    public OwnedFieldSource() { }

    public OwnedFieldSource(String fieldPath) {
        fieldPath(fieldPath);
    }

    public String fieldPath() { return clean(fieldPath); }

    public void fieldPath(String value) { fieldPath = clean(value); }

    public FieldSourceMapping mapping() { return mapping; }

    public FieldSourceMapping fallbackMapping() { return fallbackMapping; }

    public void fallbackMapping(FieldSourceMapping value) { fallbackMapping = value; }

    public FieldSourceMapping ensureFallbackMapping() {
        if (fallbackMapping == null) fallbackMapping = new FieldSourceMapping();
        return fallbackMapping;
    }

    public WikipediaCategoryRule wikipediaCategoryRule() { return wikipediaCategoryRule; }

    public void wikipediaCategoryRule(WikipediaCategoryRule value) {
        wikipediaCategoryRule = value;
    }

    public WikipediaCategoryRule ensureWikipediaCategoryRule() {
        if (wikipediaCategoryRule == null) {
            wikipediaCategoryRule = new WikipediaCategoryRule();
        }
        return wikipediaCategoryRule;
    }

    public List<SourceBinding> sourceBindings() { return sourceBindings; }

    public OwnedFieldSource copy() {
        OwnedFieldSource copy = new OwnedFieldSource(fieldPath);
        copy.mapping.copyFrom(mapping);
        if (fallbackMapping != null) copy.fallbackMapping = fallbackMapping.copy();
        if (wikipediaCategoryRule != null) {
            copy.wikipediaCategoryRule = wikipediaCategoryRule.copy();
        }
        copy.sourceBindings.addAll(sourceBindings);
        return copy;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
