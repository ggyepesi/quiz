package wikidata.explore.generation;

import datasource.graph.*;
import wikidata.explore.model.*;

import java.util.ArrayList;
import java.util.List;

/** Compiles explicitly traversable Wikidata entity fields into neutral graph steps. */
public final class WikidataFieldGraphTraversal {
    private WikidataFieldGraphTraversal() { }

    public static List<GraphTraversalStep> derive(GeneratedProjectModel model) {
        if (model == null) return List.of();
        List<GraphTraversalStep> result = new ArrayList<>();
        for (GeneratedClassModel owner : model.classes()) {
            if (owner == null) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                GraphTraversalStep step = derive(model, owner, field);
                if (step != null) result.add(step);
            }
        }
        return List.copyOf(result);
    }

    private static GraphTraversalStep derive(GeneratedProjectModel model,
            GeneratedClassModel owner, GeneratedFieldModel field) {
        if (owner == null
                || !WikidataFieldGraphTraversalEligibility.canCompile(model, field)) return null;
        GraphTraversalDirection direction = field.mapping().direction()
                == RuleDirection.ROOT_TO_ITEM
                ? GraphTraversalDirection.OUTGOING : GraphTraversalDirection.INCOMING;
        String id = owner.className() + "." + field.name() + ":"
                + field.mapping().propertyPid() + ":" + direction;
        return new GraphTraversalStep(id, owner.className(), field.entityClassName(),
                field.name(), new GraphRelation("wikidata", field.mapping().propertyPid()),
                direction, field.graphExpansionPolicy());
    }
}
