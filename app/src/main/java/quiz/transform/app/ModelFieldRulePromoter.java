package quiz.transform.app;

import objectview.field.FieldRef;
import quiz.curation.Correction;
import quiz.curation.FieldRulePromoter;
import quiz.curation.FieldSourceRecipe;
import quiz.curation.ValueSource;
import domain.DomainModel;
import quiz.transform.ui.FieldDefinitions;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceBindings;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;

import java.io.File;

/**
 * Applies one explicitly reviewed TransformApp field rule to the domain's WORKING model.
 *
 * <p>It never touches the model file. It used to read model.json, edit it and write it
 * back on the spot, which made a promotion a second persistence path beside Save and let
 * it overwrite whatever ModelBuilder had saved in between. The edit now lands in the
 * working model the opened domain holds, and Save writes it.
 */
final class ModelFieldRulePromoter {

    private final File modelFile;
    private final GeneratedProjectModel model;
    private final DomainModel domain;

    ModelFieldRulePromoter(File modelFile, GeneratedProjectModel model, DomainModel domain) {
        this.modelFile = modelFile;
        this.model = model;
        this.domain = domain;
    }

    FieldRulePromoter.PromotionPreview preview(Correction correction) {
        if (model == null) return noModel();
        return inspect(correction, model);
    }

    FieldRulePromoter.PromotionPreview promote(Correction correction) throws Exception {
        if (model == null) throw new IllegalArgumentException(noModel().reason());
        FieldRulePromoter.PromotionPreview preview = inspect(correction, model);
        if (!preview.eligible()) {
            throw new IllegalArgumentException(preview.reason());
        }
        GeneratedClassModel owner = model.findClass(preview.targetType());
        GeneratedFieldModel field = findField(owner, preview.field());
        if (field == null) {
            FieldRef schema = domain.fieldSchema(preview.targetType()).field(preview.field());
            var definition = FieldDefinitions.fromFieldRef(schema);
            field = owner.addField(definition.name(), definition.type(),
                    definition.cardinality());
        }
        FieldRef schema = domain.fieldSchema(preview.targetType()).field(preview.field());
        field.definition(FieldDefinitions.fromFieldRef(schema));
        field.mapping().sourceType(FieldSourceType.SPARQL);
        field.mapping().propertyPid(preview.sourceProperty());
        ValueSource source = correction.source();
        field.mapping().propertyLabel(source.propertyLabel() == null
                || source.propertyLabel().isBlank()
                ? preview.sourceProperty() : source.propertyLabel());
        field.mapping().direction(direction(source.direction()));
        field.mapping().productionKind(FieldProductionKind.AUTO);
        return preview;
    }

    FieldRulePromoter.PromotionPreview preview(FieldSourceRecipe recipe) {
        if (model == null) return noModel();
        return inspect(recipe, model);
    }

    FieldRulePromoter.PromotionPreview promote(FieldSourceRecipe recipe) throws Exception {
        if (model == null) throw new IllegalArgumentException(noModel().reason());
        FieldRulePromoter.PromotionPreview preview = inspect(recipe, model);
        if (!preview.eligible()) throw new IllegalArgumentException(preview.reason());
        GeneratedFieldModel field = findField(
                model.findClass(recipe.type()), recipe.field());
        if (infobox(recipe) && recipe.binding().target().slot()
                == datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE) {
            // The binding names only kind, key and label; whatever the previous primary
            // also said about acquisition (a qualifier condition, a value language, a
            // direction) would otherwise survive and be saved into the infobox binding.
            field.mapping().copyAcquisitionFrom(new FieldSourceMapping());
        }
        wikidata.explore.model.FieldSourceBindings.put(field, recipe.binding());
        return preview;
    }

