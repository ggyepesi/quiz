package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing "Owned component" has to leave the model SAYING so. The kind is read back
 * from a field on another class, so a class that had only been shown this editor was
 * still UNCONFIGURED — the next refresh reset the kind box to "Source class" and the
 * tree node read "Unconfigured".
 */
class OwnedComponentSourcePanelTest {

    @Test void declaringTheSiteMakesTheClassReadAsAnOwnedComponent() {
        GeneratedProjectModel project = project();
        GeneratedClassModel name = project.findClass("Name");
        OwnedComponentSourcePanel panel = new OwnedComponentSourcePanel(project);
        panel.edit(name);

        assertEquals(MembershipPattern.UNCONFIGURED,
                MembershipPattern.of(name, project), "nothing declared yet");
        assertTrue(panel.declareSite(false), "an owner class is available");

        assertEquals(MembershipPattern.OWNED_COMPONENT,
                MembershipPattern.of(name, project));
        assertEquals("Person",
                MembershipPattern.ownedBy(name, project).getFirst().ownerClass());
        assertTrue(MembershipPattern.describe(name, project).startsWith("Owned by Person."),
                MembershipPattern.describe(name, project));
    }

    /** Re-pointing the owner MOVES the site; two owners would produce two components. */
    @Test void changingTheOwnerLeavesOneSite() {
        GeneratedProjectModel project = project();
        project.addClass(new GeneratedClassModel("Organisation"));
        GeneratedClassModel name = project.findClass("Name");
        OwnedComponentSourcePanel panel = new OwnedComponentSourcePanel(project);
        panel.edit(name);
        panel.declareSite(false);

        panel.selectOwner("Organisation");
        panel.declareSite(false);

        assertEquals(1, MembershipPattern.ownedBy(name, project).size(),
                "the previous owner's field no longer produces one: "
                        + MembershipPattern.ownedBy(name, project));
        assertEquals("Organisation",
                MembershipPattern.ownedBy(name, project).getFirst().ownerClass());
    }

    /** With nothing to own it, the choice cannot be recorded — the caller reverts. */
    @Test void aLoneClassCannotBecomeAnOwnedComponent() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel only = new GeneratedClassModel("Name");
        project.rootClass(only);
        OwnedComponentSourcePanel panel = new OwnedComponentSourcePanel(project);
        panel.edit(only);

        assertFalse(panel.declareSite(false));
        assertEquals(MembershipPattern.UNCONFIGURED, MembershipPattern.of(only, project));
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        project.rootClass(person);
        project.addClass(new GeneratedClassModel("Name"));
        return project;
    }
}
