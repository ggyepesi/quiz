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
        return "Save " + kind + " \"" + name + "\" with " + count + " instance"
                + (count == 1 ? "" : "s") + ", types "
                + (schema == null ? List.of() : schema.servedTypes())
                + ", and their model to " + files + ".";
    }

    @Override
    public String save(String name, Collection<? extends Viewable> members,
                       DomainModel schema) throws Exception {
        String key = sanitize(name);
        if (schema == null) {
            throw new IllegalArgumentException("A domain schema is required");
        }
        ProjectBacking backing = backingFor(name, schema);
        wikidata.explore.model.GeneratedProjectModel owner = backing == null ? null
                : new wikidata.explore.model.GeneratedProjectModelStore()
                        .load(backing.modelFile());
        if (owner != null) addSubclasses(owner, schema);
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
        if (owner != null) {
            new wikidata.explore.model.GeneratedProjectModelStore().save(owner,
                    backing.modelFile());
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

        return "Saved \"" + name + "\"  (" + members.size() + " members, types "
                + types + ")\n" + file.getPath()
                + "\nRegistered — now in the navigator and served by the web.";
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
            if (type == null || type.isBlank() || model.findClass(type) != null) continue;
            String base = schema.baseType(type);
            if (base == null || base.isBlank() || model.findClass(base) == null) continue;
            wikidata.explore.model.GeneratedClassModel subclass =
                    new wikidata.explore.model.GeneratedClassModel(type);
            subclass.baseClassName(base);
            subclass.clearIndependentPopulation();
            wikidata.explore.model.ClassSourceBindings.synchronize(subclass);
            model.addClass(subclass);
        }
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
