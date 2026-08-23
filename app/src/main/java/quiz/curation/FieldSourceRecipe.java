package quiz.curation;

import wikidata.explore.model.CategoryCandidatePolicy;
import wikidata.explore.model.WikipediaCategoryRule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable, provider-neutral field source configuration owned by Transform curation.
 * Provider-specific editors interpret {@link #parameters}; the sidecar does not need a
 * new schema every time another evidence source is plugged in.
 *
 * <p><b>{@code provider} does not hold a datasource, and must not be renamed as though
 * it did.</b> It takes one of two values, and they are different kinds of thing:
 *
 * <ul>
 *   <li>{@link #WIKIPEDIA_CATEGORY} collapses a provider AND an operation into one
 *       token — {@code wikipedia} × {@code category}.</li>
 *   <li>{@link #ADDITIONAL_SOURCE} is not a source at all but a SLOT, saying where on
 *       the field this recipe attaches. The datasource for such a recipe is a parameter,
 *       under {@link #SOURCE_TYPE}.</li>
 * </ul>
 *
 * <p>{@code datasource.api.SourceRecipe} is the same idea said properly — provider,
 * operation, parameters — with the attachment site belonging to whatever binds it rather
 * than to the recipe. This record should eventually become one, bound at field-value
 * scope. Getting there means an adapter that separates those three meanings; renaming
 * {@code provider} to {@code providerId} would only make a wrong field look right.
 *
 * <p>The one-slot rule below is likewise a property of the ATTACHMENT, not of the
 * recipe: at most one such recipe per ⟨type, field⟩. Carried across as a magic value
 * that happens to be unique, it invites the very bug it was written to record.
 */
public record FieldSourceRecipe(
        String type, String field, String provider, Map<String, String> parameters) {

    public static final String WIKIPEDIA_CATEGORY = "wikipedia-category";
    /** Where else this field may be read from — ONE slot per field, because the choices
     * offered for it are alternatives. Keying it per provider meant picking DBpedia left
     * an earlier native-infobox recipe behind, and a reload heard the abandoned one. */
    public static final String ADDITIONAL_SOURCE = "additional-source";
    public static final String PATTERN = "pattern";
    public static final String POLICY = "policy";
    public static final String SOURCE_TYPE = "sourceType";
    public static final String PROPERTY = "property";
    public static final String LABEL = "label";

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

    /** An additional source for one field: which kind of source, and what it names there
     *  — a Template.parameter, a DBpedia property. The recipe stays provider-neutral, so
     *  {@link FieldSourceRecipeCodec} is the one place that reads the kind back. */
    public static FieldSourceRecipe additionalSource(
            String type, String field, String sourceType, String property, String label) {
        return new FieldSourceRecipe(type, field, ADDITIONAL_SOURCE,
                Map.of(SOURCE_TYPE, clean(sourceType), PROPERTY, clean(property),
                        LABEL, clean(label)));
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
