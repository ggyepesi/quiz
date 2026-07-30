package quiz.transform.app;

import quiz.DatasetRegistry;
import objectview.Viewable;
import quiz.transform.ui.DomainModel;
import quiz.transform.ui.DomainWriter;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The wikidata {@link DomainWriter}: saves a transform result (the current view's
 * members) as a snapshot + {@link DatasetRegistry} entry, so it re-appears in the
 * catalog/navigator and is served by the web like a generated dataset — the
 * producer loop.
 */
public final class DomainSaver implements DomainWriter {

    @Override
    public String save(String name, Collection<? extends Viewable> members) throws Exception {
        return save(name, members, null);
    }

    @Override
    public String save(String name, Collection<? extends Viewable> members,
                       DomainModel schema) throws Exception {
        String key = sanitize(name);
        var converted = schema == null
                ? ViewableToWdo.convertDomain(members, List.of(), null)
                : ViewableToWdo.convertDomain(
                        schema.memberRoots(), schema.groupRoots(), schema);

        File file = new File(aux.Constants.wikidataDataDirectory
                + "transform/" + key + ".snapshot.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        var fieldGraph = store.saveWithFieldGraph(
                converted.memberRoots(), converted.groupRoots(), file, schema);
        Set<String> types = new LinkedHashSet<>(fieldGraph.memberTypes());

        DatasetRegistry.Dataset d = new DatasetRegistry.Dataset();
        d.name(name);
        d.key(key);
        d.snapshotPath(file.getPath());
        d.types().addAll(types);
        d.rootClass(types.isEmpty() ? "" : types.iterator().next());
        // Match the generation-pipeline registry entry (ModelBuilderFrame): count
        // the whole pool (roots + nested), stamp the save time. modelSignature stays
        // blank — a transform-view save is not model-backed.
        d.instanceCount(converted.allObjects().size());
        d.savedAt(java.time.LocalDateTime.now().toString());

        DatasetRegistry reg = DatasetRegistry.load();
        reg.upsert(d);
        reg.save();

        return "Saved \"" + name + "\"  (" + members.size() + " members, types "
                + types + ")\n" + file.getPath()
                + "\nRegistered — now in the navigator and served by the web.";
    }

    private static String sanitize(String name) {
        String s = (name == null ? "" : name).trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isBlank() ? "transform-result" : s;
    }
}
