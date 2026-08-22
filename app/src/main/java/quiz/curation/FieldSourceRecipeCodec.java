package quiz.curation;

import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;

/**
 * Translation boundary between a scoped curation recipe and ModelBuilder's canonical
 * source mapping. Kept at the curation adapter edge so the source/model layer does not
 * depend back on a UI application's sidecar format.
 *
 * <p>It reads the source KIND out of the recipe rather than branching per provider. Every
 * additional source answers the same two questions — which kind of source, and what it
 * names there — so a second one needed no code here, and the branch that existed had
 * quietly made "recordable" mean "native infobox".
 */
public final class FieldSourceRecipeCodec {
    private FieldSourceRecipeCodec() { }

    public static FieldSourceMapping mapping(FieldSourceRecipe recipe) {
        if (recipe == null
                || !FieldSourceRecipe.ADDITIONAL_SOURCE.equals(recipe.provider())) {
            return null;
        }
        FieldSourceType sourceType = sourceType(recipe.parameter(FieldSourceRecipe.SOURCE_TYPE));
        String property = recipe.parameter(FieldSourceRecipe.PROPERTY);
        if (sourceType == null || property.isBlank()) return null;
        FieldSourceMapping result = new FieldSourceMapping();
        result.sourceType(sourceType);
        result.propertyPid(property);
        result.propertyLabel(recipe.parameter(FieldSourceRecipe.LABEL));
        return result;
    }

    /** Null for a mapping this sidecar has no business recording: an additional source is
     *  read after extraction by definition, so the source type answers that itself. */
    public static FieldSourceRecipe scoped(String type, String field,
            FieldSourceMapping mapping) {
        if (mapping == null || mapping.sourceType() == null
                || !mapping.sourceType().filledAfterExtraction()
                || mapping.propertyPid().isBlank()) {
            return null;
        }
        return FieldSourceRecipe.additionalSource(type, field,
                mapping.sourceType().name(), mapping.propertyPid(), mapping.propertyLabel());
    }

    /** An unreadable kind yields no mapping rather than an exception: a sidecar is a
     *  user's record and must survive being opened by a build that does not know every
     *  source a later one wrote — the same reason an unknown category policy defaults. */
    private static FieldSourceType sourceType(String name) {
        try {
            return FieldSourceType.valueOf(name);
        } catch (RuntimeException unknown) {
            return null;
        }
    }
}