    /**
     * The rule this field already has in the working model, or null. A field with no
     * property declared yields null rather than an empty rule — "declared as nothing" and
     * "not declared" are different, and only the second should send the user to the
     * property picker.
     */
    FieldSourceMapping declaredSource(String type, String field) {
        GeneratedFieldModel declared = declaredField(type, field);
        if (declared == null || declared.mapping() == null
                || declared.mapping().propertyPid() == null
                || declared.mapping().propertyPid().isBlank()) {
            return null;
        }
        return declared.mapping();
    }

    FieldSourceMapping declaredFallbackSource(String type, String field) {
        GeneratedFieldModel declared = declaredField(type, field);
        if (declared == null || declared.fallbackMapping() == null
                || declared.fallbackMapping().propertyPid().isBlank()) return null;
        FieldSourceMapping copy = new FieldSourceMapping();
        copy.copyFrom(declared.fallbackMapping());
        return copy;
    }

    datasource.api.SourceBinding declaredBinding(
            String type, String field, datasource.api.SourceBindingSlot slot) {
        GeneratedFieldModel declared = declaredField(type, field);
        return declared == null || slot == null ? null
                : wikidata.explore.model.FieldSourceBindings.binding(declared, slot);
    }

    private GeneratedFieldModel declaredField(String type, String field) {
        if (model == null || type == null || field == null) return null;
        return findField(model.findClass(type), field);
    }

    private static FieldRulePromoter.PromotionPreview noModel() {
        return FieldRulePromoter.PromotionPreview.ineligible(
                "This dataset has no ModelBuilder model.");
    }

