package domain.transform.app;

import objectview.Viewable;
import domain.curation.Curatable;
import domain.curation.ManualCuration;
import domain.DelegatingDomainModel;
import domain.DomainField;
import domain.DomainModel;
import domain.transform.ui.SchemaView;
import objectview.field.FieldSchema;
import objectview.viewconfig.FieldTypeSource;

import javax.swing.JComponent;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import domain.DomainSchemas;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.persistence.ProjectSaveGuard;
import domain.config.PopulationSelection;
import domain.compile.transform.PopulationSelections;
import domain.compile.transform.RoleSelections;
import dataset.DomainStorage;

/**
 * A {@link DomainModel} that also carries its {@link ManualCuration} store, so the
 * workbench can offer curation. Delegates every schema/instance query to the compiled
 * domain — it only adds the {@link Curatable} capability (and forwards {@link
 * SchemaView} when the base has one).
 */
final class CuratableDomain extends DelegatingDomainModel implements Curatable,
        domain.curation.FieldRulePromoter, domain.transform.ui.PopulationSelectionStore,
        domain.transform.ui.ProjectBacking, domain.curation.CuratedInstanceSink {

    private final ManualCuration curation;
    private final Collection<? extends Viewable> memberRoots;
    private final java.util.List<Viewable> curatedMemberRoots = new java.util.ArrayList<>();
    private final List<objectview.viewconfig.DomainGroupRoot> groupRootBindings;
    private final java.io.File modelFile;
    /** The project model as read when this domain opened, edited in memory since.
     *  Null when there is no model file, or it could not be read. */
    private final domain.config.GeneratedProjectModel workingModel;
    private final ProjectSaveGuard saveGuard;
    private final List<String> unsavedModelChanges = new java.util.ArrayList<>();
    private final java.util.Map<String, List<Viewable>> editedPopulationMembers =
            new java.util.LinkedHashMap<>();

    private record Backing(GeneratedProjectModel model, ProjectSaveGuard guard) { }

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
        this(base, curation, memberRoots, groupRootBindings, modelFile, readBacking(modelFile));
    }

    CuratableDomain(DomainModel base, ManualCuration curation,
            Collection<? extends Viewable> memberRoots,
            List<objectview.viewconfig.DomainGroupRoot> groupRootBindings,
            java.io.File modelFile, GeneratedProjectModel model, ProjectSaveGuard guard) {
        this(base, curation, memberRoots, groupRootBindings, modelFile, new Backing(model, guard));
    }

    private CuratableDomain(DomainModel base, ManualCuration curation,
            Collection<? extends Viewable> memberRoots,
            List<objectview.viewconfig.DomainGroupRoot> groupRootBindings,
            java.io.File modelFile, Backing backing) {
        super(base);
        this.curation = curation;
        this.memberRoots = memberRoots == null ? List.of() : List.copyOf(memberRoots);
        this.groupRootBindings = groupRootBindings == null
                ? List.of() : List.copyOf(groupRootBindings);
        this.modelFile = modelFile;
        this.workingModel = backing.model();
        this.saveGuard = backing.guard();
    }

    private static Backing readBacking(java.io.File modelFile) {
        if (modelFile == null) return new Backing(null, null);
        try {
            var parent = modelFile.getAbsoluteFile().getParentFile();
            String key = modelFile.getName().replaceFirst("\\.model\\.json$", "");
            var guard = ProjectSaveGuard.capture(DomainStorage.in(parent), key,
                    modelFile, snapshotBeside(modelFile));
            var model = new GeneratedProjectModelStore().load(modelFile);
            guard.check();
            return new Backing(model, guard);
        } catch (Exception unreadable) {
            return new Backing(null, null);
        }
    }

    @Override public ManualCuration curation() { return curation; }

    @Override public <T extends domain.DomainCapability> T capability(Class<T> type) {
        if (type == domain.transform.ui.ProjectBacking.class && modelFile == null) return null;
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
        for (domain.curation.FieldDeclaration declaration : curation.fieldDeclarations()) {
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
    @Override public Collection<? extends Viewable> memberRoots() {
        java.util.List<Viewable> roots = new java.util.ArrayList<>();
        memberRoots.stream().filter(Viewable.class::isInstance)
                .map(Viewable.class::cast).forEach(roots::add);
        for (Viewable value : curatedMemberRoots) {
            if (!roots.contains(value)) roots.add(value);
        }
        return List.copyOf(roots);
    }

    @Override public domain.curation.CuratedInstances.Result materialize(
            java.util.Collection<domain.curation.CuratedInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return new domain.curation.CuratedInstances.Result(List.of(), List.of());
        }
        if (!(base.instances() instanceof java.util.List<?> raw)) {
            throw new IllegalStateException("This domain's instance pool is not mutable");
        }
        @SuppressWarnings("unchecked")
        java.util.List<domain.runtime.WikidataDynamicObject> pool =
                (java.util.List<domain.runtime.WikidataDynamicObject>) raw;
        domain.curation.CuratedInstances.Result added =
                domain.curation.CuratedInstances.apply(pool, instances);
        for (Viewable value : added.instances()) {
            if (!curatedMemberRoots.contains(value)) curatedMemberRoots.add(value);
        }
        return added;
    }
    @Override public List<String> selectionNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(base.selectionNames());
        names.addAll(editedPopulationMembers.keySet());
        names.addAll(curationSourceSelections().keySet());
        return List.copyOf(names);
    }
    @Override public List<Viewable> selectionMembers(String name) {
        List<Viewable> edited = editedPopulationMembers.get(name);
        if (edited != null) return edited;
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
        for (domain.curation.Correction correction : curation.corrections()) {
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

    private static String sourceSelectionName(domain.curation.Correction correction) {
        String owner = correction.type() == null || correction.type().isBlank()
                ? "*" : correction.type();
        return "Source / " + correction.source().kind() + " / " + owner + "."
                + correction.field();
    }


    @Override public java.io.File modelFile() { return modelFile; }

    @Override public ProjectSaveGuard saveGuard() { return saveGuard; }

    @Override public domain.config.GeneratedProjectModel projectModel() {
        return workingModel;
    }

    @Override public String projectName() {
        domain.config.GeneratedProjectModel model = workingModel;
        return model == null ? "" : model.name();
    }

    @Override public domain.config.GeneratedProjectModel.ProjectKind projectKind() {
        domain.config.GeneratedProjectModel model = workingModel;
        return model == null
                ? domain.config.GeneratedProjectModel.ProjectKind.DOMAIN
                : model.projectKind();
    }

    @Override public java.io.File snapshotFile() {
        if (modelFile == null) return null;
        return snapshotBeside(modelFile);
    }

    static java.io.File snapshotBeside(java.io.File modelFile) {
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
        domain.config.PopulationSelection selection =
                new domain.config.PopulationSelection(name);
        selection.className(className);
        selection.instanceQids(qids);
        if (!selection.isConfigured()) {
            throw new IllegalArgumentException(
                    "A population selection needs a name, a class and at least one QID");
        }
        workingModel.replaceSelection(selection);
        populationChanged(selection);
    }

    @Override public List<String> unsavedModelChanges() {
        return List.copyOf(unsavedModelChanges);
    }

    @Override public void recordModelChange(String change) {
        unsavedModelChanges.add(change);
    }

    @Override public void populationChanged(PopulationSelection population) {
        // As at load, a semantic role keeps its name if it occupies the same scope.
        if (RoleSelections.definitions(workingModel).stream()
                .noneMatch(role -> role.key().equals(population.name()))) {
            editedPopulationMembers.put(population.name(),
                    PopulationSelections.members(workingModel, population, instances()));
        }
        recordModelChange("Population selection \"" + population.name() + "\": "
                + population.instanceQids().size() + " " + population.className() + " instance QIDs");
    }

    @Override public String modelWriteConflict() {
        if (workingModel == null || saveGuard == null) {
            return "This domain has no readable model file.";
        }
        try {
            saveGuard.check();
            return "";
        } catch (ProjectSaveGuard.Conflict conflict) {
            return conflict.getMessage();
        } catch (java.io.IOException unreadable) {
            return "The model file cannot be read: " + unreadable.getMessage();
        }
    }

    @Override public void writeProjectModel() throws Exception {
        saveProject(() -> { }, () -> null);
    }

    @Override public <T> T saveProject(Runnable prepare,
            ProjectSaveGuard.Write<T> saveSnapshot) throws Exception {
        if (workingModel == null || saveGuard == null) {
            throw new IllegalStateException("This domain has no readable model file.");
        }
        T result = saveGuard.write(() -> {
            prepare.run();
            new GeneratedProjectModelStore().save(workingModel, modelFile);
            return saveSnapshot.run();
        });
        unsavedModelChanges.clear();
        return result;
    }

    @Override public boolean exposesEntityUniverse() { return base.exposesEntityUniverse(); }
    @Override public boolean entityOrigin(String type, objectview.field.FieldPath path) {
        return base.entityOrigin(type, path);
    }
    @Override public List<? extends objectview.group.ViewableGroup<?>> groupRoots() {
        // Derived from THIS domain's bindings, not the base's: the curated domain is
        // constructed with its own group roots.
        return groupRootBindings().stream()
                .<objectview.group.ViewableGroup<?>>map(objectview.viewconfig.DomainGroupRoot::root)
                .toList();
    }
    @Override public List<objectview.viewconfig.DomainGroupRoot> groupRootBindings() {
        return groupRootBindings;
    }
    @Override public Class<? extends Viewable> universe() { return base.universe(); }

    @Override public domain.config.WikipediaCategoryRule wikipediaCategoryRule(
            String type, String field) {
        try {
            domain.config.GeneratedProjectModel model = workingModel;
            if (model == null) return null;
            domain.config.GeneratedClassModel owner = model.findClass(type);
            if (owner == null) return null;
            return owner.effectiveFields(model).stream()
                    .filter(value -> value != null && java.util.Objects.equals(field, value.name()))
                    .map(domain.config.GeneratedFieldModel::wikipediaCategoryRule)
                    .filter(java.util.Objects::nonNull).findFirst()
                    .map(domain.config.WikipediaCategoryRule::copy).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public domain.curation.FieldRulePromoter.PromotionPreview previewPromotion(
            domain.curation.Correction correction) {
        return promoter().preview(correction);
    }

    @Override public domain.curation.FieldRulePromoter.PromotionPreview promote(
            domain.curation.Correction correction) throws Exception {
        domain.curation.FieldRulePromoter.PromotionPreview promoted =
                promoter().promote(correction);
        unsavedModelChanges.add("Promoted " + promoted.targetType() + "."
                + promoted.field() + " ← " + promoted.sourceProperty());
        return promoted;
    }

    @Override public domain.curation.FieldRulePromoter.PromotionPreview previewPromotion(
            domain.curation.FieldSourceRecipe recipe) {
        return promoter().preview(recipe);
    }

    @Override public domain.curation.FieldRulePromoter.PromotionPreview promote(
            domain.curation.FieldSourceRecipe recipe) throws Exception {
        domain.curation.FieldRulePromoter.PromotionPreview promoted =
                promoter().promote(recipe);
        unsavedModelChanges.add("Configured " + promoted.targetType() + "."
                + promoted.field() + " from " + promoted.sourceKind() + " "
                + promoted.sourceProperty());
        return promoted;
    }

    private ModelFieldRulePromoter promoter() {
        return new ModelFieldRulePromoter(modelFile, workingModel, this);
    }

    @Override public domain.config.EntityKindRule entityKindRule(String className) {
        if (className == null || className.isBlank()) return null;
        try {
            domain.config.GeneratedProjectModel model = workingModel;
            if (model == null) return null;
            domain.config.EntityKindRule rule =
                    domain.config.MembershipPattern.kindRule(
                            model.findClass(className), model);
            return rule == null ? null : rule.copy();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public domain.config.FieldSourceMapping declaredSource(
            String type, String field) {
        return promoter().declaredSource(type, field);
    }

    @Override public domain.config.FieldSourceMapping declaredFallbackSource(
            String type, String field) {
        return promoter().declaredFallbackSource(type, field);
    }

    @Override public domain.datasource.api.SourceBinding declaredBinding(
            String type, String field, domain.datasource.api.SourceBindingSlot slot) {
        return promoter().declaredBinding(type, field, slot);
    }

    @Override public java.util.List<domain.datasource.api.SourceBinding> declaredBindings(
            String type, String field,
            domain.datasource.api.SourceBindingSlot slot) {
        return promoter().declaredBindings(type, field, slot);
    }
}
