package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Producing sites belong to owner fields; the owned schema declares its back-link. */
class OwnedComponentSourcePanelTest {

    @Test void ownedClassDeclaresItsReadOnlyPolymorphicOwnerReference() {
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
                "a back-reference is not a production site");
        var owner = name.fields().stream()
                .filter(field -> field.name().equals("owner"))
                .findFirst().orElseThrow();
        assertTrue(wikidata.explore.model.OwnedClassSemantics
                .isOwnerReferenceField(name, owner));
    }
}
