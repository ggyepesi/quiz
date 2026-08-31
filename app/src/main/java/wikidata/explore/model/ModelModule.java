package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable-versioned schema declarations shared by domain models. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ModelModule {
    private String moduleId = "";
    private String version = "";
    private String contentDigest = "";
    private final List<ModelModuleImport> imports = new ArrayList<>();
    private final List<GeneratedClassModel> classes = new ArrayList<>();
    private final List<Selection> selections = new ArrayList<>();
    private final List<EntityKindRule> entityKindRules = new ArrayList<>();

    public ModelModule() { }

    public ModelModule(String moduleId, String version) {
        moduleId(moduleId);
        version(version);
    }

    public String moduleId() { return clean(moduleId); }
    public void moduleId(String value) { moduleId = clean(value); }
    public String version() { return clean(version); }
    public void version(String value) { version = clean(value); }
    public String contentDigest() { return clean(contentDigest); }
    public void contentDigest(String value) { contentDigest = clean(value); }
    public String coordinate() { return moduleId() + "@" + version(); }

    public List<ModelModuleImport> imports() { return Collections.unmodifiableList(imports); }
    public void addImport(ModelModuleImport dependency) {
        if (dependency != null) imports.add(dependency);
    }
    public List<GeneratedClassModel> classes() {
        return Collections.unmodifiableList(classes);
    }
    public void addClass(GeneratedClassModel declaration) {
        if (declaration != null) classes.add(declaration);
    }
    public List<Selection> selections() { return Collections.unmodifiableList(selections); }
    public void addSelection(Selection declaration) {
        if (declaration != null) selections.add(declaration);
    }
    public List<EntityKindRule> entityKindRules() {
        return Collections.unmodifiableList(entityKindRules);
    }
    public void addEntityKindRule(EntityKindRule rule) {
        if (rule != null) entityKindRules.add(rule);
    }

    /** All declarations exported by this first module format. */
    public List<String> declarationIds() {
        List<String> ids = new ArrayList<>();
        classes.stream().map(GeneratedClassModel::declarationId).forEach(ids::add);
        selections.stream().map(Selection::declarationId).forEach(ids::add);
        return ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    }

    /** A temporary project view lets the one existing identity normalizer resolve every
     * internal class/selection reference; modules do not invent a second resolver. */
    GeneratedProjectModel declarationsProject() {
        if (classes.isEmpty()) {
            throw new IllegalStateException("Module " + coordinate() + " declares no classes");
        }
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("module " + coordinate());
        project.rootClass(classes.getFirst().copy());
        for (int i = 1; i < classes.size(); i++) project.addClass(classes.get(i).copy());
        selections.stream().map(Selection::copy).forEach(project::addSelection);
        entityKindRules.stream().map(EntityKindRule::copy)
                .forEach(project::addEntityKindRule);
        project.ensureDeclarationIdentities();
        return project;
    }

    void normalizeDeclarations() {
        if (moduleId().isBlank() || version().isBlank()) {
            throw new IllegalStateException("Module id and version are required");
        }
        for (GeneratedClassModel clazz : classes) {
            if (clazz.declarationId().isBlank()) {
                clazz.declarationId(DeclarationIds.module(moduleId(), "class", clazz.className()));
            }
        }
        for (Selection selection : selections) {
            if (selection.declarationId().isBlank()) {
                selection.declarationId(
                        DeclarationIds.module(moduleId(), "selection", selection.name()));
            }
        }
        GeneratedProjectModel normalized = declarationsProject();
        classes.clear();
        normalized.classes().stream().map(GeneratedClassModel::copy).forEach(classes::add);
        selections.clear();
        normalized.selections().stream().map(Selection::copy).forEach(selections::add);
        entityKindRules.clear();
        normalized.entityKindRules().stream().map(EntityKindRule::copy)
                .forEach(entityKindRules::add);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
