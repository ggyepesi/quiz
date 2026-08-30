package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A kind rule set is a partition of the values the pool actually carries, so what NOTHING
 * claims is the number worth seeing — before a regeneration reports it as "M of unknown
 * kind" and after new data adds a value nobody mapped.
 */
class EntityKindCoverageTest {

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass().className("Nomination");
        VocabularySelection types = new VocabularySelection("NomineeType");
        types.valueQids(List.of("Q5", "Q11424", "Q482994", "Q3624078"));
        model.addSelection(types);
        VocabularySelection empty = new VocabularySelection("WorkGenre");
        model.addSelection(empty);
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        GeneratedFieldModel type = nominee.addField(
                "type", FieldType.ENTITY, FieldCardinality.SINGLE);
        type.entityClassName("NomineeType");
        type.mapping().propertyPid("P31");
        model.addClass(nominee);
        return model;
    }

    private static final Map<String, String> LABELS = Map.of(
            "Q5", "human", "Q11424", "film", "Q482994", "album",
            "Q3624078", "sovereign state");

    @Test void onlyVocabulariesWithValuesAreOfferedToPickFrom() {
        assertEquals(List.of("NomineeType"),
                EntityKindCoverage.vocabularies(model(), "P31"));
        assertTrue(EntityKindCoverage.vocabularies(model(), "P136").isEmpty());
    }

    @Test void whatNoKindClaimsIsVisibleBeforeAnythingRuns() {
        GeneratedProjectModel model = model();
        List<EntityKindRule> rules = List.of(
                new EntityKindRule("Person", List.of("Q5")),
                new EntityKindRule("Film", List.of("Q11424")));

        List<EntityKindCoverage.Member> members = EntityKindCoverage.members(
                model, "NomineeType", "P31", rules, LABELS::get);

        assertEquals(4, members.size());
        assertEquals(2, EntityKindCoverage.unmapped(members), "album and sovereign state");
        assertEquals(List.of("Person"), members.get(0).kinds());
        assertEquals("film", members.get(1).label());
        assertFalse(members.get(3).mapped());
    }

    /** Classification applies EVERY matching rule, so a shared value shows every claim. */
    @Test void aValueClaimedByTwoKindsReportsBoth() {
        List<EntityKindRule> rules = List.of(
                new EntityKindRule("Film", List.of("Q11424")),
                new EntityKindRule("Work", List.of("Q11424", "Q482994")));

        List<EntityKindCoverage.Member> members = EntityKindCoverage.members(
                model(), "NomineeType", "P31", rules, LABELS::get);

        assertEquals(List.of("Film", "Work"), members.get(1).kinds());
        assertTrue(members.get(2).mapped(), "album is claimed by Work");
    }

    @Test void aValueWithoutALabelStillReadsAsItsQid() {
        List<EntityKindCoverage.Member> members = EntityKindCoverage.members(
                model(), "NomineeType", "P31", List.of(), qid -> null);

        assertEquals("Q5", members.get(0).label());
    }

    @Test void anUnknownVocabularyIsEmptyRatherThanAFailure() {
        assertTrue(EntityKindCoverage.members(
                model(), "NoSuchVocabulary", "P31", List.of(), LABELS::get).isEmpty());
    }

    @Test void claimsFromAnotherEvidencePropertyDoNotCoverThisVocabulary() {
        EntityKindRule genreRule = new EntityKindRule("GenreKind", List.of("Q5"));
        genreRule.propertyPid("P136");

        List<EntityKindCoverage.Member> members = EntityKindCoverage.members(
                model(), "NomineeType", "P31", List.of(genreRule), LABELS::get);

        assertFalse(members.getFirst().mapped());
    }
}
