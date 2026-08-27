package wikidata.explore.workbench;

import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import objectview.Viewable;
import wikidata.ui.IdentityChip;
import wikidata.explore.extract.WikidataDynamicObject;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Adds graph coverage beside the existing native-identity card decoration. */
final class GraphCoverageCardDecorator implements Function<Viewable, JComponent> {
    private record Key(String type, String id) { }
    private record Marker(String label, GraphExpansionCoverage.State state) { }

    private final Map<Key, GraphExpansionCoverage.State> states = new HashMap<>();
    private final Map<String, GraphExpansionCoverage.State> coveredIds = new HashMap<>();

    GraphCoverageCardDecorator(GraphDiscoveryState graph) {
        if (graph == null) return;
        Map<String, String> targetByPattern = new HashMap<>();
        graph.patterns().forEach(pattern ->
                targetByPattern.put(pattern.id(), pattern.targetNodeClass()));
        graph.coverage().forEach(item -> {
            String target = targetByPattern.get(item.patternId());
            if (target != null) states.put(new Key(target, item.node().id()), item.state());
            coveredIds.put(item.node().id(), item.state());
        });
    }

    @Override public JComponent apply(Viewable view) {
        JComponent identity = IdentityChip.ofInstance(view);
        Marker marker = marker(view);
        if (marker == null) return identity;
        JComponent coverage = marker.state() == null
                ? unstampedChip() : coverageChip(marker.state());
        return combine(coverage, identity);
    }

    /** Pure classification seam: tests need not initialize clickable Swing identity UI. */
    String coverageLabel(Viewable view) {
        Marker marker = marker(view);
        return marker == null ? "" : marker.label();
    }

    private Marker marker(Viewable view) {
        if (view == null) return null;
        // A dynamic object's typeName() has a Java-name fallback when it has no stamp.
        // Membership-sensitive presentation follows the same strict rule as graph
        // discovery; an expected covered node with no stamp is called out explicitly.
        if (view instanceof WikidataDynamicObject dynamic && !dynamic.hasTypeStamp()) {
            return coveredIds.containsKey(view.getIdentifier())
                    ? new Marker("unstamped", null) : null;
        }
        GraphExpansionCoverage.State state = view.directClassNames().stream()
                .map(type -> states.get(new Key(type, view.getIdentifier())))
                .filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.comparingInt(GraphCoverageCardDecorator::priority))
                .orElse(null);
        return state == null ? null : new Marker(label(state), state);
    }

    private static JComponent combine(JComponent coverage, JComponent identity) {
        if (identity == null) return coverage;
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(coverage);
        row.add(Box.createHorizontalStrut(6));
        row.add(identity);
        return row;
    }

    private static int priority(GraphExpansionCoverage.State state) {
        return switch (state) {
            case INCOMPLETE -> 0;
            case EXPANDING -> 1;
            case QUEUED -> 2;
            case ENCOUNTERED -> 3;
            case EXPANDED -> 4;
        };
    }

    private static JLabel unstampedChip() {
        return chip("unstamped", Color.GRAY,
                "This covered graph node has no explicit modeled class stamp");
    }

    private static JLabel coverageChip(GraphExpansionCoverage.State state) {
        String text = label(state);
        Color color = state == GraphExpansionCoverage.State.EXPANDED
                ? new Color(38, 110, 55) : new Color(165, 92, 0);
        String tooltip = state == GraphExpansionCoverage.State.EXPANDED
                ? "This node's configured neighbourhood was enumerated"
                : state == GraphExpansionCoverage.State.ENCOUNTERED
                ? "Reached through the graph; its configured neighbourhood is not expanded"
                : "Graph expansion state: " + text;
        return chip(text, color, tooltip);
    }

    private static String label(GraphExpansionCoverage.State state) {
        return switch (state) {
            case ENCOUNTERED -> "frontier";
            case EXPANDED -> "expanded";
            case QUEUED -> "queued";
            case EXPANDING -> "expanding";
            case INCOMPLETE -> "incomplete";
        };
    }

    private static JLabel chip(String text, Color color, String tooltip) {
        JLabel chip = new JLabel(text);
        chip.setFont(chip.getFont().deriveFont(
                Font.BOLD, chip.getFont().getSize2D() - 1f));
        chip.setForeground(color);
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(chip.getForeground()),
                BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        chip.setToolTipText(tooltip);
        return chip;
    }
}
