package quiz.transform.app;

import quiz.DatasetRegistry;
import objectview.Viewable;
import domain.DomainModel;
import quiz.transform.ui.DomainWriter;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import quiz.transform.ui.ProjectBacking;

/**
 * The wikidata {@link DomainWriter}: saves a transform result (the current view's
 * members) as a snapshot + {@link DatasetRegistry} entry, so it re-appears in the
 * catalog/navigator and is served by the web like a generated dataset — the
 * producer loop.
 */
public final class DomainSaver implements DomainWriter {

    @Override
    public String describeSave(String name, Collection<? extends Viewable> members,
                               DomainModel schema) {
        int count = members == null ? 0 : members.size();
        ProjectBacking backing = backingFor(name, schema);
        String kind = backing == null ? "domain" : backing.projectKind().toString().toLowerCase();
        String files = backing == null
                ? destination(name).getPath()
                : backing.modelFile().getPath() + " and " + backing.snapshotFile().getPath();
        List<String> changes = backing == null ? List.of() : backing.unsavedModelChanges();
        return "Save " + kind + " \"" + name + "\" with " + count + " instance"
                + (count == 1 ? "" : "s") + ", types "
                + (schema == null ? List.of() : schema.servedTypes())
                + ", and their model to " + files + "."
                + (changes.isEmpty() ? ""
                        : "\nUnsaved model changes written by this save:\n  - "
                                + String.join("\n  - ", changes));
    }

    @Override
    public String save(String name, Collection<? extends Viewable> members,
                       DomainModel schema) throws Exception {
        String key = sanitize(name);
        if (schema == null) {
            throw new IllegalArgumentException("A domain schema is required");
        }
        ProjectBacking backing = backingFor(name, schema);
        // The working model, with this session's edits — not a fresh read of the file,
        // which would drop them. Refused before anything is written, so a save that
        // would overwrite another application's model save writes nothing at all.
        wikidata.explore.model.GeneratedProjectModel owner =
                backing == null ? null : backing.projectModel();
        if (owner != null) {
            String conflict = backing.modelWriteConflict();
            if (!conflict.isEmpty()) throw new IllegalStateException(conflict);
            addSubclasses(owner, schema);
        }
        var converted = ViewableToWdo.convertDomain(
                schema.memberRoots(), schema.groupRootBindings(), schema);

        File file = backing == null ? destination(name) : backing.snapshotFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        var persistedGroups = converted.groupRootBindings().stream()
                .map(binding -> new WikidataDynamicObjectJsonStore.GroupRootBinding(
                        binding.memberType(), binding.root()))
                .toList();
        var fieldGraph = store.saveWithGroupRootBindings(
                converted.memberRoots(), persistedGroups, file, schema);
        Set<String> types = new LinkedHashSet<>(fieldGraph.memberTypes());

        DatasetRegistry.Dataset d = new DatasetRegistry.Dataset();
        d.name(name);
        d.key(backing == null ? key : dataset.DomainStorage.key(name));
        d.snapshotPath(file.getPath());
        d.types().addAll(types);
        d.rootClass(types.isEmpty() ? "" : types.iterator().next());
        // A model's snapshot is local working data. Keep it loadable in TransformApp,
        // but do not turn Save model into publishing a QuizFactory/web domain.
        boolean served = backing == null || serves(backing.projectKind());
        d.served(served);
        if (owner != null) {
            backing.writeProjectModel();
            d.modelPath(backing.modelFile().getPath());
            File ruleTree = new File(backing.modelFile().getParentFile(),
                    backing.modelFile().getName().replace(".model.json", ".ruletree.json"));
            d.ruletreePath(ruleTree.getPath());
            d.rootClass(owner.rootClass().className());
            d.modelSignature(wikidata.explore.generation.DomainSave.signature(owner));
        }
        // Stamp the save time. A detached transform result has no model signature;
        // a model-backed save above carries the owner explicitly.
        d.savedAt(java.time.LocalDateTime.now().toString());

        DatasetRegistry reg = DatasetRegistry.load();
        // This may be a transformed view of a ModelBuilder domain with the same key.
        // Keep that domain's model/rule-tree identity while replacing its served snapshot.
        reg.upsertSnapshot(d);
        if (backing != null) reg.removeUnbackedNamedExcept(name, d.key());
        reg.save();

        // The build list is the user's: Save never adds or removes its steps. It says which
        // steps a saved transformation still needs, for the user to place in ModelBuilder.
        List<String> buildSteps = owner == null ? List.of()
                : wikidata.explore.model.GeneratedProjectModelValidator
                        .transformationBuildProblems(owner).stream()
                        .map(problem -> problem.message()).toList();
        return "Saved \"" + name + "\"  (" + members.size() + " members, types "
                + types + ")\n" + file.getPath()
                + (buildSteps.isEmpty() ? ""
                        : "\nBuild list (edit it in ModelBuilder's project overview):\n  - "
                                + String.join("\n  - ", buildSteps))
                + (served
                        ? "\nRegistered — now in QuizFactory, the navigator, and the web."
                        : "\nRegistered as model working data — loadable in TransformApp, "
                                + "not offered by QuizFactory or the web.");
    }

    static boolean serves(
            wikidata.explore.model.GeneratedProjectModel.ProjectKind kind) {
        return kind == wikidata.explore.model.GeneratedProjectModel.ProjectKind.DOMAIN;
    }

