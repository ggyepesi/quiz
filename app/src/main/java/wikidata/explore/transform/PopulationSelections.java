package wikidata.explore.transform;

import objectview.Viewable;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityRepresentations;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.Selection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Materializes each saved {@link PopulationSelection} over a loaded pool, so a loaded
 * domain offers it by name like any other selection.
 *
 * <p>A member is a loaded instance whose QID the population names AND whose configured
 * class the population's class accepts (directive 9): the same QID stamped with an
 * unrelated class is not a member. Imported populations are included — they are in the
 * effective model once its imports are resolved — and are read, never rerun.
 */
public final class PopulationSelections {
    private PopulationSelections() {}

    public static Map<String, List<Viewable>> materialize(
            GeneratedProjectModel model, Collection<WikidataDynamicObject> pool) {
        LinkedHashMap<String, List<Viewable>> result = new LinkedHashMap<>();
        if (model == null || pool == null) return result;
        for (Selection selection : model.selections()) {
            if (!(selection instanceof PopulationSelection population)) continue;
            Set<String> qids = new LinkedHashSet<>(population.instanceQids());
            LinkedHashSet<Viewable> members = new LinkedHashSet<>();
            for (WikidataDynamicObject instance : pool) {
                if (instance == null || !instance.hasTypeStamp()
                        || !qids.contains(instance.qid())) continue;
                if (EntityRepresentations.fieldAccepts(model, population.className(),
                        instance.directClassNames())) {
                    members.add(instance);
                }
            }
            result.put(population.name(), List.copyOf(members));
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
