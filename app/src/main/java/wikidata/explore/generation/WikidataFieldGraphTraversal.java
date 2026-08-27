package wikidata.explore.generation;

import datasource.graph.*;
import wikidata.WikidataIds;
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
                GraphTraversalStep step = derive(owner, field);
                if (step != null) result.add(step);
            }
        }
        return List.copyOf(result);
    }

    public static GraphTraversalStep derive(
            GeneratedClassModel owner, GeneratedFieldModel field) {
        if (owner == null || field == null
                || field.graphExpansionPolicy() == GraphExpansionPolicy.NONE
                || field.type() != FieldType.ENTITY
                || field.entityClassName().isBlank()
                || !WikidataIds.isPid(field.mapping().propertyPid())) return null;
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
