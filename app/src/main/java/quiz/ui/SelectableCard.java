package quiz.ui;

import objectview.render.Card;
import objectview.Viewable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Interaction and visual-state layer around a content-only Card. The wrapper
 * owns quiz highlighting, so changing selection never replaces Card's own
 * structural border.
 */
public final class SelectableCard extends JPanel {
    private static final Color IDLE_BACKGROUND = new Color(250, 250, 250);
    private static final Color EXHAUSTED_BACKGROUND = new Color(230, 230, 230);
    private static final Color CORRECT_BACKGROUND = new Color(170, 255, 170);
    private static final Color WRONG_BACKGROUND = new Color(255, 190, 190);

    private final Viewable item;
    private final JComponent content;
    private final boolean framedIdle;
    private CardSelectionState state = CardSelectionState.IDLE;
    private Color matchColor = Color.BLUE;
    private Consumer<Viewable> onSelected = ignored -> {};

    public SelectableCard(Viewable item, Card card) {
        this(item, card, true);
    }

    public SelectableCard(
            Viewable item, JComponent content, boolean framedIdle) {
        this.item = Objects.requireNonNull(item);
        this.content = Objects.requireNonNull(content);
        this.framedIdle = framedIdle;
        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);
        addMouseListenerRecursively(this, new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (isSelectable()) {
                    onSelected.accept(SelectableCard.this.item);
                }
            }
        });
        applyState();
    }

    public Viewable item() {
        return item;
    }

    public JComponent content() {
        return content;
    }

    public Card card() {
        return content instanceof Card card ? card : null;
    }

    public CardSelectionState state() {
        return state;
    }

    public void setState(CardSelectionState state) {
        this.state = state == null ? CardSelectionState.IDLE : state;
        applyState();
    }

    public void setMatchColor(Color color) {
        matchColor = color == null ? Color.BLUE : color;
        if (state == CardSelectionState.MATCHED) {
            applyState();
        }
    }

    public void onSelected(Consumer<Viewable> callback) {
        onSelected = callback == null ? ignored -> {} : callback;
    }

    public boolean isSelectable() {
        return isEnabled()
                && state != CardSelectionState.EXHAUSTED
                && state != CardSelectionState.DISABLED
                && state != CardSelectionState.CORRECT
                && state != CardSelectionState.WRONG;
    }

    private void applyState() {
        Border border;
        Color background = null;
        switch (state) {
            case IDLE -> {
                border = framedIdle
                        ? BorderFactory.createLineBorder(Color.GRAY, 2, true)
                        : BorderFactory.createEmptyBorder(4, 4, 4, 4);
                if (framedIdle) background = IDLE_BACKGROUND;
            }
            case SELECTED -> border =
                    BorderFactory.createLineBorder(Color.PINK, 4, true);
            case CORRECT -> {
                border = BorderFactory.createLineBorder(
                        Color.GREEN.darker(), 3, true);
                background = CORRECT_BACKGROUND;
            }
            case WRONG -> {
                border = BorderFactory.createLineBorder(
                        Color.RED.darker(), 3, true);
                background = WRONG_BACKGROUND;
            }
            case MATCHED -> border =
                    BorderFactory.createLineBorder(matchColor, 4, true);
            case EXHAUSTED, DISABLED -> {
                border = BorderFactory.createLineBorder(
                        Color.LIGHT_GRAY, 3, true);
                background = EXHAUSTED_BACKGROUND;
            }
            default -> throw new IllegalStateException("Unknown state " + state);
        }
        setBorder(border);
        setOpaque(background != null);
        if (background != null) {
            setBackground(background);
        }
        revalidate();
        repaint();
    }

    private static void addMouseListenerRecursively(
            Component component, MouseListener listener) {
        component.addMouseListener(listener);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                addMouseListenerRecursively(child, listener);
            }
        }
    }
}
