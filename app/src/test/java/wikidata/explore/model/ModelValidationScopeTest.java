package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model is configuration and never acquires, so a rule that exists to bound an
 * acquisition has nothing to bound in one. A statement class that discovers its subjects
 * needs a bounded value domain or its membership scan is unbounded — true of the domain
 * that gives it a population, and meaningless in the model that declares its shape.
 *
 * <p>Everything structural still applies to both: a model whose class references do not
 * resolve is wrong wherever it is used.
 */
class ModelValidationScopeTest {

    private static GeneratedProjectModel discoveringStatementClass(
            GeneratedProjectModel.ProjectKind kind) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.projectKind(kind);
        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.statementSource(new StatementClassSource("P166"));
        project.rootClass(award);
        return project;
    }

    @Test void aModelMayDeclareAClassItCannotIndependentlyGenerate() {
        var result = GeneratedProjectModelValidator.validate(
                discoveringStatementClass(GeneratedProjectModel.ProjectKind.MODEL));

        assertTrue(result.valid(), result.format());
        assertTrue(result.errors().isEmpty(),
                "bounding an acquisition is not a model's problem: " + result.format());
    }

    @Test void theDomainThatGivesItAPopulationMustStillBoundIt() {
        var result = GeneratedProjectModelValidator.validate(
                discoveringStatementClass(GeneratedProjectModel.ProjectKind.DOMAIN));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(problem ->
                        problem.message().contains("bounded value domain")),
                result.format());
    }

    @Test void aStructuralErrorIsAnErrorInBothKinds() {
        for (GeneratedProjectModel.ProjectKind kind
                : GeneratedProjectModel.ProjectKind.values()) {
            GeneratedProjectModel project = new GeneratedProjectModel();
            project.projectKind(kind);
            project.rootClass(new GeneratedClassModel("Person"));
            project.addClass(new GeneratedClassModel("Person"));

            var result = GeneratedProjectModelValidator.validate(project);

            assertFalse(result.valid(),
                    kind + ": two classes of one name are wrong wherever they are used");
        }
    }

}
