package wikidata.explore.query.swing;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every type in a result is rendered the way a single type is, and in a stated order.
 *
 * <p>Several types used to be laid out as side-by-side sections — a second presentation
 * of the same objects, and a much smaller one: two types split the available width, so
 * embedded in a panel rather than a wide window the result came out as search headers
 * with no room for any cards. The reader then had the same sample in two places
 * disagreeing about how it looked.
 */
class MultiTypeResultIsTabbedTest {

    /** A viewable with a type name — the panel groups on exactly this. */
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

    @Test void eachTypeGetsItsOwnTabAndTheWholeWidth() throws Exception {
        JTabbedPane tabs = tabsFor(List.of());

        assertNotNull(tabs, "two types, so the reader chooses one rather than sharing");
        assertEquals(2, tabs.getTabCount());
    }

    /** The producer's order is what the tabs are in. */
    @Test void theStatedOrderIsTheTabOrder() throws Exception {
        JTabbedPane derivedFirst =
                tabsFor(List.of("NobelPrize", "LaureatesWithMotivation"));
        assertTrue(derivedFirst.getTitleAt(0).contains("NobelPrize"),
                derivedFirst.getTitleAt(0));

        JTabbedPane populationFirst =
                tabsFor(List.of("LaureatesWithMotivation", "NobelPrize"));
        assertTrue(populationFirst.getTitleAt(0).contains("LaureatesWithMotivation"),
                "traversal order is not an order: " + populationFirst.getTitleAt(0));
    }

    /** A type the order does not name still appears, after the ones it does. */
    @Test void anUnnamedTypeFollowsTheNamedOnes() throws Exception {
        JTabbedPane tabs = tabsFor(List.of("LaureatesWithMotivation"));

        assertTrue(tabs.getTitleAt(0).contains("LaureatesWithMotivation"));
        assertTrue(tabs.getTitleAt(1).contains("NobelPrize"));
    }

    /** One type is still one panel: tabs are for choosing between several. */
    @Test void aSingleTypeIsNotTabbed() throws Exception {
        QueryObjectResultPanel panel = new QueryObjectResultPanel();
        panel.accept(new ObjectQueryResult(
                List.of(new Prize("p1", "Physics 1921")), Prize.class, "test"));
        flush();

        assertNull(find(panel, JTabbedPane.class),
                "a tab strip over one tab is a control with nothing to choose");
    }

    private static JTabbedPane tabsFor(List<String> typeOrder) throws Exception {
        QueryObjectResultPanel panel = new QueryObjectResultPanel();
        List<Viewable> objects = new ArrayList<>(List.of(
                new Prize("p1", "Physics 1921"),
                new Award("a1", "Einstein"),
                new Award("a2", "Bohr")));
        panel.accept(new ObjectQueryResult(objects, Prize.class, "test", typeOrder));
        flush();
        return find(panel, JTabbedPane.class);
    }

    private static void flush() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
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
