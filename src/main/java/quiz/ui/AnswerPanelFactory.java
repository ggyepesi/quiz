package quiz.ui;

import aux.GridBagUtils;
import objectview.render.Card;
import objectview.viewconfig.ViewConfig;
import quiz.Quiz;
import quiz.Quizable;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds consistent answer panels using the provided answerConfig from the quiz.
 */
public class AnswerPanelFactory {

    private final ViewConfig answerConfig;

    public AnswerPanelFactory(ViewConfig answerConfig) {
        this.answerConfig = answerConfig;
    }

    /**
     * Creates a grid of answer panels for the provided Quizable options.
     *
     * @param options list of Quizable answers to render
     * @param onSelect callback triggered when a user clicks an answer
     * @return JPanel with all answer options laid out
     */
    public JPanel createAnswerPanels(List<? extends Quizable> options,
                                     Consumer<Quizable> onSelect) {

        JPanel panel = new JPanel(new GridBagLayout());
        int col = 0, row = 0;

        for (Quizable q : options) {
            ViewConfig cfg = answerConfig == null
                    ? new ViewConfig()
                    : answerConfig.copy();
            cfg.setThumb(true); // common for answer panels

            Card qp = new Card(q, cfg, options, false);
            Quiz.addMouseListenerRecursively(qp, new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    onSelect.accept(q);
                }
            });

            panel.add(qp, GridBagUtils.gbc(col, row,
                    1.0, 1.0,
                    GridBagConstraints.CENTER,
                    GridBagConstraints.BOTH,
                    new Insets(6, 6, 6, 6)));

            if (++col == 2) { col = 0; row++; }
        }

        return panel;
    }

    public static final class ScrollUtils {
        private ScrollUtils() {}

        public static void enableScrollPropagation(Component root, JScrollPane ownerScrollPane) {
            if (root == null || ownerScrollPane == null) {
                return;
            }

            MouseWheelListener listener = e -> forwardToParentScrollPaneIfNeeded(e, ownerScrollPane);

            addRecursively(root, listener);

            JViewport viewport = ownerScrollPane.getViewport();
            if (viewport != null) {
                viewport.addMouseWheelListener(listener);
            }

            ownerScrollPane.addMouseWheelListener(listener);
        }

        private static void addRecursively(Component c, MouseWheelListener listener) {
            c.addMouseWheelListener(listener);
            if (c instanceof Container container) {
                for (Component child : container.getComponents()) {
                    addRecursively(child, listener);
                }
            }
        }

        private static void forwardToParentScrollPaneIfNeeded(MouseWheelEvent e, JScrollPane ownerScrollPane) {
            if (e.isConsumed()) {
                return;
            }

            if (canScroll(ownerScrollPane, e.getWheelRotation())) {
                return;
            }

            JScrollPane parent = findParentScrollPane(ownerScrollPane);
            if (parent == null) {
                return;
            }

            e.consume();

            MouseWheelEvent converted = (MouseWheelEvent)
                    SwingUtilities.convertMouseEvent(e.getComponent(), e, parent);

            parent.dispatchEvent(converted);
        }

        private static JScrollPane findParentScrollPane(Component c) {
            Container p = c.getParent();
            while (p != null) {
                if (p instanceof JScrollPane sp) {
                    return sp;
                }
                p = p.getParent();
            }
            return null;
        }

        private static boolean canScroll(JScrollPane scrollPane, int wheelRotation) {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            if (bar == null || !bar.isVisible()) {
                return false;
            }

            int value = bar.getValue();
            int min = bar.getMinimum();
            int max = bar.getMaximum() - bar.getVisibleAmount();

            if (wheelRotation < 0) {
                return value > min;
            } else if (wheelRotation > 0) {
                return value < max;
            }
            return false;
        }
    }

    public static final class TextUiUtils {
        private TextUiUtils() {}

        public static JComponent createWrappedText(String text) {
            if (text == null) {
                text = "";
            }

            String escaped = text
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");

            JLabel label = new JLabel(
                    "<html><div style='width:320px;'>" + escaped + "</div></html>");

            label.setVerticalAlignment(SwingConstants.TOP);

            return label;
        }

        public static JComponent createWrappedText2(String text) {
            return new JLabel(text);
        }

        // Caret causes uncontrollable scrolling when the toplevel frame is shown first.
        public static JComponent createWrappedText1 (String text) {
            JTextArea area = new JTextArea(text == null ? "" : text);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setEditable(false);
            area.setOpaque(false);
            area.setBorder(null);
            area.setFocusable(false);
            area.setFont(UIManager.getFont("Label.font"));
            area.setAlignmentX(Component.LEFT_ALIGNMENT);

            DefaultCaret caret = (DefaultCaret) area.getCaret();
            caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
            caret.setVisible(false);
            area.setCaretPosition(0);
            area.setColumns(28);
            area.setSize(new Dimension(320, Short.MAX_VALUE));
            Dimension wrapped = area.getPreferredSize();
            area.setPreferredSize(new Dimension(320, wrapped.height));
            area.setMaximumSize(new Dimension(420, wrapped.height));
            return area;
        }
    }
}
