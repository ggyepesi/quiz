package quiz.transform.ui;

import quiz.DatasetRegistry;
import quiz.Quizable;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Saves a transform RESULT (the current view's members — a filtered subset or a
 * PROJECT-derived class) as a first-class domain: a snapshot on disk plus a
 * {@link DatasetRegistry} entry. Once registered it appears in the
 * {@link DomainCatalog} (so it re-opens in the workbench) and is served by the web
 * exactly like a generated dataset — turning the workbench into a producer.
 */
public final class DomainSaver {

    private DomainSaver() {}

    public static DatasetRegistry.Dataset save(String name,
                                               Collection<? extends Quizable> members)
            throws IOException {

        String key = sanitize(name);
        List<WikidataDynamicObject> pool = QuizableToWdo.pool(members);

        File file = new File(aux.Constants.wikidataDataDirectory
                + "transform/" + key + ".snapshot.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(pool, file);

        // Types = the distinct stamped types actually written (members + refs).
        Set<String> types = new LinkedHashSet<>();
        for (WikidataDynamicObject o : store.loadAll(file)) {
            if (o.typeName() != null && !o.typeName().isBlank()
                    && !"WikidataDynamicObject".equals(o.typeName())) {
                types.add(o.typeName());
            }
        }

        DatasetRegistry.Dataset d = new DatasetRegistry.Dataset();
        d.name(name);
        d.key(key);
        d.snapshotPath(file.getPath());
        d.types().addAll(types);
        d.rootClass(types.isEmpty() ? "" : types.iterator().next());

        DatasetRegistry reg = DatasetRegistry.load();
        reg.upsert(d);
        reg.save();
        return d;
    }

    private static String sanitize(String name) {
        String s = (name == null ? "" : name).trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isBlank() ? "transform-result" : s;
    }
}
