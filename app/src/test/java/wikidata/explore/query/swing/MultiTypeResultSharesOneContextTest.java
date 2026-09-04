package wikidata.explore.query.swing;

import objectview.Viewable;
import objectview.demo.MultiView;
import org.junit.jupiter.api.Test;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Several types are shown side by side, sharing ONE render context, in a stated order.
 *
 * <p>The shared context is the point of this layout rather than a detail of it: a
 * reference highlighted in one section lights up in the other only while both render
 * through the same context, and navigation reveals a card by scrolling to it — which a
 * tabbed layout defeats, since focusing a card on an inactive tab shows nothing.
 * {@code MultiView.layout} says so in as many words; this test is that comment made to
 * fail. It was written after tabs were tried here and every cross-panel highlight went
 * dead.
 */
class MultiTypeResultSharesOneContextTest {

    record Prize(String id, String name) implements Viewable {
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return name; }
        @Override public String typeName() { return "NobelPrize"; }
        @Override public objectview.field.FieldSet fields() {
            return objectview.field.FieldSet.of(this);
        }
    }

    record Award(String id, String name) implements Viewable {
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return name; }
        @Override public String typeName() { return "LaureatesWithMotivation"; }
        @Override public objectview.field.FieldSet fields() {
            return objectview.field.FieldSet.of(this);
        }
    }

    @Test void sectionsShareTheContextThatCarriesTheHighlight() throws Exception {
        QueryObjectResultPanel panel = panelShowing(List.of());

        MultiView multi = find(panel, MultiView.class);
        assertNotNull(multi, "side by side, so a reference can be seen in both at once");
        assertNull(find(panel, JTabbedPane.class),
                "a card focused on an inactive tab is a card nobody is shown");
        assertSame(multi.context(), panel.activeRenderContext(),
                "one context across the sections, or nothing relates them");
    }

    /** The producer's order is the order the sections are laid out in. */
    @Test void theStatedOrderIsTheSectionOrder() throws Exception {
        assertEquals(List.of("NobelPrize", "LaureatesWithMotivation"),
                sectionTitles(panelShowing(
                        List.of("NobelPrize", "LaureatesWithMotivation"))));

        assertEquals(List.of("LaureatesWithMotivation", "NobelPrize"),
                sectionTitles(panelShowing(
                        List.of("LaureatesWithMotivation", "NobelPrize"))),
                "traversal order is not an order");
    }

    /** A type the order does not name still appears, after the ones it does. */
    @Test void anUnnamedTypeFollowsTheNamedOnes() throws Exception {
        assertEquals(List.of("LaureatesWithMotivation", "NobelPrize"),
                sectionTitles(panelShowing(List.of("LaureatesWithMotivation"))));
    }

    /** Every section says how many it holds, counted the way the result counts. */
    @Test void aSectionSaysHowManyItHolds() throws Exception {
        QueryObjectResultPanel panel = panelShowing(List.of());

        assertTrue(titlesWithCounts(panel).contains("LaureatesWithMotivation  (2)"),
                titlesWithCounts(panel).toString());
    }

    private static QueryObjectResultPanel panelShowing(List<String> typeOrder)
            throws Exception {
        QueryObjectResultPanel panel = new QueryObjectResultPanel();
        List<Viewable> objects = new ArrayList<>(List.of(
                new Prize("p1", "Physics 1921"),
                new Award("a1", "Einstein"),
                new Award("a2", "Bohr")));
        panel.accept(new ObjectQueryResult(objects, Prize.class, "test", typeOrder));
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        return panel;
    }

    /** Section titles carry their count; the order is what these tests are about. */
    private static List<String> sectionTitles(Container root) {
        List<String> titles = new ArrayList<>();
        for (String title : titlesWithCounts(root)) {
            titles.add(title.split("\\s\\s\\(")[0]);
        }
        return titles;
    }

    private static List<String> titlesWithCounts(Container root) {
        List<String> titles = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent component
                    && component.getBorder() instanceof TitledBorder titled
                    && titled.getTitle() != null
                    // A section's border carries its count; the inner panels a
                    // SearchableView draws have titled borders of their own.
                    && titled.getTitle().matches(".*\\s\\s\\(\\d+\\)$")) {
                titles.add(titled.getTitle());
            }
            if (child instanceof Container container) {
                titles.addAll(titlesWithCounts(container));
            }
        }
        return titles;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
