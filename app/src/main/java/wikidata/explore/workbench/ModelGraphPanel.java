package wikidata.explore.workbench;

import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.MembershipPattern;

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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Read-only graph view of a {@link GeneratedProjectModel}: each class is a node,
 * each entity-reference field whose target is another class in the project is an
 * edge (labelled with the field name). Dashed edges show explicit contextual
 * representations and dotted edges show inheritance, including imported bases.
 * Scalar fields are listed inside the node.
 *
 * <p>The model <i>is</i> the rule-tree (classes = nodes, reference fields =
 * edges), so this is the structural view of what you've configured. Clicking a
 * node fires {@link #onClassSelected}; the host wires that to select the class in
 * the workbench — the graph is a map + selector, not an editor (the class/field
 * panels stay where they are).
 */
public class ModelGraphPanel extends JPanel {

    private static final int NODE_W = 230;
    private static final int H_GAP = 60;
    private static final int V_GAP = 70;
    private static final int MARGIN = 30;
    private static final int LINE_H = 15;
    private static final int HEAD_H = 41;

    private enum EdgeKind { REFERENCE, REPRESENTATION, INHERITANCE }
    private record Edge(String from, String to, String field, EdgeKind kind) {}
    private record FieldLine(String name, String display) {}
    private record FieldKey(String className, String fieldName) {}

    private final Map<String, GeneratedClassModel> classes = new LinkedHashMap<>();
    private final Map<String, Rectangle> nodeRects = new HashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, List<FieldLine>> scalarFields = new HashMap<>();
    private final Map<String, String> classCues = new HashMap<>();
    private final Map<FieldKey, Rectangle> fieldRects = new LinkedHashMap<>();

    private String selected = "";
    private Consumer<String> onClassSelected = c -> {};
    private Consumer<String> onClassExplore = c -> {};
    private BiConsumer<String, String> onFieldSelected = (c, f) -> {};
    private Dimension size = new Dimension(400, 200);

    public ModelGraphPanel() {
        setBackground(Color.WHITE);
        setToolTipText("Click a class to select it; double-click to explore its "
                               + "Wikidata type's relations");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                FieldKey field = fieldAt(e.getPoint());
                if (field != null) {
                    selected = field.className();
                    repaint();
                    onFieldSelected.accept(field.className(), field.fieldName());
                    return;
                }
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

    private FieldKey fieldAt(Point p) {
        for (Map.Entry<FieldKey, Rectangle> en : fieldRects.entrySet()) {
            if (en.getValue().contains(p)) {
                return en.getKey();
            }
        }
        return null;
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

    /** Clicking a scalar field selects that exact field in the workbench. */
    public void onFieldSelected(BiConsumer<String, String> handler) {
        this.onFieldSelected = handler == null ? (c, f) -> {} : handler;
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
        classCues.clear();
        fieldRects.clear();
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
            classCues.put(c.className(), classSourceCue(c, model));
            List<FieldLine> scalars = new ArrayList<>();
            for (GeneratedFieldModel f : c.fields()) {
                if (f == null || f.name() == null || f.name().isBlank()) {
                    continue;
                }
                String target = f.type() == FieldType.ENTITY ? f.entityClassName() : null;
                if (target != null && !target.isBlank() && classes.containsKey(target)) {
                    boolean coll = f.cardinality() == FieldCardinality.COLLECTION;
                    edges.add(new Edge(c.className(), target,
                                       f.name() + (coll ? " [*]" : "")
                                               + " · " + sourceCue(f), EdgeKind.REFERENCE));
                } else {
                    scalars.add(new FieldLine(f.name(), f.name() + "  " + sourceCue(f)));
                }
            }
            scalarFields.put(c.className(), scalars);
            if (c.hasBase() && classes.containsKey(c.baseClassName())) {
                edges.add(new Edge(c.className(), c.baseClassName(), "extends",
                        EdgeKind.INHERITANCE));
            }
        }
        for (var representation : model.entityRepresentationRules()) {
            if (representation == null
                    || !classes.containsKey(representation.roleClassName())
                    || !classes.containsKey(representation.representationClassName())) continue;
            var target = model.findClass(representation.representationClassName());
            var admission = MembershipPattern.kindRule(target, model);
            String condition = admission == null ? "admission missing"
                    : admission.propertyPid() + " = "
                            + String.join(", ", admission.evidenceQids());
            edges.add(new Edge(representation.roleClassName(),
                    representation.representationClassName(),
                    "represents as · " + condition, EdgeKind.REPRESENTATION));
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
        fieldRects.clear();
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
            Stroke previousStroke = g.getStroke();
            if (e.kind() == EdgeKind.REPRESENTATION) {
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10f, new float[]{6f, 5f}, 0f));
            } else if (e.kind() == EdgeKind.INHERITANCE) {
                g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND, 10f, new float[]{2f, 4f}, 0f));
            }
            Color edgeColor = switch (e.kind()) {
                case REPRESENTATION -> new Color(0x7A, 0x45, 0x9A);
                case INHERITANCE -> new Color(0x2E, 0x6F, 0x7E);
                case REFERENCE -> new Color(0x90, 0x90, 0x90);
            };
            g.setColor(edgeColor);
            g.drawLine(x1, y1, x2, y2);
            drawArrowHead(g, x1, y1, x2, y2);
            g.setStroke(previousStroke);
            g.setColor(e.kind() == EdgeKind.REFERENCE
                    ? new Color(0x33, 0x66, 0x99) : edgeColor);
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
            g.drawString(fit(g, classCues.getOrDefault(name, "membership: ?"),
                             r.width - 16), r.x + 8, r.y + 32);
            int fy = r.y + HEAD_H + 4;
            List<FieldLine> sc = scalarFields.getOrDefault(name, List.of());
            for (int i = 0; i < Math.min(sc.size(), 6); i++) {
                FieldLine line = sc.get(i);
                fieldRects.put(new FieldKey(name, line.name()),
                               new Rectangle(r.x + 4, fy - LINE_H + 3, r.width - 8, LINE_H));
                g.drawString(fit(g, "· " + line.display(), r.width - 16), r.x + 8, fy);
                fy += LINE_H;
            }
            if (sc.size() > 6) {
                g.drawString("… +" + (sc.size() - 6), r.x + 8, fy);
            }
            g.setFont(base);
        }
    }

    private static String sourceCue(GeneratedFieldModel field) {
        if (field.mapping().productionKind() == FieldProductionKind.INVERT) {
            String forward = field.mapping().inverseField();
            return forward == null || forward.isBlank()
                    ? "inverse (choose forward field)"
                    : "inverse of " + field.entityClassName() + "." + forward;
        }
        String source = switch (field.mapping().sourceType()) {
            case SPARQL -> "WD";
            case DBPEDIA -> "DB";
            case WIKIPEDIA_INFOBOX -> "infobox";
            case WIKIDATA_API -> "WD-API";
            case BACKLINKS -> "backlinks";
            case WIKIPEDIA_CATEGORY -> "category";
            case WIKIPROJECT -> "project";
            case COMMONS -> "Commons";
            case MANUAL -> "manual";
        };
        String property = field.mapping().propertyPid().isBlank()
                ? "?" : field.mapping().propertyPid();
        String direction = wikidata.explore.model.FieldSemantics.effectiveDirection(field)
                == wikidata.explore.model.RuleDirection.ROOT_TO_ITEM ? "→" : "←";
        return source + ":" + property + " " + direction;
    }

    private static String classSourceCue(
            GeneratedClassModel clazz,
            GeneratedProjectModel project) {
        var mapping = clazz.effectiveInstanceMapping(project);
        var membership = clazz.effectiveMembership(project);
        if (!membership.bounded()) {
            return clazz.seedQids().isEmpty()
                    ? "membership: ?"
                    : "membership: " + clazz.seedQids().size() + " seed QID"
                      + (clazz.seedQids().size() == 1 ? "" : "s");
        }
        String target = membership.qids().isEmpty()
                ? "?" : String.join(", ", membership.qids());
        String direction = mapping.direction()
                == wikidata.explore.model.RuleDirection.ROOT_TO_ITEM ? "→" : "←";
        return "members: WD:" + membership.relationPid() + " " + direction + " " + target;
    }

    private static String fit(Graphics2D g, String text, int width) {
        if (g.getFontMetrics().stringWidth(text) <= width) {
            return text;
        }
        String suffix = "…";
        int end = text.length();
        while (end > 0
                && g.getFontMetrics().stringWidth(text.substring(0, end) + suffix) > width) {
            end--;
        }
        return text.substring(0, end) + suffix;
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
