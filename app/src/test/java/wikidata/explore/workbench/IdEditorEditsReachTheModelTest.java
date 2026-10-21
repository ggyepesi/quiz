package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Add and Remove on an id editor are edits: the class they change is applied (#404). */
class IdEditorEditsReachTheModelTest {

    @Test void removingAnObjectQidIsApplied() {
        GeneratedClassModel position = position();
        ClassSourcePanel panel = panel(position);
        WikidataIdListEditor qids = listShowing(panel, "Q6");
        qids.idList().setSelectedValue("Q6", false);
        qids.removeButton().doClick();

        panel.applyEdits();

        assertEquals(List.of("Q5"), position.membership().qids());
    }

    @Test void addingAnObjectQidIsApplied() {
        GeneratedClassModel position = position();
        ClassSourcePanel panel = panel(position);
        WikidataIdListEditor qids = listShowing(panel, "Q6");
        qids.draftField().setText("Q7");
        qids.addButton().doClick();

        panel.applyEdits();

        assertEquals(List.of("Q5", "Q6", "Q7"), position.membership().qids());
    }

    private static WikidataIdListEditor listShowing(Container root, String id) {
        WikidataIdListEditor found = find(root, id);
        if (found == null) throw new AssertionError("no id list shows " + id);
        return found;
    }

    private static GeneratedClassModel position() {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation("P31", List.of("Q5", "Q6"), false));
        return position;
    }

    private static ClassSourcePanel panel(GeneratedClassModel clazz) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(clazz);
        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(clazz);
        return panel;
    }

    /** The id list showing {@code id}; asserted present by {@link #listShowing}. */
    private static WikidataIdListEditor find(Container root, String id) {
        for (Component child : root.getComponents()) {
            if (child instanceof WikidataIdListEditor list && list.shownIds().contains(id)) {
                return list;
            }
            if (child instanceof Container nested) {
                WikidataIdListEditor found = find(nested, id);
                if (found != null) return found;
            }
        }
        return null;
    }
}
