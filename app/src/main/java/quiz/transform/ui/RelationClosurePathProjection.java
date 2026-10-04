package quiz.transform.ui;

import graphview.GraphViewModel;
import objectview.Viewable;
import quiz.transform.RelationClosureGroup;
import wikidata.ui.WikidataLinks;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presents a retained relation-closure path without turning bridge rows into nodes. */
final class RelationClosurePathProjection {
    private RelationClosurePathProjection() { }

    static GraphViewModel graph(RelationClosureGroup.RelationPath path) {
        if (path == null || path.isEmpty()) return new GraphViewModel(List.of(), List.of());
        List<GraphViewModel.Node> nodes = new ArrayList<>();
        for (int index = 0; index < path.nodes().size(); index++) {
            RelationClosureGroup.PathNode pathNode = path.nodes().get(index);
            Viewable value = pathNode.instance();
            String type = clean(value.typeName(), "Instance");
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Path role", roleLabel(pathNode, index));
            details.put("Class", type);
            nodes.add(new GraphViewModel.Node(nodeId(index), displayName(value), link(value),
                    index, index == path.nodes().size() - 1
                            ? GraphViewModel.State.FRONTIER
                            : GraphViewModel.State.EXPANDED,
                    details, value));
        }

        List<GraphViewModel.Edge> edges = new ArrayList<>();
        for (int index = 0; index < path.edges().size(); index++) {
            Viewable bridge = path.edges().get(index).bridge();
            edges.add(new GraphViewModel.Edge("path-edge-" + index,
                    nodeId(index), nodeId(index + 1),
                    clean(bridge == null ? null : bridge.typeName(), "Relation"), true));
        }
        return new GraphViewModel(nodes, edges);
    }

    static String roleLabel(RelationClosureGroup.PathNode node, int index) {
        String type = clean(node == null || node.instance() == null
                ? null : node.instance().typeName(), "Instance");
        if (node != null && node.role() == RelationClosureGroup.PathRole.MEMBER) {
            return "Member " + type;
        }
        return (index == 0 ? "Start " : "Shared ") + type;
    }

    private static String nodeId(int index) {
        return "path-node-" + index;
    }

    private static String displayName(Viewable value) {
        if (value == null) return "Instance";
        return clean(value.getDisplayName(), clean(value.getIdentifier(), "Instance"));
    }

    private static URI link(Viewable value) {
        String url = value == null ? null : WikidataLinks.url(value.getIdentifier());
        return url == null ? null : URI.create(url);
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
