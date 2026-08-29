package wikidata.explore.workbench;

import wikidata.explore.query.logical.DiscoverEntityRelationQuery;

import javax.swing.JComponent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/** Layered, selectable rendering of QIDs connected by one configured PID. */
final class EntityRelationDiagram extends JComponent {
    private static final int W = 180, H = 48, HG = 35, VG = 62, M = 28;
    private DiscoverEntityRelationQuery.Result result;
    private final Map<String, Rectangle> bounds = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private Consumer<Set<String>> listener = ignored -> { };
    private Consumer<String> startListener = ignored -> { };

    EntityRelationDiagram() {
        setOpaque(true); setBackground(DiagramStyle.surface());
        setToolTipText("Click QIDs to select them; Cmd/Ctrl-click keeps the selection.");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                String qid = bounds.entrySet().stream().filter(x -> x.getValue().contains(e.getPoint()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if (qid == null) return;
                if (e.getClickCount() >= 2) {
                    wikidata.ui.WikidataLinks.open(qid);
                    return;
                }
                if (!e.isControlDown() && !e.isMetaDown() && !e.isShiftDown()) selected.clear();
                if (!selected.add(qid)) selected.remove(qid);
                listener.accept(Set.copyOf(selected));
                startListener.accept(qid);
                repaint();
            }
        });
    }
    void onSelectionChanged(Consumer<Set<String>> value) {
        listener = value == null ? ignored -> { } : value;
    }
    void onStartingQidRequested(Consumer<String> value) {
        startListener = value == null ? ignored -> { } : value;
    }
    void result(DiscoverEntityRelationQuery.Result value) {
        result = value; selected.clear(); layoutGraph(); revalidate(); repaint();
    }
    private void layoutGraph() {
        bounds.clear();
        if (result == null) { setPreferredSize(new Dimension(720, 360)); return; }
        Map<Integer, List<DiscoverEntityRelationQuery.Node>> layers = new LinkedHashMap<>();
        result.nodes().stream().sorted(Comparator.comparingInt(DiscoverEntityRelationQuery.Node::depth)
                        .thenComparing(DiscoverEntityRelationQuery.Node::label))
                .forEach(n -> layers.computeIfAbsent(n.depth(), ignored -> new ArrayList<>()).add(n));
        int columns = 1;
        for (var layer : layers.entrySet()) {
            columns = Math.max(columns, layer.getValue().size());
            int x = M, y = M + layer.getKey() * (H + VG);
            for (var node : layer.getValue()) { bounds.put(node.qid(), new Rectangle(x, y, W, H)); x += W + HG; }
        }
        setPreferredSize(new Dimension(M * 2 + columns * W + Math.max(0, columns - 1) * HG,
                M * 2 + Math.max(1, layers.size()) * (H + VG)));
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (result == null) { g.setColor(DiagramStyle.muted()); g.drawString("Run discovery to draw the relation.", 24, 36); return; }
            for (var edge : result.edges()) paintEdge(g, bounds.get(edge.sourceQid()), bounds.get(edge.targetQid()));
            Map<String, DiscoverEntityRelationQuery.Node> nodes = new LinkedHashMap<>();
            result.nodes().forEach(n -> nodes.put(n.qid(), n));
            for (var entry : bounds.entrySet()) paintNode(g, nodes.get(entry.getKey()), entry.getValue(), selected.contains(entry.getKey()));
        } finally { g.dispose(); }
    }
    private static void paintEdge(Graphics2D g, Rectangle from, Rectangle to) {
        if (from == null || to == null) return;
        int x1 = from.x + from.width / 2, y1 = from.y + from.height;
        int x2 = to.x + to.width / 2, y2 = to.y;
        if (to.y <= from.y) { x1 = from.x + from.width; y1 = from.y + from.height / 2; x2 = to.x; y2 = to.y + to.height / 2; }
        g.setColor(DiagramStyle.muted()); g.setStroke(new BasicStroke(1.2f)); g.drawLine(x1, y1, x2, y2);
        double a = Math.atan2(y2 - y1, x2 - x1);
        for (double d : new double[]{-Math.PI / 7, Math.PI / 7}) g.drawLine(x2, y2,
                (int)(x2 - 7 * Math.cos(a + d)), (int)(y2 - 7 * Math.sin(a + d)));
    }
    private static void paintNode(Graphics2D g, DiscoverEntityRelationQuery.Node n, Rectangle r, boolean active) {
        // A seed is emphasised by a stronger tint and border, never by being the only
        // box light enough to read: both fills composite over the reader's background.
        g.setColor(active ? DiagramStyle.tint() : DiagramStyle.tint(DiagramStyle.muted()));
        g.fillRoundRect(r.x,r.y,r.width,r.height,12,12);
        g.setColor(active ? DiagramStyle.ACCENT : DiagramStyle.quietBorder());
        g.setStroke(new BasicStroke(active ? 2f : 1f)); g.drawRoundRect(r.x,r.y,r.width,r.height,12,12);
        g.setColor(DiagramStyle.text()); g.setFont(g.getFont().deriveFont(Font.BOLD));
        g.drawString(DiagramStyle.elide(g,n.label(),r.width-16),r.x+8,r.y+20);
        g.setFont(g.getFont().deriveFont(Font.PLAIN,11f)); g.setColor(DiagramStyle.muted());
        g.drawString(n.qid()+" · depth "+n.depth(),r.x+8,r.y+38);
    }
}
