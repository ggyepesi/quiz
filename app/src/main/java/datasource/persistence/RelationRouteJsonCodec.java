package datasource.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.RelationRoute;

import java.util.List;

/** One JSON spelling of relation routes, outside the neutral graph contract. */
public final class RelationRouteJsonCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    private RelationRouteJsonCodec() { }

    public static List<RelationRoute> parse(String json, String defaultProviderId) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<RouteJson> configured = JSON.readerForListOf(RouteJson.class)
                    .readValue(json);
            return configured.stream().map(route -> route.toRoute(defaultProviderId)).toList();
        } catch (JsonProcessingException | RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Invalid relation routes: " + invalid.getMessage(), invalid);
        }
    }

    public static String write(List<RelationRoute> routes, String defaultProviderId) {
        try {
            return JSON.writeValueAsString((routes == null ? List.<RelationRoute>of() : routes)
                    .stream().map(route -> RouteJson.from(route, defaultProviderId)).toList());
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not serialize relation routes", impossible);
        }
    }

    private record RouteJson(String id, String label, List<StepJson> steps) {
        RelationRoute toRoute(String defaultProviderId) {
            return new RelationRoute(id, label, steps == null ? List.of()
                    : steps.stream().map(step -> step.toStep(defaultProviderId)).toList());
        }
        static RouteJson from(RelationRoute route, String defaultProviderId) {
            return new RouteJson(route.id(), route.label(), route.steps().stream()
                    .map(step -> StepJson.from(step, defaultProviderId)).toList());
        }
    }

    private record StepJson(
            String provider, String relation, GraphTraversalDirection direction) {
        RelationRoute.Step toStep(String defaultProviderId) {
            String owner = provider == null || provider.isBlank()
                    ? defaultProviderId : provider;
            return new RelationRoute.Step(new GraphRelation(owner, relation), direction);
        }
        static StepJson from(RelationRoute.Step step, String defaultProviderId) {
            String provider = step.relation().providerId().equals(defaultProviderId)
                    ? "" : step.relation().providerId();
            return new StepJson(provider, step.relation().relationId(), step.direction());
        }
    }
}