    private static ProjectBacking backingFor(String name, DomainModel schema) {
        if (schema == null) return null;
        ProjectBacking backing = schema.capability(ProjectBacking.class);
        return backing != null && backing.modelFile() != null
                && backing.modelFile().isFile()
                && java.util.Objects.equals(name == null ? "" : name.trim(),
                        backing.projectName()) ? backing : null;
    }

    /** Persist only semantic subclasses; projected/joined result classes have no base. */
    static void addSubclasses(
            wikidata.explore.model.GeneratedProjectModel model, DomainModel schema) {
        for (String type : schema.types()) {
            if (type == null || type.isBlank()) continue;
            String base = schema.baseType(type);
            if (base == null || base.isBlank() || model.findClass(base) == null) continue;
            wikidata.explore.model.GeneratedClassModel subclass = model.findClass(type);
            if (subclass == null) {
                subclass = new wikidata.explore.model.GeneratedClassModel(type);
                subclass.baseClassName(base);
                subclass.clearIndependentPopulation();
                wikidata.explore.model.ClassSourceBindings.synchronize(subclass);
                model.addClass(subclass);
            }
            if (schema instanceof quiz.transform.ui.WorkingDomain working) {
                var saved = working.subclassProducer(type);
                if (saved instanceof quiz.transform.ui.WorkingDomain.SavedSubclassTransformation
                        transformation) {
                    saveTransformation(model, subclass, transformation);
                } else if (saved
                        instanceof quiz.transform.ui.WorkingDomain.SavedManualSubclass manual) {
                    saveManualSubclass(model, subclass, manual);
                }
            }
        }
    }

    private static void saveTransformation(
            wikidata.explore.model.GeneratedProjectModel model,
            wikidata.explore.model.GeneratedClassModel output,
            quiz.transform.ui.WorkingDomain.SavedSubclassTransformation saved) {
        wikidata.explore.model.GeneratedClassModel input =
                model.findClass(saved.inputClass());
        if (input == null) throw new IllegalArgumentException(
                "Transformation input class no longer exists: " + saved.inputClass());
        String selectionName = output.className() + "Population";
        wikidata.explore.model.Selection named = model.findSelection(selectionName);
        // The publication selection is reused only when it already publishes this class.
        // A manual subclass's frozen population of the same name is over the BASE class;
        // retargeting it would silently change what every graph start or bound naming it
        // means, and keep its hand-picked QIDs until the next publish.
        if (named != null && !(named instanceof wikidata.explore.model.PopulationSelection
                existing && existing.className().equals(output.className()))) {
            throw new IllegalArgumentException("Selection '" + selectionName
                    + "' already exists" + (named instanceof wikidata.explore.model
                            .PopulationSelection existing
                            ? " as a population of " + existing.className() : "")
                    + ", so it cannot become the published population of "
                    + output.className() + ". Rename or remove it in ModelBuilder first;"
                    + " nothing was saved.");
        }
        output.populationMode(
                wikidata.explore.model.ClassPopulationMode.MATERIALIZED_TRANSFORM);
        output.subclassCondition(wikidata.explore.model.SubclassCondition.none());
        wikidata.explore.model.TransformationClassSource transformation =
                new wikidata.explore.model.TransformationClassSource();
        transformation.inputClassReference(input.declarationId(), input.className());
        for (var condition : saved.conditions()) {
            transformation.addStep(wikidata.explore.model.TransformationStep.filter(
                    condition.field().field(), condition.operator(),
                    condition.value(), condition.value2()));
        }
        output.transformationSource(transformation);

        wikidata.explore.model.PopulationSelection population =
                named instanceof wikidata.explore.model.PopulationSelection existing
                        ? existing : new wikidata.explore.model.PopulationSelection(selectionName);
        population.className(output.className());
        model.replaceSelection(population);
    }

    private static void saveManualSubclass(
            wikidata.explore.model.GeneratedProjectModel model,
            wikidata.explore.model.GeneratedClassModel output,
            quiz.transform.ui.WorkingDomain.SavedManualSubclass saved) {
        wikidata.explore.model.GeneratedClassModel input = model.findClass(saved.inputClass());
        if (input == null) throw new IllegalArgumentException(
                "Manual subclass base class no longer exists: " + saved.inputClass());
        wikidata.explore.model.Selection existing = model.findSelection(saved.populationName());
        if (existing != null
                && !(existing instanceof wikidata.explore.model.PopulationSelection)) {
            throw new IllegalArgumentException("Selection '" + saved.populationName()
                    + "' already exists and is not a population selection.");
        }
        wikidata.explore.model.PopulationSelection population =
                new wikidata.explore.model.PopulationSelection(saved.populationName());
        population.className(input.className());
        population.instanceQids(saved.qids());
        model.replaceSelection(population);
        population = (wikidata.explore.model.PopulationSelection)
                model.findSelection(saved.populationName());

        output.populationMode(wikidata.explore.model.ClassPopulationMode.GENERATED);
        output.subclassCondition(wikidata.explore.model.SubclassCondition.inPopulation(
                population.name(), population.declarationId()));
    }

    private static String sanitize(String name) {
        String s = (name == null ? "" : name).trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isBlank() ? "transform-result" : s;
    }

    /** Exact file written by {@link #save}; plans can name it before work starts. */
    public static File destination(String name) {
        return new File(aux.Constants.wikidataDataDirectory
                + "transform/" + sanitize(name) + ".snapshot.json");
    }
}
