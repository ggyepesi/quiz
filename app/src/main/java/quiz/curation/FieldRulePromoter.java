package quiz.curation;

/** Capability of a model-backed domain to promote one reviewed field-source rule. */
public interface FieldRulePromoter {

    PromotionPreview previewPromotion(Correction correction);

    PromotionPreview promote(Correction correction) throws Exception;

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
