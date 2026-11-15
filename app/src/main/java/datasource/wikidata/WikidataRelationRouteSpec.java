package datasource.wikidata;

import datasource.graph.RelationRoute;
import datasource.persistence.RelationRouteJsonCodec;

import java.util.List;

/** Typed Wikidata interpretation of a persisted relation-route recipe. */
public record WikidataRelationRouteSpec(List<RelationRoute> routes) {
    public WikidataRelationRouteSpec {
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("At least one relation route is required");
        }
        routes.stream().flatMap(route -> route.steps().stream()).forEach(step -> {
            if (!WikidataDatasourceProvider.ID.equals(step.relation().providerId())) {
                throw new IllegalArgumentException(
                        "A Wikidata relation route cannot use provider "
                                + step.relation().providerId());
            }
            WikidataRouteRelation relation = WikidataRouteRelation.parse(step.relation());
            if (relation == null) {
                throw new IllegalArgumentException("Invalid Wikidata route relation: "
                        + step.relation().relationId());
            }
            relation.requireDirection(step.direction());
        });
    }

    public static WikidataRelationRouteSpec parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Relation routes are required");
        }
        try {
            return new WikidataRelationRouteSpec(RelationRouteJsonCodec.parse(
                    json, WikidataDatasourceProvider.ID));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Invalid Wikidata relation routes: " + invalid.getMessage(), invalid);
        }
    }

    public String toJson() {
        return RelationRouteJsonCodec.write(routes, WikidataDatasourceProvider.ID);
    }

}
