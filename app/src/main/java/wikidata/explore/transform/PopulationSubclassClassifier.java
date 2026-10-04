package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataObjectGraph;
import wikidata.explore.model.EntityRepresentations;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.Selection;
import wikidata.explore.model.SubclassCondition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Classifies already-reached base instances by a saved population. */
public final class PopulationSubclassClassifier {

    public record Result(int changed, List<WikidataDynamicObject> newlyClassified) { }

    private PopulationSubclassClassifier() { }

    public static Result apply(
            GeneratedProjectModel model, List<WikidataDynamicObject> roots) {
        if (model == null || roots == null || roots.isEmpty()) {
            return new Result(0, List.of());
        }
        int changed = 0;
        List<WikidataDynamicObject> newly = new ArrayList<>();
        List<WikidataDynamicObject> graph = WikidataObjectGraph.reachable(roots);
        for (GeneratedClassModel subclass : model.classes()) {
            if (subclass == null || !subclass.hasBase()) continue;
            SubclassCondition condition = subclass.subclassCondition();
            if (!condition.populationBased() || !condition.configured()) continue;
            Selection selected = model.findSelection(condition.selectionName());
            if (!(selected instanceof PopulationSelection population)) continue;
            Set<String> populationQids = new HashSet<>(population.instanceQids());
            for (WikidataDynamicObject candidate : graph) {
                if (candidate == null || candidate.qid().isBlank()) continue;
                boolean currentlySubclass = candidate.directClassNames()
                        .contains(subclass.className());
                boolean isBase = currentlySubclass || EntityRepresentations.fieldAccepts(
                        model, subclass.baseClassName(), candidate.directClassNames());
                if (!isBase) continue;
                boolean inside = populationQids.contains(candidate.qid());
                boolean matches = condition.kind() == SubclassCondition.Kind.IN_POPULATION
                        ? inside : !inside;
                if (matches && !currentlySubclass) {
                    candidate.assignSubclass(subclass.className(), subclass.baseClassName());
                    newly.add(candidate);
                    changed++;
                } else if (!matches && currentlySubclass) {
                    candidate.removeClass(subclass.className());
                    candidate.assignClass(subclass.baseClassName());
                    changed++;
                }
            }
        }
        return new Result(changed, List.copyOf(newly));
    }
}
