package datasource.graph.execution;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.RelationRoute;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphAdjacencyResult;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.LocalGraphStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Executes finite relation routes in one batched wave per explicit route step. */
public final class RelationRouteExecutor {
    private RelationRouteExecutor() { }

    /** One terminal and one concrete source path that reached it. */
    public record Match(
            EntityRef owner,
            EntityRef terminal,
            RelationRoute route,
            List<GraphEdge> path) {
        public Match {
            if (owner == null || terminal == null || route == null) {
                throw new IllegalArgumentException(
                        "A route match needs an owner, terminal and route");
            }
            path = path == null ? List.of() : List.copyOf(path);
        }
    }

    public static final class Result {
        private final Map<EntityRef, List<Match>> matches;
        private final Map<EntityRef, List<EntityRef>> terminals;
        private final List<EntityRef> incomplete;
        private final List<EntityRef> unavailable;

        private Result(Map<EntityRef, List<Match>> matches,
                Collection<EntityRef> incomplete,
                Collection<EntityRef> unavailable) {
            LinkedHashMap<EntityRef, List<Match>> frozenMatches = new LinkedHashMap<>();
            LinkedHashMap<EntityRef, List<EntityRef>> frozenTerminals = new LinkedHashMap<>();
            matches.forEach((owner, values) -> {
                List<Match> copy = List.copyOf(values);
                frozenMatches.put(owner, copy);
                frozenTerminals.put(owner, copy.stream().map(Match::terminal)
                        .distinct().toList());
            });
            this.matches = Collections.unmodifiableMap(frozenMatches);
            this.terminals = Collections.unmodifiableMap(frozenTerminals);
            this.incomplete = List.copyOf(new LinkedHashSet<>(incomplete));
            this.unavailable = List.copyOf(new LinkedHashSet<>(unavailable));
        }

        public Map<EntityRef, List<Match>> matches() { return matches; }
        public List<Match> matches(EntityRef owner) {
            return matches.getOrDefault(owner, List.of());
        }
        public List<EntityRef> terminals(EntityRef owner) {
            return terminals.getOrDefault(owner, List.of());
        }
        public List<EntityRef> incomplete() { return incomplete; }
        public List<EntityRef> unavailable() { return unavailable; }
        public boolean complete() { return incomplete.isEmpty() && unavailable.isEmpty(); }
    }

    public static Result execute(
            LocalGraphStore store,
            List<RelationRoute> routes,
            List<EntityRef> owners,
            GraphAdjacencyAcquirer acquirer) throws Exception {
        if (store == null || acquirer == null) {
            throw new IllegalArgumentException("Graph store and adjacency acquirer are required");
        }
        List<RelationRoute> configured = routes == null ? List.of() : List.copyOf(routes);
        if (configured.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Relation routes cannot be null");
        }
        List<EntityRef> roots = owners == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(owners));
        acquirer.validateRelations(configured.stream().flatMap(route -> route.steps().stream())
                .map(RelationRoute.Step::relation).distinct().toList());

        LinkedHashMap<EntityRef, List<Match>> matches = new LinkedHashMap<>();
        roots.forEach(owner -> matches.put(owner, new ArrayList<>()));
        LinkedHashSet<EntityRef> incomplete = new LinkedHashSet<>();
        LinkedHashSet<EntityRef> unavailable = new LinkedHashSet<>();

        for (RelationRoute route : configured) {
            Map<EntityRef, LinkedHashMap<EntityRef, List<GraphEdge>>> paths =
                    initialPaths(roots);
            for (RelationRoute.Step step : route.steps()) {
                List<EntityRef> frontier = paths.values().stream()
                        .flatMap(byNode -> byNode.keySet().stream()).distinct().toList();
                if (frontier.isEmpty()) break;
                GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                        frontier, step.relation(), step.direction());
                GraphAdjacencyResult local = store.adjacent(demand);
                if (!local.missingNodes().isEmpty()) {
                    acquirer.acquire(store, new GraphAdjacencyDemand(local.missingNodes(),
                            step.relation(), step.direction()));
                    local = store.adjacent(demand);
                }
                incomplete.addAll(local.incompleteNodes());
                unavailable.addAll(local.unavailableNodes());
                paths = advance(store, paths, step);
            }
            for (EntityRef owner : roots) {
                for (Map.Entry<EntityRef, List<GraphEdge>> terminal
                        : paths.getOrDefault(owner, new LinkedHashMap<>()).entrySet()) {
                    matches.get(owner).add(new Match(owner, terminal.getKey(), route,
                            terminal.getValue()));
                }
            }
        }
        return new Result(matches, incomplete, unavailable);
    }

    private static Map<EntityRef, LinkedHashMap<EntityRef, List<GraphEdge>>> initialPaths(
            List<EntityRef> owners) {
        LinkedHashMap<EntityRef, LinkedHashMap<EntityRef, List<GraphEdge>>> paths =
                new LinkedHashMap<>();
        for (EntityRef owner : owners) {
            LinkedHashMap<EntityRef, List<GraphEdge>> start = new LinkedHashMap<>();
            start.put(owner, List.of());
            paths.put(owner, start);
        }
        return paths;
    }

    private static Map<EntityRef, LinkedHashMap<EntityRef, List<GraphEdge>>> advance(
            LocalGraphStore store,
            Map<EntityRef, LinkedHashMap<EntityRef, List<GraphEdge>>> paths,
            RelationRoute.Step step) {
        LinkedHashMap<EntityRef, LinkedHashMap<EntityRef, List<GraphEdge>>> next =
                new LinkedHashMap<>();
        for (Map.Entry<EntityRef, LinkedHashMap<EntityRef, List<GraphEdge>>> owner
                : paths.entrySet()) {
            LinkedHashMap<EntityRef, List<GraphEdge>> reached = new LinkedHashMap<>();
            for (Map.Entry<EntityRef, List<GraphEdge>> current : owner.getValue().entrySet()) {
                GraphAdjacencyDemand one = new GraphAdjacencyDemand(List.of(current.getKey()),
                        step.relation(), step.direction());
                for (GraphEdge edge : store.adjacent(one).edges()) {
                    EntityRef endpoint = edge.entityEndpoint(step.direction());
                    if (endpoint == null || reached.containsKey(endpoint)) continue;
                    List<GraphEdge> path = new ArrayList<>(current.getValue());
                    path.add(edge);
                    reached.put(endpoint, List.copyOf(path));
                }
            }
            next.put(owner.getKey(), reached);
        }
        return next;
    }
}
