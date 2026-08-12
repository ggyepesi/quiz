package wikidata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement id is not an entity id, and the difference decides whether an instance can
 * be identity-resolved at all: an Oscar Nomination carries "Q72717$67ADCA97-…", is already
 * anchored, and has no label a search could match — while its display name belongs to the
 * nominee the statement is about.
 */
class WikidataIdsTest {

    @Test void aStatementIdIsNotAnEntityId() {
        String statement = "Q72717$67ADCA97-2FF9-43AD-A4DC-0349086680AC";

        assertTrue(WikidataIds.isStatementId(statement));
        assertFalse(WikidataIds.isQid(statement));
        assertFalse(WikidataIds.isId(statement));
    }

    @Test void aPlainQidIsNotAStatement() {
        assertFalse(WikidataIds.isStatementId("Q72717"));
        assertFalse(WikidataIds.isStatementId("P31"));
        assertFalse(WikidataIds.isStatementId(null));
        assertFalse(WikidataIds.isStatementId("Q72717$"));
    }

    /** Wikidata writes the GUID in either case. */
    @Test void theGuidCaseDoesNotMatter() {
        assertTrue(WikidataIds.isStatementId("Q42$abc12de3-4567-89ab-cdef-0123456789ab"));
        assertTrue(WikidataIds.isStatementId("Q42$ABC12DE3-4567-89AB-CDEF-0123456789AB"));
    }

    @Test void theSubjectIsTheEntityTheStatementIsAbout() {
        assertEquals("Q72717", WikidataIds.statementSubject(
                "Q72717$67ADCA97-2FF9-43AD-A4DC-0349086680AC"));
        assertNull(WikidataIds.statementSubject("Q72717"));
    }
}
