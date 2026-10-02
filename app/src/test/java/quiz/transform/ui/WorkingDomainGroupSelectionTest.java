package quiz.transform.ui;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import quiz.transform.EditableGroup;
import quiz.transform.RelationClosureGroup;
import wikidata.explore.extract.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A group edited in TransformApp is selectable wherever a selection is: a closure
 * boundary, a join, an experiment.
 *
 * <p>Selections reached TransformApp from roles, saved populations and curation sources,
 * but never from its own groups — so a closure could only be bounded by a whole saved
 * population. Bounded by all of PositionWithHoldersPopulation, seeds from three realms
 * each reached the same 9,416 people; a narrower boundary is exactly what a group is.
 */
class WorkingDomainGroupSelectionTest {

    @Test void aGroupIsSelectableByItsPlaceInTheTree() {
        WikidataDynamicObject louis = entity("Q1", "Person");
        WikidataDynamicObject charles = entity("Q2", "Person");
        WikidataDynamicObject outsider = entity("Q3", "Person");
        WorkingDomain domain = new WorkingDomain(
                new SnapshotDomain(List.of(louis, charles, outsider)));
        EditableGroup kings = new EditableGroup("Kings");
        kings.replaceMembers(List.of(louis, charles));
        domain.editableGroupRoot("Person").addGroup(kings);

        assertTrue(domain.selectionNames().contains("Person ▸ Kings"),
                domain.selectionNames().toString());
        assertFalse(domain.selectionNames().contains("Person"),
                "the root is the class itself, not a selection");
        assertEquals(List.of(louis, charles), domain.selectionMembers("Person ▸ Kings"));
    }

    /** The motivating case: a closure over office holdings, bounded by a group of
     *  positions rather than a whole saved population. */
    @Test void aRelationClosureCanBeBoundedByAGroup() {
        WikidataDynamicObject apostolic = entity("Q10", "Position");
        WikidataDynamicObject king = entity("Q11", "Position");
        WikidataDynamicObject foreign = entity("Q12", "Position");
        WikidataDynamicObject louis = entity("Q1", "Person");
        WikidataDynamicObject charles = entity("Q2", "Person");
        WikidataDynamicObject outsider = entity("Q3", "Person");
        WorkingDomain domain = new WorkingDomain(new SnapshotDomain(List.of(
                apostolic, king, foreign, louis, charles, outsider,
                holding("Q1$a", louis, apostolic), holding("Q1$b", louis, king),
                holding("Q2$a", charles, king), holding("Q2$b", charles, foreign),
                holding("Q3$a", outsider, foreign))));
        EditableGroup hungarian = new EditableGroup("Hungarian");
        hungarian.replaceMembers(List.of(apostolic, king));
        domain.editableGroupRoot("Position").addGroup(hungarian);

        RelationClosureGroup closure = new RelationClosureGroup("Apostolic kings",
                "Person", "OfficeHolding", "source", "position",
                "Position ▸ Hungarian", List.of(apostolic));
        closure.reproduce(domain.instancesOf("Person"), domain);

        assertEquals("", closure.problem());
        assertEquals(List.of("Q1", "Q2"), closure.getMembers().stream()
                .map(Viewable::getIdentifier).toList());
    }

    /** A closure may ask for selections while its own tree is being refreshed, and the
     *  selections include that tree. It is read as it stands rather than refreshed again,
     *  so even a closure bounded by itself terminates. */
    @Test void aClosureBoundedByItselfTerminates() {
        WikidataDynamicObject louis = entity("Q1", "Person");
        WorkingDomain domain = new WorkingDomain(new SnapshotDomain(List.of(louis)));
        domain.editableGroupRoot("Person").addGroup(new RelationClosureGroup("Loop",
                "Person", "OfficeHolding", "source", "position",
                "Person ▸ Loop", List.of(louis)));

        assertTrue(domain.selectionNames().contains("Person ▸ Loop"));
    }

    private static WikidataDynamicObject entity(String qid, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.type(type);
        return value;
    }

    private static WikidataDynamicObject holding(String id, Viewable person, Viewable position) {
        WikidataDynamicObject value = entity(id, "OfficeHolding");
        value.put("source", person);
        value.put("position", position);
        return value;
    }
}
