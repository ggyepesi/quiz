package wikidata.explore.transform;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.VocabularySelection;
import wikidata.explore.model.PopulationSelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 3 (production → Selection): a reify that names a VOCABULARY Selection
 * takes its value domain from that vocabulary, overriding the filter otherwise
 * inherited from a source class. Same result on the editable and compiled paths.
 */
class SelectionValueDomainTest {

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();

        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.membership(EntityBound.relation("P1411", List.of("Q102427"), false));   // class-derived filter
        project.addClass(src);

        VocabularySelection vocab = new VocabularySelection("OscarCategories");
        vocab.valueQids(List.of("Q900", "Q901"));                    // DIFFERENT set
        project.addSelection(vocab);

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        StatementClassSource ss = new StatementClassSource("OscarNominations", "P1411");
        ss.valueSelectionName("OscarCategories");
        nom.statementSource(ss);
        nom.instanceMapping().propertyPid("P1411");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_OBJECT);
        // Declared, not implied: reification used to invent a "source" field for the
        // subject, so fixtures inherited one they never wrote down. A statement now has
        // to say where its subject goes, and this is the field it was always using.
        nom.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        nom.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(nom));
        project.addClass(nom);
        return project;
    }

    @Test void editablePathUsesTheVocabulary() {
        GeneratedProjectModel project = project();
        GeneratedClassModel nom = project.classes().stream()
                .filter(c -> c.className().equals("Nomination")).findFirst().orElseThrow();

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, project);
        assertEquals(List.of("Q900", "Q901"), r.load().objectBound().qids(),
                "the vocabulary overrides the class-derived value filter");
    }

    @Test void compiledPathMatches() {
        CompiledProjectModel compiled = ProjectModelCompiler.compile(project());
        CompiledClass nom = compiled.findClass("Nomination").orElseThrow();

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, compiled);
        assertEquals(List.of("Q900", "Q901"), r.load().objectBound().qids(),
                "compiled path matches the editable path");
    }

    @Test void aPopulationSelectionIsTheStatementValueDomain() {
        GeneratedProjectModel project = project();
        PopulationSelection positions = new PopulationSelection("PositionPopulation");
        positions.className("Position");
        positions.instanceQids(List.of("Q11696", "Q12548"));
        project.addSelection(positions);
        GeneratedClassModel nomination = project.findClass("Nomination");
        nomination.statementSource().objectBound(
                EntityBound.vocabulary("PositionPopulation"));

        assertEquals(List.of("Q11696", "Q12548"),
                ModelStatementReifications.deriveOne(nomination, project)
                        .load().objectBound().qids(),
                "the authored population supplies the exact allowed statement objects");

        CompiledProjectModel compiledProject = ProjectModelCompiler.compile(project);
        CompiledClass compiled = compiledProject.findClass("Nomination").orElseThrow();
        assertEquals(List.of("Q11696", "Q12548"),
                ModelStatementReifications.deriveOne(
                        compiled, compiledProject)
                        .load().objectBound().qids(),
                "the compiled acquisition path resolves the same population");
    }

    /**
     * The widening this construct must never do. An end bounded by a selection asks for
     * exactly the entities that selection names; if it names none, or nothing answers to
     * the name at all, the request cannot be honoured — and honouring it as "no bound"
     * would run the widest query the model can express, against all of Wikidata. The
     * model is refused, by a message naming the selection that let it down.
     */
    @Test void aSelectionSupplyingNothingIsRefusedRatherThanWidened() {
        for (String scenario : List.of("empty", "missing")) {
            GeneratedProjectModel project = project();
            if (scenario.equals("empty")) {
                PopulationSelection empty = new PopulationSelection("PositionPopulation");
                empty.className("OscarNominations");
                empty.instanceQids(List.of());
                project.addSelection(empty);
            }
            GeneratedClassModel nomination = project.classes().stream()
                    .filter(c -> c.className().equals("Nomination")).findFirst().orElseThrow();
            nomination.statementSource().objectBound(
                    EntityBound.vocabulary("PositionPopulation"));

            var problems = wikidata.explore.model.GeneratedProjectModelValidator
                    .validate(project);

            assertFalse(problems.valid(), scenario + ": the model cannot be generated");
            assertTrue(problems.errors().stream().anyMatch(
                            problem -> problem.message().contains("PositionPopulation")),
                    scenario + ": the refusal names the selection — " + problems.errors());
        }
    }

    /** The backstop under the validator: resolution never turns a reference into ANY. */
    @Test void anUnresolvableSelectionStaysAReferenceInsteadOfBecomingUnbounded() {
        EntityBound bound = EntityBound.vocabulary("PositionPopulation");

        assertEquals(bound, bound.resolved(List.of(), ""),
                "a selection supplying nothing leaves the bound the reference it was");
    }
}
