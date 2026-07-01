package wikidata.explore.workbench;

import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Read-only graph view of a {@link GeneratedProjectModel}: each class is a node,
 * each entity-reference field whose target is another class in the project is an
 * edge (labelled with the field name). Scalar fields are listed inside the node.
 *
 * <p>The model <i>is</i> the rule-tree (classes = nodes, reference fields =
 * edges), so this is the structural view of what you've configured. Clicking a
 * node fires {@link #onClassSelected}; the host wires that to select the class in
 * the workbench — the graph is a map + selector, not an editor (the class/field
 * panels stay where they are).
 */
public class RuleTreeGraphPanel extends JPanel {

    private static final int NODE_W = 170;
    private static final int H_GAP = 60;
    private static final int V_GAP = 70;
    private static final int MARGIN = 30;
    private static final int LINE_H = 15;
    private static final int HEAD_H = 26;

    private record Edge(String from, String to, String field) {}

    private final Map<String, GeneratedClassModel> classes = new LinkedHashMap<>();
    private final Map<String, Rectangle> nodeRects = new HashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, List<String>> scalarFields = new HashMap<>();

    private String selected = "";
    private Consumer<String> onClassSelected = c -> {};
    private Consumer<String> onClassExplore = c -> {};
    private Dimension size = new Dimension(400, 200);

    public RuleTreeGraphPanel() {
        setBackground(Color.WHITE);
        setToolTipText("Click a class to select it; double-click to explore its "
                + "Wikidata type's relations");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                String hit = nodeAt(e.getPoint());
                if (hit == null) {
                    return;
                }
                selected = hit;
                repaint();
                if (e.getClickCount() == 2) {
                    onClassExplore.accept(hit);
                } else {
                    onClassSelected.accept(hit);
                }
            }
        });
    }

    private String nodeAt(Point p) {
        for (Map.Entry<String, Rectangle> en : nodeRects.entrySet()) {
            if (en.getValue().contains(p)) {
                return en.getKey();
            }
        }
        return null;
    }

    public void onClassSelected(Consumer<String> handler) {
        this.onClassSelected = handler == null ? c -> {} : handler;
    }

    /** Fired on double-click — host opens the Explorer on the class's type. */
    public void onClassExplore(Consumer<String> handler) {
        this.onClassExplore = handler == null ? c -> {} : handler;
    }

    /** Marks {@code className} selected (e.g. to mirror the workbench selection). */
    public void setSelectedClass(String className) {
        this.selected = className == null ? "" : className;
        repaint();
    }

    public void setModel(GeneratedProjectModel model) {
        classes.clear();
        edges.clear();
        scalarFields.clear();
        nodeRects.clear();
        if (model == null) {
            size = new Dimension(400, 120);
            revalidate();
            repaint();
            return;
        }

        for (GeneratedClassModel c : model.classes()) {
            if (c != null && c.className() != null && !c.className().isBlank()) {
                classes.putIfAbsent(c.className(), c);
            }
        }
        for (GeneratedClassModel c : classes.values()) {
            List<String> scalars = new ArrayList<>();
            for (GeneratedFieldModel f : c.fields()) {
                if (f == null || f.name() == null || f.name().isBlank()) {
                    continue;
                }
                String target = f.type() == FieldType.ENTITY ? f.entityClassName() : null;
                if (target != null && !target.isBlank() && classes.containsKey(target)) {
                    boolean coll = f.cardinality() == FieldCardinality.COLLECTION;
                    edges.add(new Edge(c.className(), target,
                            f.name() + (coll ? " [*]" : "")));
                } else {
                    scalars.add(f.name());
                }
            }
            scalarFields.put(c.className(), scalars);
        }

        String root = model.rootClass() != null ? model.rootClass().className() : null;
        layout(root);
        revalidate();
        repaint();
    }

    // Layered layout: BFS depth from the root sets the row; orphan classes (not
    // reachable from root) get appended rows. Column = order within the row.
    private void layout(String root) {
        Map<String, Integer> depth = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        if (root != null && classes.containsKey(root)) {
            depth.put(root, 0);
            queue.add(root);
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int d = depth.get(cur);
            for (Edge e : edges) {
                if (e.from().equals(cur) && !depth.containsKey(e.to())) {
                    depth.put(e.to(), d + 1);
                    queue.add(e.to());
                }
            }
        }
        int maxDepth = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (String c : classes.keySet()) {
            depth.putIfAbsent(c, maxDepth + 1); // orphans on their own row
        }

        Map<Integer, List<String>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> en : depth.entrySet()) {
            rows.computeIfAbsent(en.getValue(), k -> new ArrayList<>()).add(en.getKey());
        }

        int maxW = 0;
        for (Map.Entry<Integer, List<String>> row : rows.entrySet()) {
            int y = MARGIN + row.getKey() * (nodeHeight(maxFields()) + V_GAP);
            int x = MARGIN;
            for (String c : row.getValue()) {
                int h = nodeHeight(scalarFields.getOrDefault(c, List.of()).size());
                nodeRects.put(c, new Rectangle(x, y, NODE_W, h));
                x += NODE_W + H_GAP;
            }
            maxW = Math.max(maxW, x);
        }
        int maxY = nodeRects.values().stream()
                .mapToInt(r -> r.y + r.height).max().orElse(200);
        size = new Dimension(maxW + MARGIN, maxY + MARGIN);
    }

    private int maxFields() {
        return scalarFields.values().stream().mapToInt(List::size).max().orElse(0);
    }

    private static int nodeHeight(int scalarCount) {
        return HEAD_H + Math.min(scalarCount, 6) * LINE_H + 8;
    }

    @Override public Dimension getPreferredSize() {
        return size;
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        // Edges first, under the nodes.
        for (Edge e : edges) {
            Rectangle a = nodeRects.get(e.from());
            Rectangle b = nodeRects.get(e.to());
            if (a == null || b == null) {
                continue;
            }
            int x1 = a.x + a.width / 2, y1 = a.y + a.height;
            int x2 = b.x + b.width / 2, y2 = b.y;
            // Self/cross edges to a node above: route from node side.
            if (b.y <= a.y) {
                y1 = a.y; // leave from top when target is above/level
            }
            g.setColor(new Color(0x90, 0x90, 0x90));
            g.drawLine(x1, y1, x2, y2);
            drawArrowHead(g, x1, y1, x2, y2);
            g.setColor(new Color(0x33, 0x66, 0x99));
            int mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
            g.drawString(e.field(), mx + 3, my);
        }

        for (Map.Entry<String, Rectangle> en : nodeRects.entrySet()) {
            String name = en.getKey();
            Rectangle r = en.getValue();
            boolean sel = name.equals(selected);
            g.setColor(sel ? new Color(0xE8, 0xF0, 0xFF) : new Color(0xF4, 0xF4, 0xF4));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setColor(sel ? new Color(0x33, 0x66, 0x99) : Color.GRAY);
            g.setStroke(new BasicStroke(sel ? 2f : 1f));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);

            g.setColor(Color.BLACK);
            Font base = g.getFont();
            g.setFont(base.deriveFont(Font.BOLD));
            g.drawString(name, r.x + 8, r.y + 17);
            g.setFont(base.deriveFont(Font.PLAIN, 11f));
            g.setColor(new Color(0x55, 0x55, 0x55));
            int fy = r.y + HEAD_H + 4;
            List<String> sc = scalarFields.getOrDefault(name, List.of());
            for (int i = 0; i < Math.min(sc.size(), 6); i++) {
                g.drawString("· " + sc.get(i), r.x + 8, fy);
                fy += LINE_H;
            }
            if (sc.size() > 6) {
                g.drawString("… +" + (sc.size() - 6), r.x + 8, fy);
            }
            g.setFont(base);
        }
    }

    private static void drawArrowHead(Graphics2D g, int x1, int y1, int x2, int y2) {
        double ang = Math.atan2(y2 - y1, x2 - x1);
        int len = 8;
        int xa = (int) (x2 - len * Math.cos(ang - Math.PI / 7));
        int ya = (int) (y2 - len * Math.sin(ang - Math.PI / 7));
        int xb = (int) (x2 - len * Math.cos(ang + Math.PI / 7));
        int yb = (int) (y2 - len * Math.sin(ang + Math.PI / 7));
        g.drawLine(x2, y2, xa, ya);
        g.drawLine(x2, y2, xb, yb);
    }
}
