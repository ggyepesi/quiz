package wikidata.explore.transform;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Slice 3 (production → Selection): a reify that names a VOCABULARY Selection
 * takes its value domain from that vocabulary, overriding the filter otherwise
 * inherited from a source class. Same result on the editable and compiled paths.
 */
class SelectionValueDomainTest {

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();

        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.instanceMapping().propertyPid("P1411");
        src.instanceMapping().additionalTypeQids().add("Q102427");   // class-derived filter
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
                .mapping().propertyPid("P1411");
        // Declared, not implied: reification used to invent a "source" field for the
        // subject, so fixtures inherited one they never wrote down. A statement now has
        // to say where its subject goes, and this is the field it was always using.
        nom.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        project.addClass(nom);
        return project;
    }

    @Test void editablePathUsesTheVocabulary() {
        GeneratedProjectModel project = project();
        GeneratedClassModel nom = project.classes().stream()
                .filter(c -> c.className().equals("Nomination")).findFirst().orElseThrow();

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, project);
        assertEquals(List.of("Q900", "Q901"), r.load().valueQids(),
                "the vocabulary overrides the class-derived value filter");
    }

    @Test void compiledPathMatches() {
        CompiledProjectModel compiled = ProjectModelCompiler.compile(project());
        CompiledClass nom = compiled.findClass("Nomination").orElseThrow();

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, compiled);
        assertEquals(List.of("Q900", "Q901"), r.load().valueQids(),
                "compiled path matches the editable path");
    }
}
