package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldExpectation;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MissingQualifierPolicy;
import wikidata.explore.model.StatementClassSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #95, the second half: after the subject-fallback became field-scoped, the incomplete
 * nominations had to go somewhere. 14 of the 16 remaining missing-year Nominations were
 * fully self-referential — a bare {@code P1411} with every qualifier absent, an award
 * that really belongs to a person who holds the correct atom.
 *
 * <p>The issue asked to filter on P805-ABSENCE, not on edition-resolution, and the
 * distinction matters: {@code A Time for Burning} does carry a P805, pointing at an
 * Edition entity that happens to be bare and undated. It is a real nomination with poor
 * reference data, and it has to survive.
 *
 * <p>No new mechanism: {@link MissingQualifierPolicy#MISSING} keeps an absent qualifier
 * absent instead of collapsing it onto the subject, and the field expectation (#96) then
 * decides whether that is reported or dropped. This test is the composition, because
 * each half passing alone is what let the phantom through.
 */
class IncompleteNominationQuarantineTest {

    private static WikidataDynamicObject entity(String id, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, name);
        o.type(type);
        return o;
    }

    private static GeneratedProjectModel nominationsExpecting(FieldExpectation level) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("OscarNominations", "P1411"));

        GeneratedFieldModel edition =
                nomination.addField("edition", FieldType.ENTITY, FieldCardinality.SINGLE);
        edition.mapping().qualifierPid("P805");
        // The correctness half: an absent ceremony must stay absent. With the legacy
        // subject fallback it became the film itself, so "edition" was never missing and
        // no expectation could ever have seen the gap.
        edition.mapping().missingQualifierPolicy(MissingQualifierPolicy.MISSING);
        edition.expectation(level);

        project.addClass(nomination);
        return project;
    }

    /** The three shapes #95 distinguishes, as they stand after reification. */
    private static List<WikidataDynamicObject> nominations() {
        WikidataDynamicObject realCeremony =
                entity("Q66707607", "95th Academy Awards", "Edition");
        WikidataDynamicObject bareCeremony =
                entity("Q1968Ceremony", "1968 Academy Awards", "Edition");  // no date

        WikidataDynamicObject hongChau = entity("Q38195662$real", "Hong Chau", "Nomination");
        hongChau.put("edition", realCeremony);

        WikidataDynamicObject aTimeForBurning =
                entity("Q4661973$real", "A Time for Burning", "Nomination");
        aTimeForBurning.put("edition", bareCeremony);   // P805 present, Edition undated

        WikidataDynamicObject whale =
                entity("Q105883400$whale", "The Whale", "Nomination");   // bare P1411

        return new ArrayList<>(List.of(hongChau, aTimeForBurning, whale));
    }

    @Test void expectedReportsTheGapWithoutDeletingAnything() {
        List<WikidataDynamicObject> pool = nominations();

        FieldExpectations.Result result = FieldExpectations.apply(
                nominationsExpecting(FieldExpectation.EXPECTED), pool, null);

        assertEquals(3, pool.size(), "EXPECTED is a check, not an action");
        assertTrue(result.dropped().isEmpty());
        FieldExpectations.FieldCoverage coverage = result.coverage().get(0);
        assertEquals(3, coverage.total());
        assertEquals(2, coverage.present());
        assertEquals(1, coverage.missing(),
                "the one nomination with no ceremony at all is counted, not guessed at");
    }

    @Test void requiredQuarantinesOnlyTheNominationWithNoCeremonyQualifierAtAll() {
        List<WikidataDynamicObject> pool = nominations();

        FieldExpectations.Result result = FieldExpectations.apply(
                nominationsExpecting(FieldExpectation.REQUIRED), pool, null);

        assertEquals(1, result.dropped().size());
        assertEquals("Q105883400$whale", result.dropped().get(0).getIdentifier());
        assertEquals(List.of("Q38195662$real", "Q4661973$real"),
                pool.stream().map(WikidataDynamicObject::getIdentifier).toList(),
                "A Time for Burning has a P805 — a real nomination with a bare Edition, "
                        + "which is a reference-data problem and not grounds for deletion");
    }

    @Test void theMistake_theSubjectFallbackHidesTheGapCompletely() {
        // With the legacy policy the film's own qid fills edition, so coverage reads
        // 100% and every expectation level becomes a no-op. This is why #95 called the
        // field-scoped fallback the correctness fix and the quarantine only the cleanup.
        List<WikidataDynamicObject> pool = nominations();
        WikidataDynamicObject whale = pool.get(2);
        whale.put("edition", entity("Q105883400", "The Whale", "Film"));

        FieldExpectations.Result result = FieldExpectations.apply(
                nominationsExpecting(FieldExpectation.REQUIRED), pool, null);

        assertTrue(result.dropped().isEmpty());
        assertEquals(0, result.coverage().get(0).missing(),
                "nothing looks missing, and a film is recorded as its own ceremony");
    }
}
