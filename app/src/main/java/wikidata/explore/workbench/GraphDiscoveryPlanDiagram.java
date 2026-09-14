package wikidata.explore.workbench;

import graphview.GraphViewModel;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static plan rendering of the same provider-neutral graph model used by graph views. */
final class GraphDiscoveryPlanDiagram extends JComponent {
    private static final int NODE_WIDTH = 170;
    private static final int NODE_HEIGHT = 64;
    private static final int X_GAP = 50;
    private static final int Y_GAP = 24;
    private static final int MARGIN = 24;

    private final GraphViewModel model;
    private final String footer;

    GraphDiscoveryPlanDiagram(GraphViewModel model, String footer) {
        this.model = model == null
                ? new GraphViewModel(List.of(), List.of()) : model;
        this.footer = footer == null ? "" : footer;
        setOpaque(true);
        setPreferredSize(preferredSize(this.model));
        setToolTipText("Configured graph execution plan");
    }

    GraphViewModel model() {
        return model;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(DiagramStyle.surface());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            if (model.nodes().isEmpty()) {
                g.setColor(DiagramStyle.muted());
                g.drawString("No graph traversal configured.", MARGIN, 32);
                return;
            }
            Map<String, Rectangle> bounds = layout(model, getWidth());
            for (GraphViewModel.Edge edge : model.edges()) {
                Rectangle source = bounds.get(edge.sourceId());
                Rectangle target = bounds.get(edge.targetId());
                if (source != null && target != null) edge(g, source, target, edge.label());
            }
            for (GraphViewModel.Node node : model.nodes()) {
                Rectangle box = bounds.get(node.id());
                if (box != null) node(g, box, node);
            }
            if (!footer.isBlank()) {
                g.setColor(DiagramStyle.muted());
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
                g.drawString(DiagramStyle.elide(g, footer, getWidth() - 2 * MARGIN),
                        MARGIN, getHeight() - 12);
            }
        } finally {
            g.dispose();
        }
    }

    private static Dimension preferredSize(GraphViewModel model) {
        int levels = model.nodes().stream().mapToInt(GraphViewModel.Node::level)
                .max().orElse(0) + 1;
        Map<Integer, Long> perLevel = model.nodes().stream().collect(
                java.util.stream.Collectors.groupingBy(GraphViewModel.Node::level,
                        java.util.stream.Collectors.counting()));
        long rows = perLevel.values().stream().mapToLong(Long::longValue).max().orElse(1);
        return new Dimension(Math.max(760,
                2 * MARGIN + levels * NODE_WIDTH + Math.max(0, levels - 1) * X_GAP),
                (int) Math.max(180, 2 * MARGIN + rows * NODE_HEIGHT
                        + Math.max(0, rows - 1) * Y_GAP + 32));
    }

    private static Map<String, Rectangle> layout(GraphViewModel model, int availableWidth) {
        Map<Integer, List<GraphViewModel.Node>> levels = new LinkedHashMap<>();
        model.nodes().stream().sorted(Comparator.comparingInt(GraphViewModel.Node::level))
                .forEach(node -> levels.computeIfAbsent(node.level(), ignored -> new ArrayList<>())
                        .add(node));
        int levelCount = Math.max(1, levels.size());
        int width = Math.max(NODE_WIDTH, availableWidth - 2 * MARGIN);
        int step = levelCount == 1 ? 0 : Math.max(NODE_WIDTH + 18,
                (width - NODE_WIDTH) / (levelCount - 1));
        Map<String, Rectangle> result = new LinkedHashMap<>();
        int column = 0;
        for (List<GraphViewModel.Node> nodes : levels.values()) {
            int x = MARGIN + column++ * step;
            for (int row = 0; row < nodes.size(); row++) {
                int y = MARGIN + row * (NODE_HEIGHT + Y_GAP);
                result.put(nodes.get(row).id(), new Rectangle(x, y, NODE_WIDTH, NODE_HEIGHT));
            }
        }
        return result;
    }

    private static void node(Graphics2D g, Rectangle box, GraphViewModel.Node node) {
        g.setColor(node.state() == GraphViewModel.State.UNAVAILABLE
                ? DiagramStyle.warningTint()
                : DiagramStyle.tint());
        g.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12);
        g.setColor(node.state() == GraphViewModel.State.UNAVAILABLE
                ? DiagramStyle.muted() : DiagramStyle.ACCENT);
        g.drawRoundRect(box.x, box.y, box.width, box.height, 12, 12);
        g.setColor(DiagramStyle.text());
        g.setFont(g.getFont().deriveFont(Font.BOLD));
        g.drawString(DiagramStyle.elide(g, node.label(), box.width - 16),
                box.x + 8, box.y + 20);
        g.setColor(DiagramStyle.muted());
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        String detail = node.details().entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(java.util.stream.Collectors.joining(" · "));
        g.drawString(DiagramStyle.elide(g, detail, box.width - 16),
                box.x + 8, box.y + 42);
    }

    private static void edge(Graphics2D g, Rectangle source, Rectangle target, String label) {
        int x1 = source.x + source.width;
        int y1 = source.y + source.height / 2;
        int x2 = target.x;
        int y2 = target.y + target.height / 2;
        g.setColor(DiagramStyle.muted());
        g.drawLine(x1 + 3, y1, x2 - 6, y2);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int arrowX = x2 - 6;
        int arrowY = y2;
        for (double offset : new double[] {-0.55, 0.55}) {
            g.drawLine(arrowX, arrowY,
                    arrowX - (int) (9 * Math.cos(angle + offset)),
                    arrowY - (int) (9 * Math.sin(angle + offset)));
        }
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        int middleX = (x1 + x2) / 2;
        int middleY = (y1 + y2) / 2;
        g.drawString(label == null ? "" : label, middleX - 20, middleY - 6);
    }
}
