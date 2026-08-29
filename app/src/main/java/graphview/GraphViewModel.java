package graphview;

import java.net.URI;
import java.util.List;

/** Provider-neutral graph presented to an interactive renderer. */
public record GraphViewModel(List<Node> nodes, List<Edge> edges) {
    public GraphViewModel {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public enum State { DEFAULT, FRONTIER, EXPANDED, UNAVAILABLE }

    public record Node(String id, String label, URI link, int level, State state,
                       Object value) {
        public Node {
            id = id == null ? "" : id;
            label = label == null || label.isBlank() ? id : label;
            state = state == null ? State.DEFAULT : state;
        }
    }

    public record Edge(String id, String sourceId, String targetId, String label,
                       boolean directed) {
        public Edge {
            id = id == null ? "" : id;
            sourceId = sourceId == null ? "" : sourceId;
            targetId = targetId == null ? "" : targetId;
            label = label == null ? "" : label;
        }
    }
}
