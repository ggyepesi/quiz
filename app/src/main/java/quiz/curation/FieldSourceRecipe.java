package quiz.curation;

import wikidata.explore.model.CategoryCandidatePolicy;
import wikidata.explore.model.WikipediaCategoryRule;

import java.util.LinkedHashMap;
import java.util.Map;

/** Durable, provider-neutral field source configuration owned by Transform curation.
 * Provider-specific editors interpret {@link #parameters}; the sidecar does not need a
 * new schema every time another evidence source is plugged in. */
public record FieldSourceRecipe(
        String type, String field, String provider, Map<String, String> parameters) {

    public static final String WIKIPEDIA_CATEGORY = "wikipedia-category";
    public static final String WIKIPEDIA_INFOBOX = "wikipedia-infobox";
    public static final String PATTERN = "pattern";
    public static final String POLICY = "policy";
    public static final String INFOBOX_KEY = "key";
    public static final String INFOBOX_LABEL = "label";

    public FieldSourceRecipe {
        type = clean(type);
        field = clean(field);
        provider = clean(provider);
        parameters = parameters == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(parameters));
        if (type.isBlank() || field.isBlank() || provider.isBlank()) {
            throw new IllegalArgumentException("A source recipe needs type, field and provider");
        }
    }

    public static FieldSourceRecipe wikipediaCategory(
            String type, String field, String pattern, CategoryCandidatePolicy policy) {
        return new FieldSourceRecipe(type, field, WIKIPEDIA_CATEGORY,
                Map.of(PATTERN, pattern == null ? "" : pattern.trim(),
                        POLICY, (policy == null ? CategoryCandidatePolicy.REVIEW : policy).name()));
    }

    /** A native Wikipedia template parameter, identified as Template.parameter. */
    public static FieldSourceRecipe wikipediaInfobox(
            String type, String field, String key, String label) {
        return new FieldSourceRecipe(type, field, WIKIPEDIA_INFOBOX,
                Map.of(INFOBOX_KEY, clean(key), INFOBOX_LABEL, clean(label)));
    }

    public String infoboxKey() {
        return WIKIPEDIA_INFOBOX.equals(provider()) ? parameter(INFOBOX_KEY) : "";
    }

    public String infoboxLabel() {
        return WIKIPEDIA_INFOBOX.equals(provider()) ? parameter(INFOBOX_LABEL) : "";
    }

    public String parameter(String name) {
        return parameters.getOrDefault(name, "");
    }

    /**
     * The rule this recipe means, derived HERE and only here. Curation renders the
     * effective rule and promotion writes it into the model; when each read the map for
     * itself the two drifted — one defaulted an unknown policy, the other threw. An
     * unparseable policy yields the reviewed default, because a recipe is a user's note
     * about a source and must stay readable; promotion validates it separately before
     * writing anything to the model.
     */
    public WikipediaCategoryRule categoryRule() {
        WikipediaCategoryRule rule = new WikipediaCategoryRule();
        rule.pattern(parameter(PATTERN));
        try {
            rule.policy(CategoryCandidatePolicy.valueOf(parameter(POLICY)));
        } catch (RuntimeException unknown) {
            rule.policy(CategoryCandidatePolicy.REVIEW);
        }
        return rule;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
