package wikidata.explore.transform;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PopulationSelectionsTest {

    /** A saved population is offered by name, holding the loaded instances it names —
     *  and only as instances of a class it accepts: the same QID stamped with an
     *  unrelated class is not retyped into it (directive 9). */
    @Test void aPopulationHoldsTheLoadedInstancesItNamesOfAClassItAccepts() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        GeneratedClassModel subclass = new GeneratedClassModel("Kingship");
        subclass.baseClassName("Position");
        model.addClass(subclass);
        model.addClass(new GeneratedClassModel("Person"));
        PopulationSelection held = new PopulationSelection("Held");
        held.className("Position");
        held.instanceQids(List.of("Q1", "Q2", "Q3"));
        model.addSelection(held);

        WikidataDynamicObject king = stamped("Q1", "Position");
        WikidataDynamicObject kingship = stamped("Q2", "Kingship");
        WikidataDynamicObject samePersonQid = stamped("Q3", "Person");
        WikidataDynamicObject unnamed = stamped("Q4", "Position");

        List<Viewable> members = PopulationSelections.materialize(model,
                List.of(king, kingship, samePersonQid, unnamed)).get("Held");

        assertEquals(List.of(king, kingship), members);
    }

    private static WikidataDynamicObject stamped(String qid, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.type(type);
        return value;
    }
}
