package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.Selection;
import wikidata.explore.model.StatementClassSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 4a: a statement class with NO source class discovers its subjects — the
 * reify is configured to discover, sources on an internal load type, and takes its
 * (bounding) value domain from the referenced VOCABULARY. Editable + compiled agree.
 */
class SourceClasslessReifyTest {

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();

        Selection vocab = new Selection("OscarCategories", Selection.Kind.VOCABULARY);
        vocab.valueQids(List.of("Q102427", "Q106301"));
        project.addSelection(vocab);

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        StatementClassSource ss = new StatementClassSource("P1411");   // NO source class
        ss.valueSelectionName("OscarCategories");                      // bounds discovery
        nom.statementSource(ss);
        nom.instanceMapping().propertyPid("P1411");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P1411");
        project.addClass(nom);
        return project;
    }

    @Test void editablePathDiscovers() {
        GeneratedProjectModel project = project();
        GeneratedClassModel nom = project.classes().stream()
                .filter(c -> c.className().equals("Nomination")).findFirst().orElseThrow();

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, project);
        assertNotNull(r, "a source-class-less reify still derives");
        assertTrue(r.load().discoverSubjects(), "no source class => discover subjects");
        assertTrue(r.reify().sourceType().startsWith("__subject_"),
                "subjects source on an internal load type, not a class");
        assertEquals(List.of("Q102427", "Q106301"), r.load().valueQids(),
                "value domain (bounding the discovery) comes from the vocabulary");
    }

    @Test void compiledPathMatches() {
        CompiledProjectModel compiled = ProjectModelCompiler.compile(project());
        CompiledClass nom = compiled.findClass("Nomination").orElseThrow();

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, compiled);
        assertNotNull(r);
        assertTrue(r.load().discoverSubjects());
        assertEquals("__subject_Nomination", r.reify().sourceType());
        assertEquals(List.of("Q102427", "Q106301"), r.load().valueQids());
    }
}
