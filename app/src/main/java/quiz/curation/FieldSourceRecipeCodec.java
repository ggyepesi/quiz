package quiz.curation;

import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;

/**
 * Translation boundary between a scoped curation recipe and ModelBuilder's canonical
 * source mapping. Kept at the curation adapter edge so the source/model layer does not
 * depend back on a UI application's sidecar format.
 */
public final class FieldSourceRecipeCodec {
    private FieldSourceRecipeCodec() { }

    public static FieldSourceMapping mapping(FieldSourceRecipe recipe) {
        if (recipe == null) return null;
        if (FieldSourceRecipe.WIKIPEDIA_INFOBOX.equals(recipe.provider())) {
            FieldSourceMapping result = new FieldSourceMapping();
            result.sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
            result.propertyPid(recipe.infoboxKey());
            result.propertyLabel(recipe.infoboxLabel());
            return result;
        }
        return null;
    }

    public static FieldSourceRecipe scoped(String type, String field,
            FieldSourceMapping mapping) {
        if (mapping == null || mapping.sourceType() != FieldSourceType.WIKIPEDIA_INFOBOX) {
            return null;
        }
        return FieldSourceRecipe.wikipediaInfobox(type, field,
                mapping.propertyPid(), mapping.propertyLabel());
    }
}
