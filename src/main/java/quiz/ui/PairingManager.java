package quiz.ui;

import quiz.Quiz;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages left/right panels for the pairing quiz with three stable states:
 *   original/gray = idle
 *   pink          = selected
 *   colored       = paired
 * Important: this preserves the original ViewablePanel border by wrapping it
 * in an overlay border instead of replacing it.
 */
public class PairingManager {

    private static final String ORIGINAL_BORDER =
            "quiz.pairing.originalBorder";

    private final JButton nextButton = new JButton("Next");

    private final Map<JPanel, JPanel> leftToRight = new HashMap<>();
    private final Map<JPanel, JPanel> rightToLeft = new HashMap<>();

    private final Color[] COLORS = {
            Color.RED,
            Color.BLUE,
            Color.GREEN.darker(),
            Color.MAGENTA,
            Color.ORANGE,
            Color.CYAN.darker(),
            new Color(200, 100, 0)
    };

    private int colorIndex = 0;

    private JPanel pinkPanel = null;
    private boolean pinkIsLeft = false;
    private int expectedPairs = 0;

    public PairingManager() {
        nextButton.setFont(new Font("Arial", Font.BOLD, 18));
        nextButton.setEnabled(false);
    }

    public JButton getNextButton() {
        return nextButton;
    }

    public void setExpectedPairs(int expectedPairs) {
        this.expectedPairs = expectedPairs;
        checkSolved();
    }

    public void registerPanel(JPanel panel, boolean isLeft) {
        if (panel == null) {
            return;
        }

        rememberOriginalBorder(panel);
        setGray(panel);

        Quiz.addMouseListenerRecursively(
                panel,
                new ClickHandler(panel, isLeft));
    }

    private class ClickHandler extends MouseAdapter {
        private final JPanel panel;
        private final boolean isLeft;

        ClickHandler(JPanel panel, boolean isLeft) {
            this.panel = panel;
            this.isLeft = isLeft;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (nextButton.isEnabled()) {
                return;
            }

            processClick(panel, isLeft);
        }
    }

    private void processClick(JPanel clicked, boolean isLeft) {
        JPanel partner = getPartner(clicked);

        // same selected panel -> deselect
        if (clicked == pinkPanel) {
            setGray(clicked);
            pinkPanel = null;
            checkSolved();
            repaintParent(clicked);
            return;
        }

        // no active selection
        if (pinkPanel == null) {
            if (partner != null) {
                unpair(clicked, partner);
            }

            setPink(clicked);
            pinkPanel = clicked;
            pinkIsLeft = isLeft;

            checkSolved();
            repaintParent(clicked);
            return;
        }

        // active selection exists, same side -> switch selection
        if (pinkIsLeft == isLeft) {
            setGray(pinkPanel);

            if (partner != null) {
                unpair(clicked, partner);
            }

            setPink(clicked);
            pinkPanel = clicked;
            pinkIsLeft = isLeft;

            checkSolved();
            repaintParent(clicked);
            return;
        }

        // active selection exists, opposite side -> make/reassign pair
        if (partner != null) {
            unpair(clicked, partner);
        }

        JPanel left = pinkIsLeft ? pinkPanel : clicked;
        JPanel right = pinkIsLeft ? clicked : pinkPanel;

        Color color = COLORS[colorIndex++ % COLORS.length];

        pair(left, right, color);

        pinkPanel = null;

        checkSolved();
        repaintParent(clicked);
    }

    private void pair(JPanel left, JPanel right, Color color) {
        removePair(left);
        removePair(right);

        leftToRight.put(left, right);
        rightToLeft.put(right, left);

        setColored(left, color);
        setColored(right, color);
    }

    private void unpair(JPanel a, JPanel b) {
        leftToRight.remove(a);
        leftToRight.remove(b);
        rightToLeft.remove(a);
        rightToLeft.remove(b);

        if (pinkPanel == a || pinkPanel == b) {
            pinkPanel = null;
        }

        setGray(a);
        setGray(b);
    }

    private void removePair(JPanel panel) {
        JPanel partner = getPartner(panel);

        if (partner != null) {
            unpair(panel, partner);
        }
    }

    private JPanel getPartner(JPanel panel) {
        JPanel p = leftToRight.get(panel);

        if (p != null) {
            return p;
        }

        return rightToLeft.get(panel);
    }

    private void checkSolved() {
        boolean solved =
                expectedPairs > 0
                        && leftToRight.size() == expectedPairs
                        && rightToLeft.size() == expectedPairs;

        nextButton.setEnabled(solved);
    }

    private void rememberOriginalBorder(JPanel p) {
        if (p.getClientProperty(ORIGINAL_BORDER) == null) {
            p.putClientProperty(ORIGINAL_BORDER, p.getBorder());
        }
    }

    private Border originalBorder(JPanel p) {
        Object o = p.getClientProperty(ORIGINAL_BORDER);

        if (o instanceof Border b) {
            return b;
        }

        Border current = p.getBorder();
        p.putClientProperty(ORIGINAL_BORDER, current);
        return current;
    }

    private void setGray(JPanel p) {
        p.setOpaque(true);
        p.setBorder(originalBorder(p));
        p.repaint();
    }

    private void setPink(JPanel p) {
        p.setOpaque(true);

        Border overlay =
                BorderFactory.createLineBorder(Color.PINK, 4, true);

        p.setBorder(BorderFactory.createCompoundBorder(
                overlay,
                originalBorder(p)));

        p.repaint();
    }

    private void setColored(JPanel p, Color color) {
        p.setOpaque(true);

        Border overlay =
                new LineBorder(color, 4, true);

        p.setBorder(BorderFactory.createCompoundBorder(
                overlay,
                originalBorder(p)));

        p.repaint();
    }

    private void repaintParent(JPanel p) {
        SwingUtilities.invokeLater(() -> {
            Container parent = p.getParent();

            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }

            p.revalidate();
            p.repaint();
        });
    }
}