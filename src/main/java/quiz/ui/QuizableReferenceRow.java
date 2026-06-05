package quiz.ui;

import quiz.Quizable;
import quiz.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;

public class QuizableReferenceRow extends JComponent {
    private static final int PAD_X = 6;
    private static final int PAD_Y = 4;
    private static final int GAP = 8;

    private final String fieldName;
    private final Quizable target;
    private final QuizableRenderContext renderContext;
    private final QuizablePanelConfig openConfig;
    private final String openTitle;

    private Rectangle targetBounds;
    private boolean hover = false;

    public QuizableReferenceRow(String fieldName,
                                List<String> fieldPath,
                                Quizable target,
                                QuizableRenderContext renderContext,
                                QuizablePanelConfig openConfig,
                                String openTitle) {
        QuizablePanel.RenderStats.referenceRows++;
        this.fieldName = fieldName == null ? "" : fieldName;
        List<String> fieldPath1 = fieldPath == null ? List.of() : new ArrayList<>(fieldPath);
        this.target = target;
        this.renderContext = renderContext;
        this.openConfig = openConfig;
        this.openTitle = openTitle;

        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(targetName());

        putClientProperty(QuizableSearchPanel.FIELD_NAME_PROPERTY, "name");
        putClientProperty(QuizableSearchPanel.FIELD_PATH_PROPERTY, fieldPath1);
        putClientProperty(QuizableSearchPanel.FIELD_VALUE_PROPERTY, targetName());

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (targetBounds == null || !targetBounds.contains(e.getPoint())) {
                    return;
                }

                if (e.isShiftDown() || e.getClickCount() >= 2) {
                    openFullObject();
                } else {
                    openInContext();
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
                PAD_X
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

            int x = PAD_X;

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