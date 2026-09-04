package wikidata.explore.query.swing;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.render.RenderContext;
import org.junit.jupiter.api.Test;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "used by" decoration: what points at this card, and the way back to it.
 *
 * <p>It rides the decorator seam the panel already owns, so nothing in objectview
 * changes, and the jump is the one the forward chips already make — focusTopLevel
 * scrolls to the card and flashes it, landing in whichever section owns the target
 * because the sections share one context.
 */
class UsedByChipsNavigateBackTest {

    static final class Award implements Viewable {
        private final String id;
        Award(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public String typeName() { return "LaureatesWithMotivation"; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }

    static final class Prize implements Viewable {
        private final String id;
        public List<Award> laureatesWithMotivation = new ArrayList<>();
        Prize(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public String typeName() { return "NobelPrize"; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }

    /** Records what it was asked to reveal. */
    static final class RecordingContext extends RenderContext {
        Object focused;
        @Override public boolean focusTopLevel(Object object) {
            focused = object;
            return true;
        }
    }

    private final Award einstein = new Award("Einstein");
    private final Prize physics = new Prize("Physics 1921");

    private ObjectQueryResult result() {
        physics.laureatesWithMotivation.add(einstein);
        return new ObjectQueryResult(List.of(physics), Prize.class, "test");
    }

    @Test void thePointedAtCardSaysWhatPointsAtIt() {
        JComponent decoration = ReferrerChips
                .over(instance -> null, result(), RecordingContext::new)
                .apply(einstein);

        assertNotNull(decoration, "a record grouped by a prize can say so");
        String text = allText(decoration);
        assertTrue(text.contains("used by"), text);
        assertTrue(text.contains("Physics 1921"), text);
        assertTrue(text.contains("laureatesWithMotivation"),
                "which edge, not only which object: " + text);
    }

    /** Clicking it reveals the owner, through the context the sections share. */
    @Test void clickingAChipRevealsTheOwner() {
        RecordingContext context = new RecordingContext();
        JComponent decoration = ReferrerChips
                .over(instance -> null, result(), () -> context)
                .apply(einstein);

        JLabel chip = labelContaining(decoration, "laureatesWithMotivation");
        assertNotNull(chip, allText(decoration));
        MouseEvent click =
                new MouseEvent(chip, MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, false);
        // Every listener, not the first: a label's UI installs its own, and which comes
        // first is Swing's business.
        for (java.awt.event.MouseListener listener : chip.getMouseListeners()) {
            listener.mouseClicked(click);
        }

        assertSame(physics, context.focused,
                "the same jump the forward chips make, in the other direction");
    }

    /** Nothing points at the prize, so its card gains nothing. */
    @Test void aCardNothingPointsAtIsLeftAlone() {
        assertNull(ReferrerChips.over(instance -> null, result(), RecordingContext::new)
                        .apply(physics),
                "an empty \"used by\" is a label with nothing to say");
    }

    /** What a card IS still comes first. */
    @Test void theIdentityDecorationKeepsItsPlace() {
        JComponent decoration = ReferrerChips
                .over(instance -> new JLabel("Q937"), result(), RecordingContext::new)
                .apply(einstein);

        assertEquals("Q937", firstLabel(decoration),
                "identity before the edges that reach it: " + allText(decoration));
    }

    /** With no identity and no referrers there is nothing to decorate with. */
    @Test void nothingToSayDecoratesNothing() {
        ObjectQueryResult empty =
                new ObjectQueryResult(List.of(physics), Prize.class, "test");

        assertNull(ReferrerChips.over(instance -> null, empty, RecordingContext::new)
                .apply(physics));
    }

    private static String firstLabel(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label) return label.getText();
            if (child instanceof Container container) {
                String found = firstLabel(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel labelContaining(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null
                    && label.getText().contains(text)) {
                return label;
            }
            if (child instanceof Container container) {
                JLabel found = labelContaining(container, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String allText(Container root) {
        StringBuilder text = new StringBuilder();
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null) {
                text.append(label.getText()).append(' ');
            }
            if (child instanceof Container container) text.append(allText(container));
        }
        return text.toString();
    }
}
