package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import javax.swing.JList;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Clicking a row to look at it must not reconfigure the class.
 *
 * <p>The grouping pairs were a multi-select list whose SELECTION was the configuration,
 * and a plain click in such a list replaces the selection. So clicking one row to read it
 * deselected every other pair, and the next apply dropped them — and with them the key
 * components that depended on them. On Nobel that turned [category, year] into
 * [category], which would have merged 634 prizes into far fewer.
 *
 * <p>The defect is now unrepresentable rather than merely fixed: the pairs live in
 * {@link OrderedChoiceList}, whose list holds the choice and whose selection only says
 * what Remove would act on. These stay to assert it through the aggregate editor.
 */
class AggregatePairsAreNotSelectionTest {

    private static GeneratedProjectModel nobel() throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
    }

    private static OrderedChoiceList<?> pairs(AggregateClassPanel panel) throws Exception {
        var field = AggregateClassPanel.class.getDeclaredField("keyFields");
        field.setAccessible(true);
        return (OrderedChoiceList<?>) field.get(panel);
    }

    @SuppressWarnings("unchecked")
    private static JList<Object> pairList(AggregateClassPanel panel) throws Exception {
        var field = OrderedChoiceList.class.getDeclaredField("chosen");
        field.setAccessible(true);
        return (JList<Object>) field.get(pairs(panel));
    }

    /** Remove is the control's own button, pressed the way the modeller presses it. */
    private static void pressRemove(AggregateClassPanel panel) throws Exception {
        var field = OrderedChoiceList.class.getDeclaredField("remove");
        field.setAccessible(true);
        ((javax.swing.JButton) field.get(pairs(panel))).doClick();
    }

    @Test void clickingAPairChangesNeitherThePairsNorTheKey() throws Exception {
        GeneratedProjectModel project = nobel();
        GeneratedClassModel prize = project.findClass("NobelPrize");

        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(prize);
        pairList(panel).setSelectedIndex(0);
        panel.applyEdits();

        assertEquals(List.of("category", "year"), prize.canonical().keyFields(),
                "looking at one pair does not remove the others");
        assertEquals(2, prize.aggregateSource().keys().size());
    }

    /** The list shows what is configured, so applying reads its contents. */
    @Test void thePairsShownAreTheOnesConfigured() throws Exception {
        GeneratedProjectModel project = nobel();
        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(project.findClass("NobelPrize"));

        assertEquals(2, pairList(panel).getModel().getSize(),
                "two fields are inherited, so two are listed — not every one that could be");
    }

    @Test void notInheritingAFieldTakesItsKeyComponentWithIt() throws Exception {
        GeneratedProjectModel project = nobel();
        GeneratedClassModel prize = project.findClass("NobelPrize");

        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(prize);
        JList<Object> pairs = pairList(panel);
        int year = 0;
        for (int i = 0; i < pairs.getModel().getSize(); i++) {
            if (String.valueOf(pairs.getModel().getElementAt(i)).contains("year")) year = i;
        }
        pairs.setSelectedIndex(year);
        pressRemove(panel);

        assertEquals(List.of("category"), prize.canonical().keyFields(),
                "a field with nothing to group from cannot identify anything");
    }

    @Test void theIdentityViewCannotCompeteWithTheAggregateKeyList()
            throws Exception {
        GeneratedProjectModel project = nobel();
        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(project.findClass("NobelPrize"));

        var identityField = AggregateClassPanel.class.getDeclaredField("identityEditor");
        identityField.setAccessible(true);
        ClassIdentityEditor identity =
                (ClassIdentityEditor) identityField.get(panel);
        var keyField = ClassIdentityEditor.class.getDeclaredField("key");
        keyField.setAccessible(true);
        OrderedChoiceList<?> key = (OrderedChoiceList<?>) keyField.get(identity);
        var chosenField = OrderedChoiceList.class.getDeclaredField("chosen");
        chosenField.setAccessible(true);
        JList<?> chosen = (JList<?>) chosenField.get(key);
        chosen.setSelectedIndex(0);
        var removeField = OrderedChoiceList.class.getDeclaredField("remove");
        removeField.setAccessible(true);

        assertFalse(((javax.swing.JButton) removeField.get(key)).isEnabled(),
                "the aggregate recipe is the one author of its key");
    }
}
