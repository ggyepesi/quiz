package quiz.curation;

import datasource.api.SourceBinding;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceRecipe;
import datasource.wikipedia.WikipediaCategoryDiscoveryOperation;
import datasource.wikipedia.WikipediaDatasourceProvider;
import datasource.dbpedia.DbpediaDatasourceProvider;
import wikidata.explore.model.CategoryCandidatePolicy;
import wikidata.explore.model.WikipediaCategoryRule;

import java.util.Map;
import java.util.Objects;

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
 * <p>This is the compatibility facade for the original curation-sidecar shape. New code
 * uses {@link SourceBinding}; the facade can disappear after the sidecar has a versioned
 * migration from {@code type/field/provider/parameters} to target + recipe. Until then it
 * keeps old files readable and writable without letting their overloaded {@code provider}
 * field leak into the shared datasource contract.
 *
 * <p>The one-slot rule below is likewise a property of the ATTACHMENT, not of the
 * recipe: at most one such recipe per ⟨type, field⟩. Carried across as a magic value
 * that happens to be unique, it invites the very bug it was written to record.
 */
public final class FieldSourceRecipe {

    public static final String WIKIPEDIA_CATEGORY = SourceBindingSlot.CATEGORY_EVIDENCE.id();
    /** Where else this field may be read from — ONE slot per field, because the choices
     * offered for it are alternatives. Keying it per provider meant picking DBpedia left
     * an earlier native-infobox recipe behind, and a reload heard the abandoned one. */
    public static final String ADDITIONAL_SOURCE = SourceBindingSlot.FALLBACK_FIELD_VALUE.id();
    public static final String PATTERN = "pattern";
    public static final String POLICY = "policy";
    public static final String SOURCE_TYPE = "sourceType";
    public static final String PROPERTY = "property";
    public static final String LABEL = "label";

    private final SourceBinding binding;

    public FieldSourceRecipe(
            String type, String field, String provider, Map<String, String> parameters) {
        this.binding = legacyBinding(type, field, provider, parameters);
    }

    public FieldSourceRecipe(SourceBinding binding) {
        if (binding == null || binding.target().scope()
                != datasource.api.BindingScope.FIELD_VALUE) {
            throw new IllegalArgumentException("A field source recipe needs a field binding");
        }
        this.binding = binding;
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
        return binding.recipe().parameter(name);
    }

    public String type() { return binding.target().className(); }
    public String field() { return binding.target().fieldPath(); }
    /** Compatibility name for the replaceable slot, not a datasource provider. */
    public String provider() { return binding.target().slot().id(); }
    public Map<String, String> parameters() { return binding.recipe().parameters(); }
    public SourceRecipe recipe() { return binding.recipe(); }
    public SourceBinding binding() { return binding; }

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

    private static SourceBinding legacyBinding(
            String type, String field, String slot, Map<String, String> parameters) {
        SourceBindingSlot bindingSlot = SourceBindingSlot.require(slot);
        Map<String, String> safe = parameters == null ? Map.of() : parameters;
        SourceRecipe recipe;
        if (bindingSlot == SourceBindingSlot.CATEGORY_EVIDENCE) {
            recipe = new SourceRecipe(WikipediaDatasourceProvider.ID,
                    WikipediaCategoryDiscoveryOperation.ID, safe);
        } else if (bindingSlot == SourceBindingSlot.FALLBACK_FIELD_VALUE) {
            String sourceType = clean(safe.get(SOURCE_TYPE));
            if ("DBPEDIA".equals(sourceType)) {
                recipe = new SourceRecipe(DbpediaDatasourceProvider.ID,
                        DbpediaDatasourceProvider.PROPERTY, safe);
            } else if ("WIKIPEDIA_INFOBOX".equals(sourceType)) {
                recipe = new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.INFOBOX_PARAMETER, safe);
            } else {
                // Unknown future sidecar values remain readable. Resolution is the
                // boundary that reports an unavailable provider/operation.
                recipe = new SourceRecipe("legacy-field-source",
                        sourceType.isBlank() ? "unknown" : sourceType.toLowerCase(), safe);
            }
        } else throw new IllegalArgumentException(
                "Not a field source binding slot: " + bindingSlot.id());
        return new SourceBinding(
                SourceBindingTarget.fieldValue(type, field, bindingSlot), recipe);
    }

    @Override public boolean equals(Object other) {
        return other instanceof FieldSourceRecipe that && binding.equals(that.binding);
    }

    @Override public int hashCode() { return Objects.hash(binding); }

    @Override public String toString() { return binding.toString(); }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
