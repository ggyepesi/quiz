package quiz.curation;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #102: opening Identities… on the {@code Nomination} class offered to resolve all
 * 15,161 nominations. A nomination is a reified statement — its id is a statement id,
 * not a QID — so a "is it a QID yet?" test read every one as unresolved and would have
 * searched Wikidata by a display name borrowed from the entity the statement is about.
 */
class IdentitySubjectsTest {

    private static WikidataDynamicObject entity(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    /** A reified nomination as generation actually builds it: the Wikidata statement
     *  GUID as id, and a display name borrowed from the entity it is about. */
    private static WikidataDynamicObject nomination(String statementId, String borrowedName) {
        WikidataDynamicObject o = new WikidataDynamicObject(statementId, borrowedName);
        o.type("Nomination");
        return o;
    }

    @Test void reifiedStatementsAreNeverIdentitySubjects() {
        List<Viewable> members = List.of(
                nomination("Q72717$67ADCA97-2FF9-43AD-A4DC-0349086680AC", "Hong Chau"),
                nomination("Q105883400$3f0b1c2d-aaaa-bbbb-cccc-ddddeeeeffff", "The Whale"));

        IdentitySubjects split = IdentitySubjects.of(members);

        assertEquals(2, split.statements().size());
        assertTrue(split.resolvable().isEmpty(),
                "a statement is already anchored by its statement id");
        assertTrue(split.hasNothingToResolve());
    }

    @Test void theMistake_aQidTestAloneWouldOfferEveryNomination() {
        // This is what produced the 15,161: the id is not a QID, so "not identified yet".
        String statementId = "Q72717$67ADCA97-2FF9-43AD-A4DC-0349086680AC";
        assertFalse(WikidataIds.isQid(statementId),
                "the id is not a QID — which is exactly why a QID test mis-read it");
        assertTrue(WikidataIds.isStatementId(statementId),
                "but it IS a statement id, and that is the question worth asking");
    }

    @Test void aStampedEntityIsResolvable() {
        IdentitySubjects split = IdentitySubjects.of(
                List.of(entity("Q38195662", "Hong Chau", "Person")));

        assertEquals(1, split.resolvable().size());
        assertFalse(split.hasNothingToResolve());
    }

    @Test void anUnstampedInstanceHasNoTypeToKeyALinkUnder() {
        WikidataDynamicObject unstamped =
                new WikidataDynamicObject("Q999", "Something");   // never stamped

        IdentitySubjects split = IdentitySubjects.of(List.of(unstamped));

        assertEquals(1, split.untyped().size());
        assertTrue(split.resolvable().isEmpty());
    }

    @Test void aMixedScopeSplitsThreeWaysAndKeepsOrder() {
        WikidataDynamicObject first = entity("Q1", "First", "Person");
        WikidataDynamicObject second = entity("Q2", "Second", "Person");

        IdentitySubjects split = IdentitySubjects.of(List.of(
                first,
                nomination("Q3$aaaa-bbbb", "Third"),
                new WikidataDynamicObject("Q4", "Fourth"),
                second));

        assertEquals(List.of(first, second), split.resolvable());
        assertEquals(1, split.statements().size());
        assertEquals(1, split.untyped().size());
    }

    @Test void theSummarySaysWhatWasExcludedAndWhy() {
        IdentitySubjects split = IdentitySubjects.of(List.of(
                nomination("Q3$aaaa-bbbb", "Third"),
                new WikidataDynamicObject("Q4", "Fourth")));

        assertTrue(split.excludedSummary().contains("1 statement(s)"), split.excludedSummary());
        assertTrue(split.excludedSummary().contains("1 untyped"), split.excludedSummary());
    }

    @Test void nullsAndAnEmptyScopeAreNotAnError() {
        assertTrue(IdentitySubjects.of(null).hasNothingToResolve());
        assertTrue(IdentitySubjects.of(List.of()).hasNothingToResolve());
    }
}
