package quiz.ui;

import quiz.Quizable;
import quiz.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;

public class QuizableReferenceRow extends JComponent {
    private static final int PAD_X = 6;
    private static final int PAD_Y = 4;
    private static final int GAP = 8;
    private static final int TRI_W = 12;

    private final String fieldName;
    private final Quizable target;
    private final QuizableRenderContext renderContext;
    private final QuizablePanelConfig openConfig;
    private final String openTitle;
    private final boolean expanded;
    private final List<String> fieldPath;

    private Rectangle targetBounds;
    private boolean hover = false;

    public QuizableReferenceRow(String fieldName,
                                List<String> fieldPath,
                                Quizable target,
                                QuizableRenderContext renderContext,
                                QuizablePanelConfig openConfig,
                                String openTitle,
                                boolean expanded) {
        QuizablePanel.RenderStats.referenceRows++;
        this.fieldName = fieldName == null ? "" : fieldName;
        List<String> fieldPath1 = fieldPath == null ? List.of() : new ArrayList<>(fieldPath);
        this.fieldPath = fieldPath1;
        this.target = target;
        this.renderContext = renderContext;
        this.openConfig = openConfig;
        this.openTitle = openTitle;
        this.expanded = expanded;

        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(expanded
                ? "Click to collapse · shift-click to open in a window"
                : "Click to expand in place · shift-click to open in a window");

        putClientProperty(QuizableSearchPanel.FIELD_NAME_PROPERTY, "name");
        putClientProperty(QuizableSearchPanel.FIELD_PATH_PROPERTY, fieldPath1);
        putClientProperty(QuizableSearchPanel.FIELD_VALUE_PROPERTY, targetName());

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showCopyPopup(e.getPoint());
                    return;
                }

                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }

                // Default click toggles in-place expand/collapse; the whole
                // row is the target. Shift- or double-click still opens the
                // object in its own detail window.
                if (e.isShiftDown() || e.getClickCount() >= 2) {
                    openFullObject();
                } else {
                    toggleExpansion();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hover) {
                    hover = false;
                    repaint();
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean newHover =
                        targetBounds != null && targetBounds.contains(e.getPoint());

                if (newHover != hover) {
                    hover = newHover;
                    repaint();
                }
            }
        });
    }

    private void openInContext() {
        if (target == null) {
            return;
        }
        QuizablePanelConfig cfg = openConfig == null
                ? null
                : openConfig.copy();

        if (cfg == null && renderContext != null) {
            cfg = renderContext.configFor(target.getClass());
        }

        if (cfg == null) {
            cfg = QuizablePanelConfig.allWithMinorFields(target.getClass());
        }

        cfg.setAddListener(true);

        String title = openTitle == null || openTitle.isBlank()
                ? target.getName()
                : openTitle;

        QuizableFrame frame = new QuizableFrame(title, target, cfg);
    }

    private void openFullObject() {
        if (target == null) {
            return;
        }

        QuizablePanelConfig cfg =
                QuizablePanelConfig.allWithMinorFields(target.getClass())
                                   .setAddListener(true)
                                   .setThumb(true);
        QuizableFrame frame = new QuizableFrame(target, cfg);
    }

    private void showCopyPopup(Point p) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem copyValue = new JMenuItem("Copy value");
        copyValue.addActionListener(e -> copy(targetName()));
        menu.add(copyValue);

        if (!fieldName.isBlank()) {
            JMenuItem copyField = new JMenuItem("Copy field name");
            copyField.addActionListener(e -> copy(fieldName));

            JMenuItem copyPath = new JMenuItem("Copy field path");
            copyPath.addActionListener(e -> copy(String.join(".", fieldPath)));

            menu.addSeparator();
            menu.add(copyField);
            menu.add(copyPath);
        }

        menu.addSeparator();
        JMenuItem open = new JMenuItem("Open in window");
        open.addActionListener(e -> openFullObject());
        menu.add(open);

        menu.show(this, p.x, p.y);
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit()
               .getSystemClipboard()
               .setContents(new StringSelection(text == null ? "" : text), null);
    }

    private void toggleExpansion() {
        if (renderContext == null || target == null) {
            return;
        }

        renderContext.toggleExpanded(target);
        refreshRootCard();
    }

    // Rebuilds the whole card in place (QuizablePanel#refresh) so the
    // toggled reference re-renders as a chip or an inline panel. More
    // expensive than a local swap, but it reuses the existing modification
    // path and keeps nested state consistent.
    private void refreshRootCard() {
        QuizablePanel root = null;

        for (Container c = getParent(); c != null; c = c.getParent()) {
            if (c instanceof QuizablePanel qp) {
                root = qp;
            }
        }

        if (root != null) {
            root.refresh();
        }
    }

    private String targetName() {
        String n = target == null ? null : target.getName();
        return n == null ? "" : n;
    }

    private Font baseFont() {
        Font base = UIManager.getFont("Label.font");
        return base == null
                ? new Font(Font.SANS_SERIF, Font.PLAIN, 12)
                : base;
    }

    private Font fieldFont() {
        return baseFont().deriveFont(Font.BOLD);
    }

    private Font valueFont() {
        return baseFont();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fmField = getFontMetrics(fieldFont());
        FontMetrics fmValue = getFontMetrics(valueFont());

        String prefix = fieldName.isEmpty() ? "" : fieldName + ":";

        int w =
                PAD_X + TRI_W
                        + fmField.stringWidth(prefix)
                        + (prefix.isEmpty() ? 0 : GAP)
                        + fmValue.stringWidth(targetName())
                        + PAD_X;

        int h =
                Math.max(fmField.getHeight(), fmValue.getHeight())
                        + 2 * PAD_Y;

        return new Dimension(Math.max(100, w), h);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(80, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g.create();

        try {
            Font fieldFont = fieldFont();
            Font valueFont = valueFont();

            FontMetrics fmField = g2.getFontMetrics(fieldFont);
            FontMetrics fmValue = g2.getFontMetrics(valueFont);

            int baseline =
                    PAD_Y + Math.max(fmField.getAscent(), fmValue.getAscent());

            // expand/collapse triangle
            int triMid = baseline - fmValue.getAscent() / 2;
            g2.setColor(new Color(120, 120, 120));
            if (expanded) {
                g2.fillPolygon(
                        new int[]{PAD_X, PAD_X + 8, PAD_X + 4},
                        new int[]{triMid - 2, triMid - 2, triMid + 3},
                        3);
            } else {
                g2.fillPolygon(
                        new int[]{PAD_X + 1, PAD_X + 1, PAD_X + 6},
                        new int[]{triMid - 4, triMid + 4, triMid},
                        3);
            }

            int x = PAD_X + TRI_W;

            if (!fieldName.isEmpty()) {
                String prefix = fieldName + ":";

                g2.setFont(fieldFont);
                g2.setColor(getForeground());
                g2.drawString(prefix, x, baseline);

                x += fmField.stringWidth(prefix) + GAP;
            }

            String text = targetName();

            targetBounds = new Rectangle(
                    x,
                    baseline - fmValue.getAscent(),
                    fmValue.stringWidth(text),
                    fmValue.getHeight());

            if (hover && targetBounds.width > 0) {
                g2.setColor(new Color(220, 235, 255));
                g2.fillRoundRect(
                        targetBounds.x - 2,
                        targetBounds.y - 1,
                        targetBounds.width + 4,
                        targetBounds.height + 2,
                        6,
                        6);
            }

            g2.setFont(valueFont);
            g2.setColor(new Color(0, 80, 180));
            g2.drawString(text, x, baseline);
        } finally {
            g2.dispose();
        }
    }

    private void openOrFocus() {
        if (target == null) {
            return;
        }

        if (renderContext != null && renderContext.focusTopLevel(target)) {
            return;
        }

        new QuizableFrame(
                target,
                QuizablePanelConfig.allWithMinorFields(target.getClass())
                        .setAddListener(true)
                        .setThumb(true));
    }
}