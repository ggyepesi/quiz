package domain.datasource.wikidata;

import domain.datasource.graph.RelationRoute;
import domain.datasource.persistence.RelationRouteJsonCodec;

import java.util.List;

/** Typed Wikidata interpretation of a persisted relation-route recipe. */
public record WikidataRelationRouteSpec(List<RelationRoute> routes) {
    public WikidataRelationRouteSpec {
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("At least one relation route is required");
        }
        routes.forEach(WikidataRelationRouteSpec::validate);
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

    /** A relation route is a typed walk, not merely a list of individually valid steps. */
    private static void validate(RelationRoute route) {
        NodeKind node = NodeKind.ENTITY;
        for (int index = 0; index < route.steps().size(); index++) {
            RelationRoute.Step step = route.steps().get(index);
            if (!WikidataDatasourceProvider.ID.equals(step.relation().providerId())) {
                throw new IllegalArgumentException(
                        "A Wikidata relation route cannot use provider "
                                + step.relation().providerId());
            }
            WikidataRouteRelation relation = WikidataRouteRelation.parse(step.relation());
            if (relation == null) {
                throw invalid(route, index, "invalid Wikidata relation "
                        + step.relation().relationId());
            }
            relation.requireDirection(step.direction());
            node = switch (relation.kind()) {
                case PROPERTY -> {
                    requireNode(route, index, relation, node, NodeKind.ENTITY);
                    yield NodeKind.ENTITY;
                }
                case STATEMENT -> {
                    requireNode(route, index, relation, node, NodeKind.ENTITY);
                    yield NodeKind.STATEMENT;
                }
                case QUALIFIER -> {
                    requireNode(route, index, relation, node, NodeKind.STATEMENT);
                    yield NodeKind.ENTITY;
                }
            };
        }
        if (node != NodeKind.ENTITY) {
            throw new IllegalArgumentException("Wikidata relation route '"
                    + route.label() + "' must end at an entity, not a statement");
        }
    }

    private static void requireNode(RelationRoute route, int index,
            WikidataRouteRelation relation, NodeKind actual, NodeKind expected) {
        if (actual == expected) return;
        throw invalid(route, index, relation.kind() + " " + relation.pid()
                + " requires " + expected.article() + " " + expected.label
                + " node but the previous step yields "
                + actual.label);
    }

    private static IllegalArgumentException invalid(
            RelationRoute route, int index, String problem) {
        return new IllegalArgumentException("Wikidata relation route '" + route.label()
                + "', step " + (index + 1) + ": " + problem);
    }

    private enum NodeKind {
        ENTITY("entity"), STATEMENT("statement");

        private final String label;

        NodeKind(String label) { this.label = label; }

        String article() { return this == ENTITY ? "an" : "a"; }
    }

}
