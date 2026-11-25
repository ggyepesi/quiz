package domain.compile.transform;

import objectview.Viewable;
import domain.runtime.WikidataDynamicObject;
import domain.config.EntityRepresentations;
import domain.config.GeneratedProjectModel;
import domain.config.PopulationSelection;
import domain.config.Selection;

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
            result.put(population.name(), members(model, population, pool));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public static List<Viewable> members(GeneratedProjectModel model, PopulationSelection population,
            Collection<? extends Viewable> pool) {
        Set<String> qids = new LinkedHashSet<>(population.instanceQids());
        LinkedHashSet<Viewable> members = new LinkedHashSet<>();
        for (Viewable value : pool) {
            if (!(value instanceof WikidataDynamicObject instance) || !instance.hasTypeStamp()
                    || !qids.contains(instance.qid())) continue;
            if (EntityRepresentations.fieldAccepts(model, population.className(),
                    instance.directClassNames())) {
                members.add(instance);
            }
        }
        return List.copyOf(members);
    }
}
