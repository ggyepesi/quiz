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
                        problem.message().contains("at least one end of the triple must "
                                + "be bounded")),
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


    /**
     * The subject rule is the SIBLING of the bounding rule, not an exception to it.
     * A model states the shape of a triple and never acquires, so where the subject
     * lands is a question only a domain has to answer — and a domain must answer it,
     * because otherwise the subject has nowhere to go and reification would have to
     * invent a field name again.
     */
    @Test void wherTheSubjectGoesIsADomainsProblemJustAsBoundingIs() {
        var asModel = GeneratedProjectModelValidator.validate(
                discoveringStatementClass(GeneratedProjectModel.ProjectKind.MODEL));
        assertTrue(asModel.errors().stream().noneMatch(problem ->
                        problem.message().contains("expose its subject")),
                "a model may declare a triple with neither leg settled: " + asModel.format());

        var asDomain = GeneratedProjectModelValidator.validate(
                discoveringStatementClass(GeneratedProjectModel.ProjectKind.DOMAIN));
        assertTrue(asDomain.errors().stream().anyMatch(problem ->
                        problem.message().contains("expose its subject")),
                "the domain that generates it must say where the subject goes: "
                        + asDomain.format());
    }

    /**
     * Bounding the SUBJECT is as good as bounding the object. Each pins one side of the
     * join, which is what stops an all-of-Wikidata scan (R16). The old rule named the
     * object end because it was the only end that could be bounded — a missing
     * capability wearing a rule's clothes.
     */
    @Test void aBoundedSubjectSatisfiesDiscoveryJustAsBoundedObjectsDo() {
        GeneratedProjectModel project = discoveringStatementClass(
                GeneratedProjectModel.ProjectKind.DOMAIN);
        var award = project.findClass("Award");
        award.statementSource().subjectBound(
                EntityBound.relation("P31", java.util.List.of("Q5"), false));

        var result = GeneratedProjectModelValidator.validate(project);

        assertTrue(result.errors().stream().noneMatch(problem ->
                        problem.message().contains("must be bounded")),
                "a bounded subject pins the join: " + result.format());
    }
}
