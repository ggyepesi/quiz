package domain.build;

import domain.datasource.graph.GraphExpansionPattern;
import domain.datasource.graph.GraphExpansionPlan;
import domain.datasource.graph.GraphExpansionPolicy;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

/** One Wikidata model-to-neutral-plan compiler shared by UI and execution. */
public final class WikidataGraphExpansionPlan {
    private WikidataGraphExpansionPlan() { }

    public static GraphExpansionPlan compile(GeneratedProjectModel model) {
        if (model == null) return GraphExpansionPlan.EMPTY;
        List<GraphExpansionPattern> patterns = new ArrayList<>();
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz == null || clazz.statementSource() == null
                    || clazz.statementSource().graphExpansionPolicy()
                    != GraphExpansionPolicy.CURATED) continue;
            GraphExpansionPattern pattern = WikidataGraphDiscoveryState
                    .structuralPattern(model, clazz.className());
            if (pattern != null) patterns.add(pattern);
        }
        return new GraphExpansionPlan(patterns, WikidataFieldGraphTraversal.derive(model));
    }
}
