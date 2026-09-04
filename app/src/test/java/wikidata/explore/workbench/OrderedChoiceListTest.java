package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two defects every hand-written copy of this control had.
 *
 * <p>Both follow from one mistake — using a Swing control's SELECTION as configuration —
 * and both were found by clicking through the aggregate editor, in a class that had
 * already been "fixed" once at another call site. Fixing them here is what stops the
 * third fix from being needed a fourth time.
 */
class OrderedChoiceListTest {

    /**
     * Clicking a row is inspection, and inspection changes nothing.
     *
     * <p>Directive 9. In the copies this control replaces, a plain click in a
     * multi-select list replaced the selection, and applying then wrote only what was
     * still selected — so reading one row deleted the rest.
     */
    @Test void clickingARowChangesNothing() {
        OrderedChoiceList<String> list = new OrderedChoiceList<>(true);
        list.show(List.of("category", "year"), List.of("laureate"));

        chosenList(list).setSelectedIndex(0);

        assertEquals(List.of("category", "year"), list.chosen(),
                "looking at one row does not remove the others");
    }

    /**
     * Nothing chosen is a state this control can be in.
     *
     * <p>A JComboBox selects its first item the moment one is added, so Add acted on
     * something nobody had picked: after removing "year" the box showed it again, and
     * pressing Add put it straight back.
     */
    @Test void addDoesNothingUntilSomethingIsChosen() {
        OrderedChoiceList<String> list = new OrderedChoiceList<>(true);
        list.show(List.of("category"), List.of("year", "laureate"));

        assertNull(available(list).getSelectedItem(), "nothing is chosen yet");
        assertFalse(button(list, "add").isEnabled(), "so Add has nothing to do");

        button(list, "add").doClick();
        assertEquals(List.of("category"), list.chosen(),
                "Add must not take whatever the box happens to show");

        available(list).setSelectedItem("year");
        assertTrue(button(list, "add").isEnabled());
        button(list, "add").doClick();
        assertEquals(List.of("category", "year"), list.chosen());
    }

    /** What is chosen is no longer on offer — a key component twice is not a tighter key. */
    @Test void whatIsChosenIsNoLongerOffered() {
        OrderedChoiceList<String> list = new OrderedChoiceList<>(true);
        list.show(List.of("category"), List.of("category", "year"));

        JComboBox<String> available = available(list);
        for (int i = 0; i < available.getItemCount(); i++) {
            assertNotEquals("category", available.getItemAt(i));
        }
    }

    /** Removing puts it back on offer, so the pair of halves stays whole. */
    @Test void removingOffersItAgain() {
        OrderedChoiceList<String> list = new OrderedChoiceList<>(true);
        list.show(List.of("category", "year"), List.of());

        chosenList(list).setSelectedIndex(1);
        button(list, "remove").doClick();

        assertEquals(List.of("category"), list.chosen());
        assertEquals("year", available(list).getItemAt(1));
    }

    /** Order is the meaning where position carries one, so it survives a move. */
    @Test void movingReordersWithoutLosingAnything() {
        OrderedChoiceList<String> list = new OrderedChoiceList<>(true);
        list.show(List.of("category", "year"), List.of());

        chosenList(list).setSelectedIndex(1);
        button(list, "up").doClick();

        assertEquals(List.of("year", "category"), list.chosen());
    }

    /** A default is replaced by the first real choice, never compounded with it. */
    @Test void addingToADefaultReplacesIt() {
        OrderedChoiceList<String> list = new OrderedChoiceList<>(true);
        list.show(List.of("source identity"), List.of("name"));
        list.mode(OrderedChoiceList.Mode.REPLACED_BY_ADDING);

        available(list).setSelectedItem("name");
        button(list, "add").doClick();

        assertEquals(List.of("name"), list.chosen(),
                "a default nobody chose must not become half of a compound key");
        assertFalse(button(list, "remove").isEnabled(),
                "and a default is not removable — that would leave no identity at all");
    }

    /** Supplied by production, so nothing here is a choice. */
    @Test void aFixedListOffersNoEdit() {
        OrderedChoiceList<String> list = new OrderedChoiceList<>(true);
        list.show(List.of("owner", "site"), List.of("name"));
        list.mode(OrderedChoiceList.Mode.FIXED);

        chosenList(list).setSelectedIndex(0);
        available(list).setSelectedItem("name");

        assertFalse(button(list, "add").isEnabled());
        assertFalse(button(list, "remove").isEnabled());
        assertFalse(button(list, "up").isEnabled());
    }

    /** A change is announced once, by the thing that made it. */
    @Test void everyEditAnnouncesItselfAndNothingElseDoes() {
        OrderedChoiceList<String> list = new OrderedChoiceList<>(true);
        int[] changes = {0};
        list.onChange(() -> changes[0]++);

        list.show(List.of("category"), List.of("year"));
        assertEquals(0, changes[0], "showing what is configured is not an edit");

        chosenList(list).setSelectedIndex(0);
        assertEquals(0, changes[0], "nor is selecting a row");

        available(list).setSelectedItem("year");
        button(list, "add").doClick();
        assertEquals(1, changes[0]);
    }

    @SuppressWarnings("unchecked")
    private static JList<String> chosenList(OrderedChoiceList<String> list) {
        return (JList<String>) read(list, "chosen");
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> available(OrderedChoiceList<String> list) {
        return (JComboBox<String>) read(list, "available");
    }

    private static JButton button(OrderedChoiceList<String> list, String name) {
        return (JButton) read(list, name);
    }

    private static Object read(OrderedChoiceList<String> list, String name) {
        try {
            var field = OrderedChoiceList.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(list);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
