package wikidata.explore.transform;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelValidator;
import wikidata.explore.model.FieldProductionKind;
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
        // Declared, not implied: reification used to invent a "source" field for the
        // subject, so every fixture inherited one it never wrote down. A statement now
        // has to say where its subject goes, and this is the field it was always using.
        nom.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
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
        assertEquals(List.of("Q102427", "Q106301"), r.load().objectBound().qids(),
                "value domain (bounding the discovery) comes from the vocabulary");
        assertEquals(r.load().objectBound().qids(), r.load().discoveryValueQids(),
                "an explicit vocabulary both discovers and filters statements");
    }

    @Test void compiledPathMatches() {
        CompiledProjectModel compiled = ProjectModelCompiler.compile(project());
        CompiledClass nom = compiled.findClass("Nomination").orElseThrow();

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, compiled);
        assertNotNull(r);
        assertTrue(r.load().discoverSubjects());
        assertEquals("__subject_Nomination", r.reify().sourceType());
        assertEquals(List.of("Q102427", "Q106301"), r.load().objectBound().qids());
        assertEquals(r.load().objectBound().qids(), r.load().discoveryValueQids());
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
        // Declared, not implied: reification used to invent a "source" field for the
        // subject, so fixtures inherited one they never wrote down. A statement now has
        // to say where its subject goes, and this is the field it was always using.
        holding.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        project.addClass(holding);

        assertFalse(GeneratedProjectModelValidator.validate(project).format()
                        .contains("at least one end of the triple must be bounded"),
                "the seeded value class bounds direct subject discovery");

        var editable = ModelStatementReifications.deriveOne(holding, project);
        assertNotNull(editable);
        assertEquals(List.of(), editable.load().objectBound().qids(),
                "target seeds discover holders but do not filter their other positions");
        assertEquals(List.of("Q6412254"), editable.load().discoveryValueQids());

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);
        var compiledHolding = compiled.findClass("OfficeHolding").orElseThrow();
        var compiledResult =
                ModelStatementReifications.deriveOne(compiledHolding, compiled);
        assertNotNull(compiledResult);
        assertEquals(List.of(), compiledResult.load().objectBound().qids());
        assertEquals(List.of("Q6412254"),
                compiledResult.load().discoveryValueQids());
    }

    // --- what the discovery log calls a value domain ------------------------

    @Test void aDomainThatOnlyFiltersIsNotCalledSeeds() {
        // Every config carries discovery values, because the compatibility
        // constructors set them to the accepted values. Their presence therefore
        // says nothing about whether anything was discovered, and a label that
        // reads them alone calls a pure filter "seeds".
        QualifierLoadConfig filterOnly = new QualifierLoadConfig(
                "T",
                "P1",
                "__S",
                "S",
                "value",
                EntityBound.explicit(java.util.List.of("Q1", "Q2")),
                java.util.List.of());

        assertFalse(filterOnly.discoverSubjects());
        assertEquals("2 allowed values", filterOnly.valueDomainLabel());
    }

    @Test void aDomainThatFindsSubjectsWithoutFilteringThemIsCalledSeeds() {
        QualifierLoadConfig discovery = new QualifierLoadConfig(
                "T",
                "P1",
                "__S",
                "S",
                "value",
                EntityBound.unbounded(),
                java.util.List.of(),
                java.util.List.of("Q6412254"),
                true,
                "");

        assertTrue(discovery.discoversOnly());
        assertEquals("1 discovery seed(s)", discovery.valueDomainLabel());
    }

    @Test void aVocabularyKeepsItsOwnName() {
        // A named selection both discovers and filters; it is neither of the above.
        QualifierLoadConfig vocabulary = new QualifierLoadConfig(
                "T",
                "P1",
                "__S",
                "S",
                "value",
                EntityBound.explicit(java.util.List.of("Q1")),
                java.util.List.of(),
                true,
                "OscarCategories");

        assertFalse(vocabulary.discoversOnly());
        assertEquals("Selection 'OscarCategories'", vocabulary.valueDomainLabel());
    }
}
