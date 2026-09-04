package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import java.io.File;
import java.util.Map;

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
     * A collection may title an aggregate. It was excluded, which contradicted the
     * mechanism it feeds: Nobel's own statement class is titled "{laureates} —
     * {category}" from a collection, so an aggregate could not be named by its members
     * — the one thing it has that its sources do not.
     */
    @SuppressWarnings("unchecked")
    @Test void aCollectionFieldCanTitleAnAggregate() throws Exception {
        GeneratedProjectModel project = nobel();
        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(project.findClass("NobelPrize"));

        Map<String, JCheckBox> offered =
                (Map<String, JCheckBox>) field(panel, "displayFields");
        assertTrue(offered.containsKey("laureatesWithMotivation"),
                "the members field is offered as a title component: " + offered.keySet());
    }

    /**
     * And where nothing can be offered, the control says why. An aggregate holds its
     * sources in one of its own list fields, so choosing a class it cannot hold leaves
     * the control empty — which looked like a broken editor rather than a fact.
     */
    @Test void anImpossibleSourceExplainsItselfRatherThanGoingBlank() throws Exception {
        GeneratedProjectModel project = nobel();
        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(project.findClass("NobelPrize"));

        JComboBox<?> sourceClass = (JComboBox<?>) field(panel, "sourceClass");
        sourceClass.setSelectedItem("Person");

        JComboBox<?> members = (JComboBox<?>) field(panel, "membersField");
        assertTrue(members.getToolTipText().contains("no list field of Person"),
                "it says what is missing: " + members.getToolTipText());
    }
}
