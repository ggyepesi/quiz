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
        quiz.curation.FieldRulePromoter {

    private final ManualCuration curation;
    private final Collection<? extends Viewable> memberRoots;
    private final List<objectview.viewconfig.DomainGroupRoot> groupRootBindings;
    private final java.io.File modelFile;

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
    }

    @Override public ManualCuration curation() { return curation; }


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
        curation.corrections().stream().filter(c -> c.source() != null
                        && c.source().kind() != null && !c.source().kind().isBlank())
                .map(CuratableDomain::sourceSelectionName).forEach(names::add);
        return List.copyOf(names);
    }
    @Override public List<Viewable> selectionMembers(String name) {
        List<Viewable> inherited = base.selectionMembers(name);
        if (!inherited.isEmpty() || name == null || !name.startsWith("Source / ")) {
            return inherited;
        }
        java.util.Set<String> ids = curation.corrections().stream()
                .filter(c -> name.equals(sourceSelectionName(c)))
                .map(quiz.curation.Correction::qid)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return instances().stream().filter(value -> ids.contains(value.getIdentifier()))
                .map(Viewable.class::cast).distinct().toList();
    }

    private static String sourceSelectionName(quiz.curation.Correction correction) {
        String owner = correction.type() == null || correction.type().isBlank()
                ? "*" : correction.type();
        return "Source / " + correction.source().kind() + " / " + owner + "."
                + correction.field();
    }

    private wikidata.explore.model.GeneratedProjectModel loadModel() {
        if (modelFile == null || !modelFile.isFile()) return null;
        try { return new wikidata.explore.model.GeneratedProjectModelStore().load(modelFile); }
        catch (Exception ignored) { return null; }
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
            wikidata.explore.model.GeneratedProjectModel model = loadModel();
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
        return new ModelFieldRulePromoter(modelFile, this).preview(correction);
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview promote(
            quiz.curation.Correction correction) throws Exception {
        return new ModelFieldRulePromoter(modelFile, this).promote(correction);
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview previewPromotion(
            quiz.curation.FieldSourceRecipe recipe) {
        return new ModelFieldRulePromoter(modelFile, this).preview(recipe);
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview promote(
            quiz.curation.FieldSourceRecipe recipe) throws Exception {
        return new ModelFieldRulePromoter(modelFile, this).promote(recipe);
    }

    @Override public wikidata.explore.model.EntityKindRule entityKindRule(String className) {
        if (className == null || className.isBlank()) return null;
        try {
            wikidata.explore.model.GeneratedProjectModel model = loadModel();
            if (model == null) return null;
            return model.entityKindRules().stream()
                    .filter(rule -> rule != null && className.equals(rule.className()))
                    .filter(wikidata.explore.model.EntityKindRule::isConfigured)
                    .findFirst().map(wikidata.explore.model.EntityKindRule::copy).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public wikidata.explore.model.FieldSourceMapping declaredSource(
            String type, String field) {
        return new ModelFieldRulePromoter(modelFile, this).declaredSource(type, field);
    }

    @Override public wikidata.explore.model.FieldSourceMapping declaredFallbackSource(
            String type, String field) {
        return new ModelFieldRulePromoter(modelFile, this).declaredFallbackSource(type, field);
    }

    @Override public datasource.api.SourceBinding declaredBinding(
            String type, String field, datasource.api.SourceBindingSlot slot) {
        return new ModelFieldRulePromoter(modelFile, this).declaredBinding(type, field, slot);
    }
}
