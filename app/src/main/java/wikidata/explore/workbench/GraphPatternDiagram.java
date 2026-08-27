package wikidata.explore.workbench;

import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphTraversalStep;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.List;

/** One rendering of a compiled graph pattern, shared by configuration and preview. */
final class GraphPatternDiagram extends JComponent {
    enum Role { SELECTED_TARGET, SOURCE, STATEMENT, FRONTIER_TARGET }

    record Details(String selected, String source, String statement, String frontier,
                   String footer) {
        static Details configuration(GraphExpansionPattern pattern) {
            if (pattern == null) return new Details("", "", "", "", "");
            return new Details("seed → curated frontier", "reached subjects",
                    "materialized edge", "new curated frontier",
                    pattern.relation().providerId() + " · "
                            + pattern.direction().toString().toLowerCase().replace('_', ' ')
                            + " · Curated frontier · click a box to configure");
        }

        static Details counts(int selected, int sources, int statements, int frontier) {
            return new Details("selected · " + selected, "discovered · " + sources,
                    "statements · " + statements, "frontier · " + frontier, "");
        }
    }

    private static final Color ACCENT = new Color(30, 110, 210);
    private final Map<Rectangle, Role> hitTargets = new LinkedHashMap<>();
    private final Map<Rectangle, GraphTraversalStep> stepTargets = new LinkedHashMap<>();
    private GraphExpansionPattern pattern;
    private Details details = Details.counts(0, 0, 0, 0);
    private Consumer<Role> onActivate;
    private Consumer<GraphTraversalStep> onStepActivate;
    private List<GraphTraversalStep> steps = List.of();

