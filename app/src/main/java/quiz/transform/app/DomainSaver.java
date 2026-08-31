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

/**
 * The wikidata {@link DomainWriter}: saves a transform result (the current view's
 * members) as a snapshot + {@link DatasetRegistry} entry, so it re-appears in the
 * catalog/navigator and is served by the web like a generated dataset — the
 * producer loop.
 */
public final class DomainSaver implements DomainWriter {

    @Override
    public String save(String name, Collection<? extends Viewable> members,
                       DomainModel schema) throws Exception {
        String key = sanitize(name);
        if (schema == null) {
            throw new IllegalArgumentException("A domain schema is required");
        }
        var converted = ViewableToWdo.convertDomain(
                schema.memberRoots(), schema.groupRootBindings(), schema);

        File file = new File(aux.Constants.wikidataDataDirectory
                + "transform/" + key + ".snapshot.json");
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
        d.key(key);
        d.snapshotPath(file.getPath());
        d.types().addAll(types);
        d.rootClass(types.isEmpty() ? "" : types.iterator().next());
        // Stamp the save time. modelSignature stays blank — a transform-view save is
        // not model-backed.
        d.savedAt(java.time.LocalDateTime.now().toString());

        DatasetRegistry reg = DatasetRegistry.load();
        // This may be a transformed view of a ModelBuilder domain with the same key.
        // Keep that domain's model/rule-tree identity while replacing its served snapshot.
        reg.upsertSnapshot(d);
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
