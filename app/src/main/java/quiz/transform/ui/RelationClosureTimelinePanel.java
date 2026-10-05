package quiz.transform.ui;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A compact interval view over relation instances already retained by a closure path. */
final class RelationClosureTimelinePanel extends JComponent {
    private static final int BASE_WIDTH = 1060;
    private static final int LABEL_WIDTH = 250;
    private static final int RIGHT_MARGIN = 28;
    private static final int TOP = 46;
    private static final int ROW_HEIGHT = 30;
    private static final int LANE_GAP = 12;
    private static final int BAR_HEIGHT = 20;

    private final RelationClosureTimeline.Model model;
    private final Consumer<RelationClosureTimeline.Holding> selection;
    private final List<Hit> hits = new ArrayList<>();
    private double zoom = 1.0;

    private record Hit(Rectangle bounds, RelationClosureTimeline.Holding holding) { }

    RelationClosureTimelinePanel(RelationClosureTimeline.Model model,
                                 Consumer<RelationClosureTimeline.Holding> selection) {
        this.model = model;
        this.selection = selection == null ? ignored -> { } : selection;
        setOpaque(true);
        setToolTipText("");
        setPreferredSize(timelinePreferredSize());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                hit(event.getX(), event.getY()).stream().findFirst()
                        .ifPresent(value -> RelationClosureTimelinePanel.this.selection
                                .accept(value.holding()));
            }
        });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                setCursor(Cursor.getPredefinedCursor(hit(event.getX(), event.getY()).isEmpty()
                        ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
            }
        });
    }

    void zoom(double factor) {
        zoom = Math.max(1.0, Math.min(5.0, zoom * factor));
        setPreferredSize(timelinePreferredSize());
        revalidate();
        repaint();
    }

    void fit() {
        zoom = 1.0;
        setPreferredSize(timelinePreferredSize());
        revalidate();
        repaint();
    }

    RelationClosureTimeline.Model model() {
        return model;
    }

    @Override public String getToolTipText(MouseEvent event) {
        return hit(event.getX(), event.getY()).stream().findFirst()
                .map(value -> "<html><b>" + escape(value.holding().member().getDisplayName())
                        + "</b><br>" + escape(value.holding().position().getDisplayName())
                        + "<br>" + escape(value.holding().dateLabel())
                        + "<br>Click to inspect the original "
                        + escape(value.holding().relation().typeName()) + "</html>")
                .orElse(null);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(surface());
            g.fillRect(0, 0, getWidth(), getHeight());
            hits.clear();
            if (model.holdings().isEmpty()) {
                empty(g, "This path contains no connecting relation instances.");
                return;
            }
            if (!model.fields().configured()) {
                empty(g, "The relation class has no configured start-time or end-time "
                        + "qualifier field.");
                return;
            }
            int plotLeft = LABEL_WIDTH;
            int plotRight = Math.max(plotLeft + 100, getWidth() - RIGHT_MARGIN);
            int y = TOP;
            if (model.hasDates()) {
                axis(g, plotLeft, plotRight);
                for (RelationClosureTimeline.Lane lane : model.lanes()) {
                    List<RelationClosureTimeline.Holding> placed = lane.holdings().stream()
                            .filter(RelationClosureTimeline.Holding::placed).toList();
                    if (placed.isEmpty()) continue;
                    int firstRow = y;
                    for (RelationClosureTimeline.Holding holding : placed) {
                        grid(g, plotLeft, plotRight, y);
                        bar(g, holding, plotLeft, plotRight, y);
                        y += ROW_HEIGHT;
                    }
                    g.setColor(text());
                    g.setFont(g.getFont().deriveFont(Font.BOLD));
                    drawElided(g, lane.position().getDisplayName(), 12,
                            firstRow + Math.max(18, (placed.size() * ROW_HEIGHT) / 2),
                            LABEL_WIDTH - 28);
                    y += LANE_GAP;
                }
            } else {
                empty(g, "None of these relation instances has a configured start or end date.");
                y = TOP + 30;
            }
            if (!model.unplaced().isEmpty()) {
                y += 8;
                g.setColor(text());
                g.setFont(g.getFont().deriveFont(Font.BOLD));
                g.drawString("Undated or invalid intervals — " + model.unplaced().size(),
                        12, y + 17);
                y += ROW_HEIGHT;
                g.setFont(g.getFont().deriveFont(Font.PLAIN));
                for (RelationClosureTimeline.Holding holding : model.unplaced()) {
                    Rectangle row = new Rectangle(12, y, Math.max(100, getWidth() - 40),
                            BAR_HEIGHT + 4);
                    g.setColor(tint(new Color(180, 110, 45), 28));
                    g.fillRoundRect(row.x, row.y, row.width, row.height, 8, 8);
                    g.setColor(muted());
                    g.drawRoundRect(row.x, row.y, row.width, row.height, 8, 8);
                    g.setColor(text());
                    drawElided(g, holding.position().getDisplayName() + " — "
                            + holding.member().getDisplayName() + " — "
                            + holding.dateLabel(), row.x + 8, row.y + 16, row.width - 16);
                    hits.add(new Hit(row, holding));
                    y += ROW_HEIGHT;
                }
            }
        } finally {
            g.dispose();
        }
    }

    private void axis(Graphics2D g, int left, int right) {
        int y = TOP - 13;
        g.setColor(muted());
        g.drawLine(left, y, right, y);
        for (int tick = 0; tick <= 6; tick++) {
            double ratio = tick / 6.0;
            int x = left + (int) Math.round((right - left) * ratio);
            int year = (int) Math.round(model.minimum()
                    + (model.maximum() - model.minimum()) * ratio);
            g.drawLine(x, y - 3, x, y + 3);
            String label = yearLabel(year);
            g.drawString(label, Math.max(left, Math.min(right - 35,
                    x - g.getFontMetrics().stringWidth(label) / 2)), y - 6);
        }
    }

    private void grid(Graphics2D g, int left, int right, int y) {
        g.setColor(tint(muted(), 45));
        for (int tick = 0; tick <= 6; tick++) {
            int x = left + (int) Math.round((right - left) * tick / 6.0);
            g.drawLine(x, y, x, y + BAR_HEIGHT);
        }
    }

    private void bar(Graphics2D g, RelationClosureTimeline.Holding holding,
                     int left, int right, int y) {
        boolean openStart = holding.start() == null;
        boolean openEnd = holding.end() == null;
        int x1 = openStart ? left : x(holding.start(), left, right);
        int x2 = openEnd ? right : x(holding.end(), left, right);
        if (x2 <= x1) x2 = x1 + 5;
        Rectangle bar = new Rectangle(x1, y, Math.max(5, x2 - x1), BAR_HEIGHT);
        g.setColor(tint(new Color(40, 115, 190), 58));
        g.fillRoundRect(bar.x, bar.y, bar.width, bar.height, 8, 8);
        if (openStart || openEnd) {
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
        }
        g.setColor(new Color(40, 105, 175));
        g.drawRoundRect(bar.x, bar.y, bar.width, bar.height, 8, 8);
        g.setStroke(new BasicStroke());
        g.setColor(text());
        g.setFont(g.getFont().deriveFont(Font.PLAIN));
        drawElided(g, holding.member().getDisplayName() + "  " + holding.dateLabel(),
                bar.x + 6, bar.y + 15, Math.max(20, bar.width - 12));
        hits.add(new Hit(new Rectangle(bar.x, bar.y, bar.width, bar.height), holding));
    }

    private int x(aux.FlexibleDate date, int left, int right) {
        double ratio = (RelationClosureTimeline.coordinate(date) - model.minimum())
                / (model.maximum() - model.minimum());
        return left + (int) Math.round(Math.max(0, Math.min(1, ratio)) * (right - left));
    }

    private List<Hit> hit(int x, int y) {
        return hits.stream().filter(hit -> hit.bounds().contains(x, y)).toList();
    }

    private Dimension timelinePreferredSize() {
        int placed = (int) model.holdings().stream()
                .filter(RelationClosureTimeline.Holding::placed).count();
        long lanes = model.lanes().stream()
                .filter(lane -> lane.holdings().stream()
                        .anyMatch(RelationClosureTimeline.Holding::placed)).count();
        int height = TOP + placed * ROW_HEIGHT + (int) lanes * LANE_GAP
                + (model.unplaced().isEmpty() ? 30
                : 48 + model.unplaced().size() * ROW_HEIGHT);
        return new Dimension((int) Math.round(BASE_WIDTH * zoom), Math.max(190, height));
    }

    private static void empty(Graphics2D g, String message) {
        g.setColor(muted());
        g.drawString(message, 18, 30);
    }

    private static void drawElided(Graphics2D g, String value, int x, int y, int width) {
        String text = value == null ? "" : value;
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= width) {
            g.drawString(text, x, y);
            return;
        }
        while (text.length() > 1 && metrics.stringWidth(text + "…") > width) {
            text = text.substring(0, text.length() - 1);
        }
        g.drawString(text + "…", x, y);
    }

    private static String yearLabel(int year) {
        return year < 0 ? Math.abs(year) + " BC" : Integer.toString(year);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Color surface() {
        Color color = UIManager.getColor("Panel.background");
        return color == null ? Color.WHITE : color;
    }

    private static Color text() {
        Color color = UIManager.getColor("Label.foreground");
        return color == null ? Color.DARK_GRAY : color;
    }

    private static Color muted() {
        Color color = UIManager.getColor("Label.disabledForeground");
        return color == null ? Color.GRAY : color;
    }

    private static Color tint(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
