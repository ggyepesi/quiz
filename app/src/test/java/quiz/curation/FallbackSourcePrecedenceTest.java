package quiz.curation;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;


import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import domain.DomainModel;

/**
 * A dataset may override a field's source BEFORE the model does.
 *
 * <p>The additional-source path grew a second answer to that question and put the model
 * first, so a dataset that had chosen an infobox parameter went silent the moment the
 * model declared any fallback at all — and the "clear" action had nothing to clear. The
 * category recipe settled the direction; this locks it for every fallback source.
 */
class FallbackSourcePrecedenceTest {

    @Test void theDatasetsOwnChoiceShadowsTheModelsDeclaration() {
        FieldSourceMapping chosen = mapping("Infobox film.country");

        FieldSourceMapping effective = FieldSourceChoices.additionalSource(
                chosen, new DeclaringDomain(), "Movie", "location");

        assertSame(chosen, effective, "an override that the model can veto is not an override");
    }

    @Test void theModelIsHeardWhereTheDatasetHasSaidNothing() {
        FieldSourceMapping effective = FieldSourceChoices.additionalSource(
                null, new DeclaringDomain(), "Movie", "location");

        assertEquals("Infobox film.based_on", effective.propertyPid());
    }

    @Test void clearingTheOverrideLetsTheModelBeHeardAgain() {
        assertEquals("Infobox film.based_on", FieldSourceChoices.additionalSource(
                null, new DeclaringDomain(), "Movie", "location").propertyPid());
    }

    @Test void aFieldNeitherDeclaresNorOverridesHasNoFallback() {
        assertNull(FieldSourceChoices.additionalSource(
                null, new DeclaringDomain(), "Movie", "awards"));
    }

    @Test void aDomainThatCannotBeAskedIsNotAnError() {
        assertNull(FieldSourceChoices.additionalSource(null, new PlainDomain(), "Movie", "location"));
    }

    private static FieldSourceMapping mapping(String key) {
        FieldSourceMapping value = new FieldSourceMapping();
        value.sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        value.propertyPid(key);
        return value;
    }

    /** A model that declares a fallback for exactly one field. */
    private static final class DeclaringDomain extends PlainDomain
            implements quiz.curation.FieldRulePromoter {
        @Override public FieldSourceMapping declaredFallbackSource(String type, String field) {
            return "Movie".equals(type) && "location".equals(field)
                    ? mapping("Infobox film.based_on") : null;
        }
        @Override public PromotionPreview previewPromotion(quiz.curation.Correction c) {
            return PromotionPreview.ineligible("not part of this test");
        }
        @Override public PromotionPreview promote(quiz.curation.Correction c) {
            return PromotionPreview.ineligible("not part of this test");
        }
    }

    private static class PlainDomain implements DomainModel {
        @Override public List<String> types() { return List.of("Movie"); }
        @Override public List<String> servedTypes() { return List.of("Movie"); }
        @Override public java.util.Collection<? extends Viewable> instances() { return List.of(); }
        @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        @Override public objectview.field.FieldSchema fieldSchema(String type) { return List::of; }
    }
}
