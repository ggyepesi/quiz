package quiz.curation;

/**
 * Capability of a model-backed domain to exchange field-source rules with its model.
 *
 * <p>Both directions, deliberately. Promotion alone made the model write-only from
 * curation's side: a domain whose model already declared {@code locations -> P840} still
 * asked which property to use, because nothing ever read the declaration back.
 */
public interface FieldRulePromoter {

    PromotionPreview previewPromotion(Correction correction);

    PromotionPreview promote(Correction correction) throws Exception;

    default PromotionPreview previewPromotion(FieldSourceRecipe recipe) {
        return PromotionPreview.ineligible("This source recipe cannot be promoted.");
    }

    default PromotionPreview promote(FieldSourceRecipe recipe) throws Exception {
        throw new IllegalArgumentException("This source recipe cannot be promoted.");
    }

    /**
     * The source rule the backing model already declares for a field, or null when the
     * model has none (or there is no model). Curation uses it as the starting point
     * instead of asking for what the domain was generated with.
     *
     * <p>Returns the mapping as the model holds it — property, label and direction —
     * so the two cannot drift into separate representations of the same rule.
     */
    default wikidata.explore.model.FieldSourceMapping declaredSource(
            String type, String field) {
        return null;
    }

    record PromotionPreview(
            boolean eligible,
            String reason,
            String modelPath,
            String targetType,
            String field,
            String fieldType,
            String sourceKind,
            String sourceEntity,
            String sourceProperty,
            boolean addsField,
            String previousProperty) {

        public static PromotionPreview ineligible(String reason) {
            return new PromotionPreview(false, reason, "", "", "", "",
                    "", "", "", false, "");
        }
    }
}