    GraphPatternDiagram() {
        setPreferredSize(new Dimension(760, 125));
        setToolTipText("Graph expansion pattern");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (onActivate == null || event.getButton() != MouseEvent.BUTTON1) return;
                hitTargets.entrySet().stream()
                        .filter(entry -> entry.getKey().contains(event.getPoint()))
                        .map(Map.Entry::getValue).findFirst().ifPresent(onActivate);
                if (onStepActivate != null) stepTargets.entrySet().stream()
                        .filter(entry -> entry.getKey().contains(event.getPoint()))
                        .map(Map.Entry::getValue).findFirst().ifPresent(onStepActivate);
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                boolean roleHit = onActivate != null && hitTargets.keySet().stream()
                        .anyMatch(r -> r.contains(event.getPoint()));
                boolean stepHit = onStepActivate != null && stepTargets.keySet().stream()
                        .anyMatch(r -> r.contains(event.getPoint()));
                setCursor(roleHit || stepHit
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });
    }

    void pattern(GraphExpansionPattern value, Details valueDetails) {
        pattern = value;
        details = valueDetails == null ? Details.counts(0, 0, 0, 0) : valueDetails;
        repaint();
    }

    void details(Details value) {
        details = value == null ? Details.counts(0, 0, 0, 0) : value;
        repaint();
    }

    void onActivate(Consumer<Role> handler) {
        onActivate = handler;
        if (handler == null) hitTargets.clear();
        setCursor(Cursor.getDefaultCursor());
    }

    void onStepActivate(Consumer<GraphTraversalStep> handler) {
        onStepActivate = handler;
        if (handler == null) stepTargets.clear();
        setCursor(Cursor.getDefaultCursor());
    }

    void traversalSteps(List<GraphTraversalStep> value) {
        steps = value == null ? List.of() : List.copyOf(value);
        setPreferredSize(new Dimension(760, 125 + steps.size() * 25));
        revalidate();
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        hitTargets.clear();
        stepTargets.clear();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            if (pattern == null && steps.isEmpty()) {
                g.setColor(muted());
                g.drawString("No graph pattern selected.", 16, 32);
                return;
            }
            int y = 22, h = 54, gap = 34;
            if (pattern != null) {
            int w = Math.max(112, (getWidth() - 5 * gap) / 4);
            int x1 = gap, x2 = x1 + w + gap, x3 = x2 + w + gap,
                    x4 = x3 + w + gap;
            box(g, new Rectangle(x1, y, w, h), pattern.targetNodeClass(),
                    details.selected(), Role.SELECTED_TARGET);
            box(g, new Rectangle(x2, y, w, h), pattern.sourceNodeClass(),
                    details.source(), Role.SOURCE);
            String statementDetail = details.statement();
            if (statementDetail.isBlank()) statementDetail = pattern.relation().relationId();
            box(g, new Rectangle(x3, y, w, h), pattern.statementClass(),
                    statementDetail, Role.STATEMENT);
            box(g, new Rectangle(x4, y, w, h), pattern.targetNodeClass(),
                    details.frontier(), Role.FRONTIER_TARGET);
            arrow(g, x1 + w, y + h / 2, x2, y + h / 2, "expand incoming");
            arrow(g, x2 + w, y + h / 2, x3, y + h / 2,
                    pattern.relation().relationId());
            arrow(g, x3 + w, y + h / 2, x4, y + h / 2,
                    pattern.targetField());
            if (!details.footer().isBlank()) {
                g.setColor(muted());
                g.setFont(g.getFont().deriveFont(11f));
                g.drawString(details.footer(), gap, 108);
            }
            }
            int stepY = pattern == null ? 28 : 124;
            if (pattern == null) {
                g.setColor(text());
                g.setFont(g.getFont().deriveFont(Font.BOLD));
                g.drawString("Configured field traversal", gap, 18);
            }
            for (GraphTraversalStep step : steps) {
                Rectangle row = new Rectangle(gap, stepY - 16,
                        Math.max(100, getWidth() - 2 * gap), 22);
                g.setColor(tint());
                g.fillRoundRect(row.x, row.y, row.width, row.height, 8, 8);
                g.setColor(ACCENT);
                g.drawRoundRect(row.x, row.y, row.width, row.height, 8, 8);
                g.setColor(text());
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
                String arrow = step.direction() == datasource.graph.GraphTraversalDirection.OUTGOING
                        ? " → " : " ← ";
                g.drawString(step.sourceNodeClass() + "." + step.sourceField()
                        + arrow + step.relation().relationId() + arrow
                        + step.targetNodeClass() + " · " + step.policy(),
                        row.x + 8, stepY);
                if (onStepActivate != null) stepTargets.put(row, step);
                stepY += 25;
            }
        } finally {
            g.dispose();
        }
    }

    private void box(Graphics2D g, Rectangle bounds, String title, String detail, Role role) {
        g.setColor(tint());
        g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 12, 12);
        g.setColor(ACCENT);
        g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 12, 12);
        g.setColor(text());
        g.setFont(g.getFont().deriveFont(Font.BOLD));
        g.drawString(elide(g, title, bounds.width - 18), bounds.x + 9, bounds.y + 21);
        g.setColor(muted());
        g.setFont(g.getFont().deriveFont(Font.PLAIN));
        g.drawString(elide(g, detail, bounds.width - 18), bounds.x + 9, bounds.y + 41);
        if (onActivate != null) hitTargets.put(new Rectangle(bounds), role);
    }

    private static void arrow(Graphics2D g, int x1, int y1, int x2, int y2,
                              String label) {
        g.setColor(muted());
        g.drawLine(x1 + 3, y1, x2 - 5, y2);
        g.drawLine(x2 - 12, y2 - 5, x2 - 5, y2);
        g.drawLine(x2 - 12, y2 + 5, x2 - 5, y2);
        Font old = g.getFont();
        g.setFont(old.deriveFont(10f));
        g.drawString(label, x1 + 7, y1 - 6);
        g.setFont(old);
    }

    private static String elide(Graphics2D g, String text, int width) {
        String value = text == null ? "" : text;
        if (g.getFontMetrics().stringWidth(value) <= width) return value;
        while (value.length() > 1
                && g.getFontMetrics().stringWidth(value + "…") > width) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "…";
    }

    private static Color text() {
        return ui("Label.foreground", Color.DARK_GRAY);
    }

    private static Color muted() {
        return ui("Label.disabledForeground", Color.GRAY);
    }

    private static Color tint() {
        return new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 28);
    }

    private static Color ui(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }
}
