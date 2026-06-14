package quiz.ui;

import aux.BrowserLauncher;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link quiz.annotations.Link}-annotated field as a clickable hyperlink:
 * left-click opens the URL, right-click copies it.
 *
 * Painted directly (no child Swing widgets) like the other lightweight
 * row components. The visible caption is, in priority order: the
 * annotation's {@code text()}, a {@code "label|url"} prefix in the value,
 * then the URL itself.
 */
public class QuizableLinkRow extends JComponent {

    private static final int PAD_X = 6;
    private static final int PAD_Y = 4;
    private static final int GAP = 8;

    private static final Color LINK_COLOR = new Color(0, 80, 200);

    private final String fieldName;
    private final List<String> fieldPath;
    private final String url;
    private final String label;

    private Rectangle linkBounds;
    private boolean hover = false;

    public QuizableLinkRow(
            String fieldName,
            List<String> fieldPath,
            String rawValue,
            String annotationText) {

        this.fieldName = fieldName == null ? "" : fieldName;
        this.fieldPath = fieldPath == null ? List.of() : new ArrayList<>(fieldPath);

        String value = rawValue == null ? "" : rawValue;

        // "label|url" convention
        String valueLabel = null;
        int bar = value.indexOf('|');
        if (bar > 0 && bar < value.length() - 1) {
            valueLabel = value.substring(0, bar).trim();
            value = value.substring(bar + 1).trim();
        }

        this.url = value;
        this.label =
                annotationText != null && !annotationText.isBlank()
                        ? annotationText.trim()
                        : valueLabel != null && !valueLabel.isBlank()
                                ? valueLabel
                                : this.url;

        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(this.url + "  ·  click to open · right-click to copy");

        putClientProperty(QuizableSearchPanel.FIELD_NAME_PROPERTY, this.fieldName);
        putClientProperty(QuizableSearchPanel.FIELD_PATH_PROPERTY, this.fieldPath);
        putClientProperty(QuizableSearchPanel.FIELD_VALUE_PROPERTY, this.url);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (linkBounds == null || !linkBounds.contains(e.getPoint())) {
                    return;
                }
                if (SwingUtilities.isRightMouseButton(e)) {
                    showPopup(e.getPoint());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    BrowserLauncher.open(url);
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
                boolean h = linkBounds != null && linkBounds.contains(e.getPoint());
                if (h != hover) {
                    hover = h;
                    repaint();
                }
            }
        });
    }

    private void showPopup(Point p) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem copyUrl = new JMenuItem("Copy URL");
        copyUrl.addActionListener(e -> copy(url));
        menu.add(copyUrl);

        if (!label.equals(url)) {
            JMenuItem copyLabel = new JMenuItem("Copy label");
            copyLabel.addActionListener(e -> copy(label));
            menu.add(copyLabel);
        }

        if (!fieldName.isBlank()) {
            JMenuItem copyField = new JMenuItem("Copy field name");
            copyField.addActionListener(e -> copy(fieldName));

            JMenuItem copyPath = new JMenuItem("Copy field path");
            copyPath.addActionListener(e -> copy(String.join(".", fieldPath)));

            menu.addSeparator();
            menu.add(copyField);
            menu.add(copyPath);
        }

        menu.show(this, p.x, p.y);
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit()
               .getSystemClipboard()
               .setContents(new StringSelection(text == null ? "" : text), null);
    }

    private Font baseFont() {
        Font base = UIManager.getFont("Label.font");
        return base == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : base;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fmField = getFontMetrics(baseFont().deriveFont(Font.BOLD));
        FontMetrics fmValue = getFontMetrics(baseFont());

        String prefix = fieldName.isEmpty() ? "" : fieldName + ":";

        int w = PAD_X
                + fmField.stringWidth(prefix)
                + (prefix.isEmpty() ? 0 : GAP)
                + fmValue.stringWidth(label)
                + PAD_X;

        int h = Math.max(fmField.getHeight(), fmValue.getHeight()) + 2 * PAD_Y;
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
            Font fieldFont = baseFont().deriveFont(Font.BOLD);
            Font valueFont = baseFont();

            FontMetrics fmField = g2.getFontMetrics(fieldFont);
            FontMetrics fmValue = g2.getFontMetrics(valueFont);

            int baseline = PAD_Y + Math.max(fmField.getAscent(), fmValue.getAscent());
            int x = PAD_X;

            if (!fieldName.isEmpty()) {
                String prefix = fieldName + ":";
                g2.setFont(fieldFont);
                g2.setColor(getForeground());
                g2.drawString(prefix, x, baseline);
                x += fmField.stringWidth(prefix) + GAP;
            }

            int textW = fmValue.stringWidth(label);
            linkBounds = new Rectangle(
                    x, baseline - fmValue.getAscent(), textW, fmValue.getHeight());

            g2.setFont(valueFont);
            g2.setColor(LINK_COLOR);
            g2.drawString(label, x, baseline);

            // underline (thicker on hover)
            int underY = baseline + 1;
            g2.fillRect(x, underY, textW, hover ? 2 : 1);
        } finally {
            g2.dispose();
        }
    }
}
