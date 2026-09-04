package wikidata.explore.query.swing;

import objectview.Viewable;
import objectview.render.RenderContext;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * "Used by" — the reference edges of a result, shown on the card they point AT.
 *
 * <p>A card has always shown what it points to. Nothing showed what points to it, and
 * for a class with no population of its own that is the more useful direction: a Nobel
 * prize lists the award records it grouped, but a record could not say which prize took
 * it — which is the connection a modeller checking a key wants to follow.
 *
 * <p>The jump is the one the forward chips already make: {@link RenderContext#focusTopLevel}
 * scrolls to the card and flashes it, and because the sections share one context it lands
 * in whichever section owns the target. RenderContext keeps a back-stack, so the way back
 * is the same gesture.
 *
 * <p>This is a decoration, not a field. The edge is derived from the objects on screen
 * and stored nowhere: a back-reference in the model would put it in everything served,
 * which is production carrying something only a view wants.
 */
final class ReferrerChips {

    /** Beyond this the header stops being a header. The rest are named in the tooltip. */
    private static final int MAX_CHIPS = 3;

    private ReferrerChips() { }

    /**
     * A decorator that adds "used by" chips to whatever decoration is already there.
     *
     * @param identity the existing decoration — the identity chip, kept first, because
     *                 what a card IS comes before what refers to it
     * @param context  supplies the live context, which does not exist until the view is
     *                 built and the cards are decorated as they are constructed
     */
    static Function<Viewable, JComponent> over(
            Function<Viewable, JComponent> identity,
            ObjectQueryResult result,
            Supplier<RenderContext> context) {
        Map<Viewable, List<ObjectQueryResult.Referrer>> referrers = result.referrers();
        return instance -> {
            JComponent chip = identity == null ? null : identity.apply(instance);
            List<ObjectQueryResult.Referrer> pointing = referrers.get(instance);
            if (pointing == null || pointing.isEmpty()) return chip;

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row.setOpaque(false);
            if (chip != null) row.add(chip);
            row.add(new JLabel("used by"));
            for (ObjectQueryResult.Referrer referrer : pointing.stream()
                    .limit(MAX_CHIPS).toList()) {
                row.add(chipFor(referrer, context));
            }
            if (pointing.size() > MAX_CHIPS) {
                JLabel more = new JLabel("+" + (pointing.size() - MAX_CHIPS));
                more.setToolTipText(names(pointing));
                row.add(more);
            }
            return row;
        };
    }

    private static JComponent chipFor(
            ObjectQueryResult.Referrer referrer, Supplier<RenderContext> context) {
        JLabel chip = new JLabel(label(referrer));
        chip.setFont(chip.getFont().deriveFont(Font.PLAIN,
                chip.getFont().getSize2D() - 1f));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(chip.getForeground(), 1, true),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)));
        chip.setToolTipText("Go to " + name(referrer.owner())
                + " — it holds this in " + referrer.field() + ".");
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        chip.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                RenderContext live = context.get();
                if (live != null) live.focusTopLevel(referrer.owner());
            }
        });
        return chip;
    }

    /**
     * What the chip says.
     *
     * <p>The field is named as well as the owner, because a card reached from two edges
     * of the same type is the interesting case and "NobelPrize" twice explains nothing.
     */
    private static String label(ObjectQueryResult.Referrer referrer) {
        String owner = name(referrer.owner());
        return referrer.field().isBlank() ? owner : owner + " · " + referrer.field();
    }

    private static String name(Viewable value) {
        if (value == null) return "?";
        String name = value.getDisplayName();
        if (name != null && !name.isBlank()) return name;
        String type = value.typeName();
        return type == null || type.isBlank() ? "?" : type;
    }

    private static String names(List<ObjectQueryResult.Referrer> referrers) {
        return "<html>" + referrers.stream().map(ReferrerChips::label)
                .reduce((a, b) -> a + "<br>" + b).orElse("") + "</html>";
    }
}
