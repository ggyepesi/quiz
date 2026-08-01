package quiz.transform.app;

import objectview.field.FieldRef;
import quiz.curation.Correction;
import quiz.curation.FieldRulePromoter;
import quiz.curation.ValueSource;
import quiz.transform.ui.DomainModel;
import quiz.transform.ui.FieldDefinitions;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.RuleDirection;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Writes one explicitly reviewed TransformApp field rule into a ModelBuilder model. */
final class ModelFieldRulePromoter {

    private final File modelFile;
    private final DomainModel domain;

    ModelFieldRulePromoter(File modelFile, DomainModel domain) {
        this.modelFile = modelFile;
        this.domain = domain;
    }

    FieldRulePromoter.PromotionPreview preview(Correction correction) {
        try {
            return inspect(correction, loadModel());
        } catch (Exception ex) {
            return FieldRulePromoter.PromotionPreview.ineligible(
                    "Model cannot be read: " + ex.getMessage());
        }
    }

    FieldRulePromoter.PromotionPreview promote(Correction correction) throws Exception {
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        GeneratedProjectModel model = loadModel();
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
        field.mapping().propertyLabel(preview.sourceProperty());
        field.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        field.mapping().productionKind(FieldProductionKind.AUTO);

        File parent = modelFile.getAbsoluteFile().getParentFile();
        File temporary = File.createTempFile(modelFile.getName() + ".promote-", ".tmp", parent);
        try {
            store.save(model, temporary);
            try {
                Files.move(temporary.toPath(), modelFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), modelFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
        return preview;
    }

    private GeneratedProjectModel loadModel() throws Exception {
        if (modelFile == null || !modelFile.isFile()) {
            throw new IllegalArgumentException("This dataset has no ModelBuilder model.");
        }
        if (!Files.isWritable(modelFile.toPath())) {
            throw new IllegalArgumentException("Model is not writable: " + modelFile);
        }
        return new GeneratedProjectModelStore().load(modelFile);
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
}
