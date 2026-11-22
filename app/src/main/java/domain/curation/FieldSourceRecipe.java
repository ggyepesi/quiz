package domain.curation;

import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceRecipe;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.datasource.dbpedia.DbpediaDatasourceProvider;

import java.util.Map;
import java.util.Objects;

/**
 * Durable, provider-neutral field source configuration owned by Transform curation.
 * Provider-specific editors interpret {@link #parameters}; the sidecar does not need a
 * new schema every time another evidence source is plugged in.
 *
 * <p><b>{@code provider} does not hold a datasource, and must not be renamed as though
 * it did.</b> The remaining legacy value, {@link #ADDITIONAL_SOURCE}, is a SLOT saying
 * where on the field this recipe attaches. The datasource for such a recipe is a
 * parameter, under {@link #SOURCE_TYPE}.
 *
 * <p>This is the compatibility facade for the original curation-sidecar shape. New code
 * uses {@link SourceBinding}; the facade can disappear after the sidecar has a versioned
 * migration from {@code type/field/provider/parameters} to target + recipe. Until then it
 * keeps the supported legacy field-source records readable and writable without letting
 * their overloaded {@code provider} field leak into the shared datasource contract. The
 * retired curation-side category override is deliberately rejected: category evidence is
 * authored in the model and an old override is dropped at the sidecar load/save boundary.
 *
 * <p>The one-slot rule below is likewise a property of the ATTACHMENT, not of the
 * recipe: at most one such recipe per ⟨type, field⟩. Carried across as a magic value
 * that happens to be unique, it invites the very bug it was written to record.
 */
public final class FieldSourceRecipe {

    /** Where else this field may be read from — ONE slot per field, because the choices
     * offered for it are alternatives. Keying it per provider meant picking DBpedia left
     * an earlier native-infobox recipe behind, and a reload heard the abandoned one. */
    public static final String ADDITIONAL_SOURCE = SourceBindingSlot.FALLBACK_FIELD_VALUE.id();
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
                != domain.datasource.api.BindingScope.FIELD_VALUE) {
            throw new IllegalArgumentException("A field source recipe needs a field binding");
        }
        if (binding.target().slot() == SourceBindingSlot.CATEGORY_EVIDENCE) {
            throw new IllegalArgumentException(
                    "Wikipedia category evidence is model configuration; configure it in ModelBuilder");
        }
        this.binding = binding;
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

    /** One observed native Wikipedia infobox value promoted into an authored model
     * field source. Discovery supplies the value key; the user supplies the target
     * field and whether this is its primary or fallback source. */
    public static FieldSourceRecipe wikipediaInfobox(
            String type, String field, SourceBindingSlot slot,
            String property, String label) {
        if (slot != SourceBindingSlot.PRIMARY_FIELD_VALUE
                && slot != SourceBindingSlot.FALLBACK_FIELD_VALUE) {
            throw new IllegalArgumentException(
                    "An infobox value must be a primary or fallback field source");
        }
        return new FieldSourceRecipe(new SourceBinding(
                SourceBindingTarget.fieldValue(type, field, slot),
                new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.INFOBOX_PARAMETER,
                        Map.of(PROPERTY, clean(property), LABEL, clean(label),
                                SOURCE_TYPE,
                                domain.config.FieldSourceType.WIKIPEDIA_INFOBOX
                                        .name()))));
    }

    /** One rendered infobox row interpreted only through its retained article links.
     * The links nominate already-loaded instances for review; the row text is not used
     * as an entity value. */
    public static FieldSourceRecipe wikipediaInfoboxLinks(
            String type, String field, String property, String label) {
        return new FieldSourceRecipe(new SourceBinding(
                SourceBindingTarget.fieldValue(type, field,
                        SourceBindingSlot.CANDIDATE_EVIDENCE),
                new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.INFOBOX_ROW_LINKS,
                        Map.of(PROPERTY, clean(property), LABEL, clean(label)))));
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

    private static SourceBinding legacyBinding(
            String type, String field, String slot, Map<String, String> parameters) {
        SourceBindingSlot bindingSlot = SourceBindingSlot.require(slot);
        Map<String, String> safe = parameters == null ? Map.of() : parameters;
        SourceRecipe recipe;
        if (bindingSlot == SourceBindingSlot.CATEGORY_EVIDENCE) {
            throw new IllegalArgumentException(
                    "Wikipedia category evidence is model configuration; configure it in ModelBuilder");
        } else if (bindingSlot == SourceBindingSlot.FALLBACK_FIELD_VALUE) {
            String sourceType = clean(safe.get(SOURCE_TYPE));
            if ("DBPEDIA".equals(sourceType)) {
                recipe = new SourceRecipe(DbpediaDatasourceProvider.ID,
                        DbpediaDatasourceProvider.PROPERTY, safe);
            } else if ("WIKIPEDIA_INFOBOX".equals(sourceType)) {
                recipe = new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.INFOBOX_PARAMETER, safe);
            } else if (java.util.Set.of(
                    "WIKIPEDIA_ARTICLE_INTRODUCTION",
                    "WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH",
                    "WIKIPEDIA_ARTICLE_LEAD_SENTENCE").contains(sourceType)) {
                java.util.LinkedHashMap<String, String> configured =
                        new java.util.LinkedHashMap<>(safe);
                String projection = switch (sourceType) {
                    case "WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH" -> "LEAD_PARAGRAPH";
                    case "WIKIPEDIA_ARTICLE_LEAD_SENTENCE" -> "LEAD_SENTENCE";
                    default -> "INTRODUCTION";
                };
                configured.put(WikipediaDatasourceProvider.ARTICLE_TEXT_PROJECTION,
                        projection);
                recipe = new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.ARTICLE_INTRODUCTION, configured);
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
