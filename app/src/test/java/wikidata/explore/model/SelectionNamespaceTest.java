package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field's target names a class OR a selection in ONE namespace, and the class wins it —
 * ClassImportPlan and the validator both ask findClass before findSelection. Editing a
 * selection must read that namespace the same way, or renaming the vocabulary "Prize"
 * silently repoints every field that meant the CLASS Prize.
 */
class SelectionNamespaceTest {

    private static GeneratedProjectModel modelWithBoth() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel prizeClass = new GeneratedClassModel("Prize");
        GeneratedClassModel award = new GeneratedClassModel("Award");
        GeneratedFieldModel prize = new GeneratedFieldModel();
        prize.name("prize");
        prize.entityClassName("Prize");
        award.fields().add(prize);
        project.addClass(prizeClass);
        project.addClass(award);
        project.addSelection(new VocabularySelection("Prize"));
        return project;
    }

    @Test void aFieldNamingAClassIsNotAReferenceToTheSelectionOfThatName() {
        GeneratedProjectModel project = modelWithBoth();

        assertFalse(project.selectionReferenced("Prize"),
                "Award.prize resolves to the CLASS Prize, so the vocabulary is unreferenced");
        assertTrue(project.removeSelection("Prize"),
                "an unreferenced vocabulary can be deleted");
    }

    @Test void renamingASelectionLeavesFieldsThatMeantTheClassAlone() {
        GeneratedProjectModel project = modelWithBoth();

        assertTrue(project.renameSelection("Prize", "PrizeKind"));

        assertEquals("Prize", project.findClass("Award").fields().getFirst().entityClassName(),
                "the field still targets the class it always meant");
        assertEquals("PrizeKind", project.findSelection("PrizeKind").name());
    }

    @Test void aSelectionCannotBeRenamedOntoAClassNameThatWouldShadowIt() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(new GeneratedClassModel("Prize"));
        project.addSelection(new VocabularySelection("PrizeKind"));

        assertFalse(project.renameSelection("PrizeKind", "Prize"),
                "the class would win the name and the selection become unreachable");
        assertEquals("PrizeKind", project.findSelection("PrizeKind").name());
    }

    @Test void withNoClassOfThatNameAFieldTargetDoesReferenceTheSelection() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel award = new GeneratedClassModel("Award");
        GeneratedFieldModel prize = new GeneratedFieldModel();
        prize.name("prize");
        prize.entityClassName("Prize");
        award.fields().add(prize);
        project.addClass(award);
        project.addSelection(new VocabularySelection("Prize"));

        assertTrue(project.selectionReferenced("Prize"));
        assertFalse(project.removeSelection("Prize"), "a referenced vocabulary is kept");
        assertTrue(project.renameSelection("Prize", "PrizeKind"));
        assertEquals("PrizeKind", award.fields().getFirst().entityClassName(),
                "the reference follows the rename");
    }
}
