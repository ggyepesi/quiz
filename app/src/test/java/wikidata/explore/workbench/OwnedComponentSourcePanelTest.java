package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The class declares only its owned KIND; producing sites belong to fields. */
class OwnedComponentSourcePanelTest {

    @Test void ownedClassRemainsConfiguredWithoutInventingAnOwner() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.addClass(name);
        OwnedClassPanel panel = new OwnedClassPanel(project);

        panel.edit(name);
        panel.applyEdits();

        assertTrue(name.ownedClass());
        assertEquals(MembershipPattern.OWNED_COMPONENT,
                MembershipPattern.of(name, project));
        assertTrue(MembershipPattern.ownedBy(name, project).isEmpty(),
                "the owned-class editor must not create an owner field");
    }
}
