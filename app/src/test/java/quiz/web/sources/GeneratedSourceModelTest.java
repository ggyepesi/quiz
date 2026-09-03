package quiz.web.sources;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.VocabularySelection;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Model-derived structural fields + nested vocabulary paths used by validation. */
class GeneratedSourceModelTest {

    private static GeneratedProjectModel oscarsLikeModel() {
        GeneratedProjectModel m = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("P1411"));   // a reify class
        nom.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("OscarCategories");   // DIRECT vocab, not nested
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        nom.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(nom));
        m.addClass(nom);

        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        nominee.addField("type", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("NomineeType");
        m.addClass(nominee);

        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        forWork.addField("genre", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("WorkGenre");
        m.addClass(forWork);

        m.addSelection(new VocabularySelection("OscarCategories"));
        m.addSelection(new VocabularySelection("NomineeType"));
        m.addSelection(new VocabularySelection("WorkGenre"));
        m.rootClass(nom);
        return m;
    }

    @Test void onlyExplicitReificationSourceIsStructural() {
        GeneratedProjectModel m = oscarsLikeModel();
        assertEquals(Set.of("source"),
                GeneratedSource.structuralFor("Nomination", m));
        assertEquals(Set.of(), GeneratedSource.structuralFor("Nominee", m));
        assertEquals(Set.of(), GeneratedSource.structuralFor("ForWork", m));
    }

    @Test void nestedVocabularyCoveragePathsFromReferencedClassFields() {
        GeneratedProjectModel m = oscarsLikeModel();
        List<String[]> paths = GeneratedSource.nestedVocabularyPaths("Nomination", m);
        List<List<String>> asList = paths.stream()
                .map(p -> List.of(p[0], p[1])).toList();
        // nominee.type and forWork.genre (in Nomination's field order); category is a
        // direct field, so normal top-level coverage already includes it.
        assertEquals(List.of(
                List.of("nominee.type", "type"),
                List.of("forWork.genre", "genre")), asList);
    }
}
