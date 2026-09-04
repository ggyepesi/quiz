package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import javax.swing.JList;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Clicking a row to look at it must not reconfigure the class.
 *
 * <p>The grouping pairs were a multi-select list whose SELECTION was the configuration,
 * and a plain click in such a list replaces the selection. So clicking one row to read it
 * deselected every other pair, and the next apply dropped them — and with them the key
 * components that depended on them. On Nobel that turned [category, year] into
 * [category], which would have merged 634 prizes into far fewer.
 */
class AggregatePairsAreNotSelectionTest {

    private static GeneratedProjectModel nobel() throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
    }

    @SuppressWarnings("unchecked")
    private static JList<Object> pairList(AggregateClassPanel panel) throws Exception {
        var field = AggregateClassPanel.class.getDeclaredField("keys");
        field.setAccessible(true);
        return (JList<Object>) field.get(panel);
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
                "two pairs are configured, so two are listed — not every compatible one");
    }

    @Test void removingAPairTakesItsKeyComponentWithIt() throws Exception {
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
        var remove = AggregateClassPanel.class.getDeclaredMethod("removeSelectedPair");
        remove.setAccessible(true);
        remove.invoke(panel);

        assertEquals(List.of("category"), prize.canonical().keyFields(),
                "a field with nothing to group from cannot identify anything");
    }
}
