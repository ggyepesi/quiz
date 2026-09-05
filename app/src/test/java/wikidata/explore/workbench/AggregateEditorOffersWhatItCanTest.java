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
