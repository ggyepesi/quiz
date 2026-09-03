package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bound the modeller sets must still be there after a save and a load. */
class SubjectBoundPersistenceTest {

    @Test void aSubjectBoundSurvivesTheStore() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("History");
        // A model may carry a bound as a default without settling the triple's legs.
        project.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource("P39");
        source.subjectBound(EntityBound.relation("P31", List.of("Q5"), true));
        holding.statementSource(source);
        project.addClass(holding);
        project.rootClass(holding);

        File file = File.createTempFile("model-subject-bound", ".json");
        file.deleteOnExit();
        new GeneratedProjectModelStore().save(project, file);
        GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(file);

        EntityBound bound =
                loaded.findClass("OfficeHolding").statementSource().subjectBound();
        assertEquals(EntityBound.Kind.RELATION, bound.kind());
        assertEquals("P31", bound.relationPid());
        assertEquals(List.of("Q5"), bound.qids());
        assertTrue(bound.includeDescendants(), "P279 closure is part of the bound");
    }

    @Test void anUnboundedSubjectIsTheDefaultAndNeedsNoStoredValue() {
        assertEquals(EntityBound.Kind.UNBOUNDED,
                new StatementClassSource("P39").subjectBound().kind());
    }
}
