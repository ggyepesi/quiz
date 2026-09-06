package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.GeneratedClassModel;

import javax.swing.JComboBox;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the aggregate editor offers, and what it says when it can offer nothing.
 */
class AggregateEditorOffersWhatItCanTest {

    private static Object field(AggregateClassPanel panel, String name) throws Exception {
        var field = AggregateClassPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(panel);
    }

    private static GeneratedProjectModel nobel() throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
    }

    /**
     * A collection may title an aggregate. It was excluded from the title checkboxes,
     * which contradicted the mechanism they fed: Nobel's own statement class is titled
     * "{laureates} — {category}" from a collection, so an aggregate could not be named
     * by its members — the one thing it has that its sources do not.
     *
     * <p>The checkboxes are gone; a template is written where every kind writes one.
     * What has to stay true is that this editor neither refuses a collection nor
     * rewrites the template it is given, which is what the checkboxes did — they could
     * only ever compose "{a} — {b}" and read one back by substring.
     */
    @Test void aCollectionFieldCanTitleAnAggregate() throws Exception {
        GeneratedProjectModel project = nobel();
        GeneratedClassModel prize = project.findClass("NobelPrize");
        prize.canonical().displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE);
        prize.canonical().displayNameTemplate("{laureatesWithMotivation} · {category}");

        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(prize);
        panel.applyEdits();

        assertEquals("{laureatesWithMotivation} · {category}",
                prize.canonical().displayNameTemplate(),
                "the members field titles the aggregate, and its separator survives");
    }

    /**
     * There is no dead end left to explain. Choosing a class this aggregate has no
     * field for used to empty the members chooser, and the tooltip had to say why; the
     * aggregated list offers the field it WOULD create, named after the class, because
     * an aggregate invents no fields by hand — Add field is refused on one.
     */
    @Test void aClassWithNowhereToGoStillOffersTheFieldItWouldCreate() throws Exception {
        GeneratedProjectModel project = nobel();
        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(project.findClass("NobelPrize"));

        JComboBox<?> sourceClass = (JComboBox<?>) field(panel, "sourceClass");
        sourceClass.setSelectedItem("Person");

        OrderedChoiceList<?> aggregated =
                (OrderedChoiceList<?>) field(panel, "aggregated");
        assertTrue(offered(aggregated).contains("person"),
                "the records would go into a field named after the class: "
                        + offered(aggregated));
    }

    /** What the control offers, read the way the reader sees it. */
    private static java.util.List<String> offered(OrderedChoiceList<?> list)
            throws Exception {
        var field = OrderedChoiceList.class.getDeclaredField("available");
        field.setAccessible(true);
        JComboBox<?> available = (JComboBox<?>) field.get(list);
        java.util.List<String> items = new java.util.ArrayList<>();
        for (int i = 0; i < available.getItemCount(); i++) {
            items.add(String.valueOf(available.getItemAt(i)));
        }
        return items;
    }
}