    private FieldRulePromoter.PromotionPreview inspect(
            FieldSourceRecipe recipe, GeneratedProjectModel model) {
        if (recipe == null) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "Select a reusable source recipe.");
        }
        if (FieldSourceRecipe.WIKIPEDIA_CATEGORY.equals(recipe.provider())) {
            return inspectCategory(recipe, model);
        }
        if (infobox(recipe)) return inspectInfobox(recipe, model);
        return FieldRulePromoter.PromotionPreview.ineligible(
                "This source recipe cannot currently be promoted into a model field.");
    }

    private FieldRulePromoter.PromotionPreview inspectCategory(
            FieldSourceRecipe recipe, GeneratedProjectModel model) {
        if (!FieldSourceRecipe.WIKIPEDIA_CATEGORY.equals(recipe.provider())) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "This is not a Wikipedia category recipe.");
        }
        GeneratedClassModel owner = model.findClass(recipe.type());
        GeneratedFieldModel field = findField(owner, recipe.field());
        if (owner == null || field == null) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "The model must already declare " + recipe.type() + "." + recipe.field() + ".");
        }
        datasource.wikipedia.WikipediaDatasourceProvider.CategoryRule providerRule =
                datasource.wikipedia.WikipediaDatasourceProvider.categoryRule(
                        recipe.binding());
        if (providerRule == null) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "Configure one <value> category pattern or at least one exact "
                            + "category-to-QID mapping.");
        }
        String policy = providerRule.policy();
        try {
            wikidata.explore.model.CategoryCandidatePolicy.valueOf(policy);
        } catch (RuntimeException invalid) {
            return FieldRulePromoter.PromotionPreview.ineligible("Unknown category policy: " + policy);
        }
        wikidata.explore.model.WikipediaCategoryRule candidate = recipe.categoryRule();
        wikidata.explore.model.WikipediaCategoryRule declared = field.wikipediaCategoryRule();
        String previous = categorySummary(declared);
        if (sameCategoryRule(declared, candidate)) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "The model already contains this category recipe.");
        }
        return new FieldRulePromoter.PromotionPreview(true,
                previous.isBlank() ? "Add category source recipe" : "Update category source recipe",
                modelFile.getAbsolutePath(), recipe.type(), recipe.field(), "",
                "Wikipedia category", "", categorySummary(candidate), false, previous);
    }

    private static boolean infobox(FieldSourceRecipe recipe) {
        return datasource.wikipedia.WikipediaDatasourceProvider.ID.equals(
                        recipe.recipe().providerId())
                && datasource.wikipedia.WikipediaDatasourceProvider.INFOBOX_PARAMETER.equals(
                        recipe.recipe().operationId());
    }

    private FieldRulePromoter.PromotionPreview inspectInfobox(
            FieldSourceRecipe recipe, GeneratedProjectModel model) {
        datasource.api.SourceBinding binding = recipe.binding();
        datasource.api.SourceBindingSlot slot = binding.target().slot();
        if (slot != datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE
                && slot != datasource.api.SourceBindingSlot.FALLBACK_FIELD_VALUE) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "A Wikipedia infobox value must be a primary or fallback field source.");
        }
        GeneratedClassModel owner = model.findClass(recipe.type());
        GeneratedFieldModel field = findField(owner, recipe.field());
        if (owner == null || field == null) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "The model must already declare " + recipe.type() + "."
                            + recipe.field() + ".");
        }
        if (owner.isImported()) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    owner.className() + " is imported from " + owner.importedFrom()
                            + "; configure the source in that owning model.");
        }
        if (owner.aggregateSource() != null || !acquired(field.mapping().productionKind())) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    recipe.type() + "." + recipe.field()
                            + " is produced from other model data, not acquired from a source.");
        }
        if (!holdsInfoboxText(field)) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    recipe.type() + "." + recipe.field() + " is " + field.displayType()
                            + "; an infobox value is stored as its text, so it can fill "
                            + "only a single String or Text field.");
        }
        String property = recipe.parameter(FieldSourceRecipe.PROPERTY);
        if (property.isBlank()
                || datasource.evidence.InfoboxParameters.Key.parse(property) == null) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "The discovery result does not name a complete infobox value key.");
        }
        datasource.api.SourceBinding previous =
                FieldSourceBindings.binding(field, slot);
        if (sameSource(binding, previous)) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "The model already uses this Wikipedia infobox value as the "
                            + slotLabel(slot) + " source.");
        }
        String previousSource = previous == null ? "" : sourceSummary(previous);
        String action = previous == null ? "Add " + slotLabel(slot) + " source"
                : "Replace " + slotLabel(slot) + " source";
        return new FieldRulePromoter.PromotionPreview(true, action,
                modelFile.getAbsolutePath(), recipe.type(), recipe.field(),
                field.displayType(), "Wikipedia infobox " + slotLabel(slot), "",
                property, false, previousSource);
    }

    /** Acquisition writes an infobox value's text as it is
     *  ({@code WikipediaInfoboxAcquisition.fill}); in an entity or list field that text
     *  would stand where references or members belong. */
    private static boolean holdsInfoboxText(GeneratedFieldModel field) {
        return field.cardinality() == wikidata.explore.model.FieldCardinality.SINGLE
                && (field.type() == datasource.schema.FieldType.STRING
                        || field.type() == datasource.schema.FieldType.TEXT);
    }

    private static boolean acquired(FieldProductionKind kind) {
        return kind == FieldProductionKind.AUTO
                || kind == FieldProductionKind.INLINE_VALUE
                || kind == FieldProductionKind.DELAYED_ENTITY_FIELD
                || kind == FieldProductionKind.CHILD_OBJECTS;
    }

    private static boolean sameSource(
            datasource.api.SourceBinding left, datasource.api.SourceBinding right) {
        if (left == null || right == null || !left.sameTarget(right)) return false;
        return left.recipe().providerId().equals(right.recipe().providerId())
                && left.recipe().operationId().equals(right.recipe().operationId())
                && left.recipe().parameter(FieldSourceRecipe.PROPERTY).equals(
                        right.recipe().parameter(FieldSourceRecipe.PROPERTY));
    }

    private static String slotLabel(datasource.api.SourceBindingSlot slot) {
        return slot == datasource.api.SourceBindingSlot.FALLBACK_FIELD_VALUE
                ? "fallback" : "primary";
    }

    private static String sourceSummary(datasource.api.SourceBinding binding) {
        if (binding == null) return "";
        String property = binding.recipe().parameter(FieldSourceRecipe.PROPERTY);
        return binding.recipe().providerId() + " / " + binding.recipe().operationId()
                + (property.isBlank() ? "" : " / " + property);
    }

    private static boolean sameCategoryRule(
            wikidata.explore.model.WikipediaCategoryRule left,
            wikidata.explore.model.WikipediaCategoryRule right) {
        return left != null && right != null
                && left.pattern().equals(right.pattern())
                && left.policy() == right.policy()
                && new java.util.HashSet<>(left.mappings())
                        .equals(new java.util.HashSet<>(right.mappings()));
    }

    private static String categorySummary(
            wikidata.explore.model.WikipediaCategoryRule rule) {
        if (rule == null || !rule.configured()) return "";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (rule.patternConfigured()) parts.add(rule.pattern());
        if (!rule.mappings().isEmpty()) {
            parts.add(rule.mappings().size() + " exact mapping(s)");
        }
        return String.join(" + ", parts);
    }

    private FieldRulePromoter.PromotionPreview inspect(
            Correction correction, GeneratedProjectModel model) {
        if (correction == null) {
            return FieldRulePromoter.PromotionPreview.ineligible("Select a correction.");
        }
        ValueSource source = correction.source();
        if (source == null || !source.isPresent()) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "The correction has no structured value source.");
        }
        if (!"Wikidata".equalsIgnoreCase(source.kind())) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "Only Wikidata property rules can currently be promoted.");
        }
        if (source.propertyId() == null || !source.propertyId().matches("(?i)P\\d+")) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "The correction has no Wikidata property ID.");
        }
        if (correction.type() == null || correction.type().isBlank()
                || correction.field() == null || correction.field().isBlank()
                || correction.field().contains(".")) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "Promotion requires one top-level class field.");
        }
        GeneratedClassModel owner = model.findClass(correction.type());
        if (owner == null) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "Class " + correction.type() + " is not declared in the model.");
        }
        FieldRef schema = domain.fieldSchema(correction.type()) == null ? null
                : domain.fieldSchema(correction.type()).field(correction.field());
        if (schema == null) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "Field " + correction.type() + "." + correction.field()
                            + " has no declared schema.");
        }
        GeneratedFieldModel existing = findField(owner, correction.field());
        String previous = existing == null ? "" : existing.mapping().propertyPid();
        if (existing != null && (existing.mapping().isQualifier()
                || existing.mapping().productionKind() == FieldProductionKind.INVERT
                || existing.mapping().productionKind() == FieldProductionKind.COMPANION_MATCH)) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "The model field uses an advanced mapping that cannot be replaced safely.");
        }
        if (source.propertyId().equalsIgnoreCase(previous)) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "This field already uses " + source.propertyId() + " in the model.");
        }
        return new FieldRulePromoter.PromotionPreview(
                true,
                existing == null ? "Add field and source rule" : "Update source rule",
                modelFile.getAbsolutePath(), correction.type(), correction.field(),
                schema.typeLabel(), source.kind(), source.entityId(), source.propertyId(),
                existing == null, previous == null ? "" : previous);
    }

    private static GeneratedFieldModel findField(GeneratedClassModel owner, String name) {
        if (owner == null || name == null) return null;
        for (GeneratedFieldModel field : owner.fields()) {
            if (name.equals(field.name()) || name.equalsIgnoreCase(field.name())) return field;
        }
        return null;
    }

    private static RuleDirection direction(String stored) {
        if (stored != null && !stored.isBlank()) {
            try {
                return RuleDirection.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // Older or foreign curation sidecars have no shared direction metadata.
            }
        }
        return RuleDirection.ROOT_TO_ITEM;
    }
}
