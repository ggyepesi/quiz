package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelValidator;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.VocabularySelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        VocabularySelection vocab = new VocabularySelection("OscarCategories");
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

    @Test void valueEntityClassOwnsTheDirectDiscoveryDomain() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.seedQids().add("Q6412254");
        project.addClass(position);

        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        holding.instanceMapping().propertyPid("P39");
        var value = holding.addField(
                "position", FieldType.ENTITY, FieldCardinality.SINGLE);
        value.entityClassName("Position");
        value.mapping().propertyPid("P39");
        project.addClass(holding);

        assertFalse(GeneratedProjectModelValidator.validate(project).format()
                        .contains("needs a bounded value domain"),
                "the seeded value class bounds direct subject discovery");

        var editable = ModelStatementReifications.deriveOne(holding, project);
        assertNotNull(editable);
        assertEquals(List.of("Q6412254"), editable.load().valueQids());

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);
        var compiledHolding = compiled.findClass("OfficeHolding").orElseThrow();
        var compiledResult =
                ModelStatementReifications.deriveOne(compiledHolding, compiled);
        assertNotNull(compiledResult);
        assertEquals(List.of("Q6412254"), compiledResult.load().valueQids());
    }
}
