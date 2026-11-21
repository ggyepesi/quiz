package wikidata.explore.workbench;

import datasource.api.SourceRecipe;
import wikidata.explore.model.ClassSourceBindings;
import wikidata.explore.model.GeneratedClassModel;

/** Shared eligibility rule for actions that write Wikidata membership. */
final class PopulationSourceEditing {

    private PopulationSourceEditing() { }

    /**
     * Why a Wikidata membership cannot be written. Empty means the class has no
     * alternative population producer and the caller may check its membership shape.
     */
    static String wikidataMembershipRefusal(GeneratedClassModel clazz) {
        SourceRecipe other = ClassSourceBindings.alternativePopulationSource(clazz);
        return other == null ? ""
                : "This class's population comes from " + other.providerId() + " — "
                        + other.operationId() + ". Choose \"Wikidata — population triple\" "
                        + "as its Population source first; nothing was changed.";
    }
}
