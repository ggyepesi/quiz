package quiz.transform.app;

import objectview.Viewable;
import quiz.curation.Curatable;
import quiz.curation.ManualCuration;
import domain.DelegatingDomainModel;
import domain.DomainField;
import domain.DomainModel;
import quiz.transform.ui.SchemaView;
import objectview.field.FieldSchema;
import objectview.viewconfig.FieldTypeSource;

import javax.swing.JComponent;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import domain.DomainSchemas;

/**
 * A {@link DomainModel} that also carries its {@link ManualCuration} store, so the
 * workbench can offer curation. Delegates every schema/instance query to the compiled
 * domain — it only adds the {@link Curatable} capability (and forwards {@link
 * SchemaView} when the base has one).
 */
final class CuratableDomain extends DelegatingDomainModel implements Curatable,
        quiz.curation.FieldRulePromoter, quiz.transform.ui.PopulationSelectionStore,
        quiz.transform.ui.ProjectBacking {

    private final ManualCuration curation;
    private final Collection<? extends Viewable> memberRoots;
    private final List<objectview.viewconfig.DomainGroupRoot> groupRootBindings;
    private final java.io.File modelFile;
    /** The project model as read when this domain opened, edited in memory since.
     *  Null when there is no model file, or it could not be read. */
    private final wikidata.explore.model.GeneratedProjectModel workingModel;
    /** SHA-256 of the model file as last read or written, to notice another writer. */
    private String modelFileDigest = "";
    private final List<String> unsavedModelChanges = new java.util.ArrayList<>();

    CuratableDomain(DomainModel base, ManualCuration curation) {
        this(base, curation, base.memberRoots(), base.groupRootBindings(), null);
    }

    CuratableDomain(
            DomainModel base,
            ManualCuration curation,
            Collection<? extends Viewable> memberRoots,
            List<objectview.viewconfig.DomainGroupRoot> groupRootBindings) {
        this(base, curation, memberRoots, groupRootBindings, null);
    }

    CuratableDomain(
            DomainModel base,
            ManualCuration curation,
            Collection<? extends Viewable> memberRoots,
            List<objectview.viewconfig.DomainGroupRoot> groupRootBindings,
            java.io.File modelFile) {
        super(base);
        this.curation = curation;
        this.memberRoots = memberRoots == null ? List.of() : List.copyOf(memberRoots);
        this.groupRootBindings = groupRootBindings == null
                ? List.of() : List.copyOf(groupRootBindings);
        this.modelFile = modelFile;
        wikidata.explore.model.GeneratedProjectModel read = null;
        if (modelFile != null && modelFile.isFile()) {
            try {
                modelFileDigest = digest(modelFile);
                read = new wikidata.explore.model.GeneratedProjectModelStore().load(modelFile);
            } catch (Exception unreadable) {
                read = null;   // an unreadable model contributes nothing, as before
            }
        }
        this.workingModel = read;
    }

    @Override public ManualCuration curation() { return curation; }

    @Override public <T extends domain.DomainCapability> T capability(Class<T> type) {
        if (type == quiz.transform.ui.ProjectBacking.class && modelFile == null) return null;
        return super.capability(type);
    }


    @Override public List<String> types() { return base.types(); }
    @Override public List<String> servedTypes() { return base.servedTypes(); }
    @Override public String baseType(String type) { return base.baseType(type); }
    @Override public FieldSchema fieldSchema(String type) {
        java.util.Map<String, objectview.field.FieldRef> combined =
                new java.util.LinkedHashMap<>();
        FieldSchema inherited = base.fieldSchema(type);
        if (inherited != null) {
            for (objectview.field.FieldRef field : inherited.fields()) {
                combined.put(field.name(), field);
            }
        }
        for (quiz.curation.FieldDeclaration declaration : curation.fieldDeclarations()) {
            if (java.util.Objects.equals(type, declaration.type())) {
                combined.put(declaration.name(), declaration.fieldRef());
            }
        }
        List<objectview.field.FieldRef> immutable = List.copyOf(combined.values());
        return () -> immutable;
    }
    @Override public Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }
    @Override public FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }
    @Override public Viewable representativeSample(String type) { return base.representativeSample(type); }
    @Override public Collection<? extends Viewable> instances() { return base.instances(); }
    @Override public List<String> selectionNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(base.selectionNames());
        names.addAll(curationSourceSelections().keySet());
        return List.copyOf(names);
    }
    @Override public List<Viewable> selectionMembers(String name) {
        // Looked up by the exact name each selection was built under. The name is a
        // label; nothing is decided by parsing it (it used to be gated on a
        // "Source / " prefix).
        if (base.selectionNames().contains(name)) return base.selectionMembers(name);
        return curationSourceSelections().getOrDefault(name, List.of());
    }

    /** One selection per value source that curation recorded for a field: the instances
     *  holding a value from it, named after the source and the field. */
    private java.util.Map<String, List<Viewable>> curationSourceSelections() {
        java.util.Map<String, java.util.LinkedHashSet<String>> qids =
                new java.util.LinkedHashMap<>();
        for (quiz.curation.Correction correction : curation.corrections()) {
            if (correction.source() == null || correction.source().kind() == null
                    || correction.source().kind().isBlank()) continue;
            qids.computeIfAbsent(sourceSelectionName(correction),
                    ignored -> new java.util.LinkedHashSet<>()).add(correction.qid());
        }
        java.util.Map<String, List<Viewable>> selections = new java.util.LinkedHashMap<>();
        qids.forEach((name, ids) -> selections.put(name, instances().stream()
                .filter(value -> ids.contains(value.getIdentifier()))
                .map(Viewable.class::cast).distinct().toList()));
        return selections;
    }

    private static String sourceSelectionName(quiz.curation.Correction correction) {
        String owner = correction.type() == null || correction.type().isBlank()
                ? "*" : correction.type();
        return "Source / " + correction.source().kind() + " / " + owner + "."
                + correction.field();
    }


    @Override public java.io.File modelFile() { return modelFile; }

    @Override public wikidata.explore.model.GeneratedProjectModel projectModel() {
        return workingModel;
    }

    @Override public String projectName() {
        wikidata.explore.model.GeneratedProjectModel model = workingModel;
        return model == null ? "" : model.name();
    }

    @Override public wikidata.explore.model.GeneratedProjectModel.ProjectKind projectKind() {
        wikidata.explore.model.GeneratedProjectModel model = workingModel;
        return model == null
                ? wikidata.explore.model.GeneratedProjectModel.ProjectKind.DOMAIN
                : model.projectKind();
    }

    @Override public java.io.File snapshotFile() {
        if (modelFile == null) return null;
        String name = modelFile.getName();
        String baseName = name.endsWith(".model.json")
                ? name.substring(0, name.length() - ".model.json".length()) : name;
        return new java.io.File(modelFile.getParentFile(), baseName + ".snapshot.json");
    }

    @Override public void createPopulationSelection(
            String name, String className, java.util.List<String> qids) {
        if (workingModel == null) {
            throw new IllegalStateException("This domain has no saved model file");
        }
        if (workingModel.findClass(className) == null) {
            throw new IllegalArgumentException("The model has no class named " + className);
        }
        wikidata.explore.model.PopulationSelection selection =
                new wikidata.explore.model.PopulationSelection(name);
        selection.className(className);
        selection.instanceQids(qids);
        if (!selection.isConfigured()) {
            throw new IllegalArgumentException(
                    "A population selection needs a name, a class and at least one QID");
        }
        workingModel.replaceSelection(selection);
        unsavedModelChanges.add("Population selection \"" + name + "\": "
                + selection.instanceQids().size() + " " + className + " instance QIDs");
    }

    @Override public List<String> unsavedModelChanges() {
        return List.copyOf(unsavedModelChanges);
    }

    @Override public String modelWriteConflict() {
        if (workingModel == null || modelFile == null) {
            return "This domain has no readable model file.";
        }
        if (!modelFile.isFile()) return "";
        try {
            if (!java.nio.file.Files.isWritable(modelFile.toPath())) {
                return "The model file is not writable: " + modelFile.getPath();
            }
            if (!digest(modelFile).equals(modelFileDigest)) {
                return modelFile.getPath() + " changed on disk after this domain was opened"
                        + " (another application, usually ModelBuilder, saved it). Writing"
                        + " would discard that save; reopen the domain to work on the"
                        + " current model.";
            }
            return "";
        } catch (java.io.IOException unreadable) {
            return "The model file cannot be read: " + unreadable.getMessage();
        }
    }

    @Override public void writeProjectModel() throws Exception {
        String conflict = modelWriteConflict();
        if (!conflict.isEmpty()) throw new IllegalStateException(conflict);
        new wikidata.explore.model.GeneratedProjectModelStore().save(workingModel, modelFile);
        modelFileDigest = digest(modelFile);
        unsavedModelChanges.clear();
    }

    private static String digest(java.io.File file) throws java.io.IOException {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.nio.file.Files.readAllBytes(file.toPath()));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override public boolean exposesEntityUniverse() { return base.exposesEntityUniverse(); }
    @Override public boolean entityOrigin(String type, objectview.field.FieldPath path) {
        return base.entityOrigin(type, path);
    }
    @Override public Collection<? extends Viewable> memberRoots() { return memberRoots; }
    @Override public List<? extends objectview.group.ViewableGroup<?>> groupRoots() {
        // Derived from THIS domain's bindings, not the base's: the curated domain is
        // constructed with its own group roots.
        return groupRootBindings().stream()
                .map(objectview.viewconfig.DomainGroupRoot::root)
                .toList();
    }
    @Override public List<objectview.viewconfig.DomainGroupRoot> groupRootBindings() {
        return groupRootBindings;
    }
    @Override public Class<? extends Viewable> universe() { return base.universe(); }

    @Override public wikidata.explore.model.WikipediaCategoryRule wikipediaCategoryRule(
            String type, String field) {
        try {
            wikidata.explore.model.GeneratedProjectModel model = workingModel;
            if (model == null) return null;
            wikidata.explore.model.GeneratedClassModel owner = model.findClass(type);
            if (owner == null) return null;
            return owner.effectiveFields(model).stream()
                    .filter(value -> value != null && java.util.Objects.equals(field, value.name()))
                    .map(wikidata.explore.model.GeneratedFieldModel::wikipediaCategoryRule)
                    .filter(java.util.Objects::nonNull).findFirst()
                    .map(wikidata.explore.model.WikipediaCategoryRule::copy).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview previewPromotion(
            quiz.curation.Correction correction) {
        return promoter().preview(correction);
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview promote(
            quiz.curation.Correction correction) throws Exception {
        quiz.curation.FieldRulePromoter.PromotionPreview promoted =
                promoter().promote(correction);
        unsavedModelChanges.add("Promoted " + promoted.targetType() + "."
                + promoted.field() + " ← " + promoted.sourceProperty());
        return promoted;
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview previewPromotion(
            quiz.curation.FieldSourceRecipe recipe) {
        return promoter().preview(recipe);
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview promote(
            quiz.curation.FieldSourceRecipe recipe) throws Exception {
        quiz.curation.FieldRulePromoter.PromotionPreview promoted =
                promoter().promote(recipe);
        unsavedModelChanges.add("Promoted category source " + promoted.targetType() + "."
                + promoted.field() + " ← " + promoted.sourceProperty());
        return promoted;
    }

    private ModelFieldRulePromoter promoter() {
        return new ModelFieldRulePromoter(modelFile, workingModel, this);
    }

    @Override public wikidata.explore.model.EntityKindRule entityKindRule(String className) {
        if (className == null || className.isBlank()) return null;
        try {
            wikidata.explore.model.GeneratedProjectModel model = workingModel;
            if (model == null) return null;
            wikidata.explore.model.EntityKindRule rule =
                    wikidata.explore.model.MembershipPattern.kindRule(
                            model.findClass(className), model);
            return rule == null ? null : rule.copy();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public wikidata.explore.model.FieldSourceMapping declaredSource(
            String type, String field) {
        return promoter().declaredSource(type, field);
    }

    @Override public wikidata.explore.model.FieldSourceMapping declaredFallbackSource(
            String type, String field) {
        return promoter().declaredFallbackSource(type, field);
    }

    @Override public datasource.api.SourceBinding declaredBinding(
            String type, String field, datasource.api.SourceBindingSlot slot) {
        return promoter().declaredBinding(type, field, slot);
    }
}
