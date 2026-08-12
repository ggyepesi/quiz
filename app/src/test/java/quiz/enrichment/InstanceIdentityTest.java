package quiz.enrichment;

import org.junit.jupiter.api.Test;
import quiz.enrichment.ResolveIdentitiesReviewRequest.IdentityMatch;
import quiz.enrichment.ResolveIdentitiesReviewRequest.InstanceIdentity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The confident pick is the top-ranked exact-label hit, not demoted by homonyms. */
class InstanceIdentityTest {

    @Test void exactMatchPrefersTheTopRankedHitDespiteHomonyms() {
        // "India" search: Q668 (the country) ranks first, but "India" also labels a given
        // name — the old guard bailed to null; ranking now picks Q668.
        InstanceIdentity india = new InstanceIdentity("State", "india", "India", "", List.of(
                new IdentityMatch("Q668", "India", "country in South Asia"),
                new IdentityMatch("Q11703", "India", "given name"),
                new IdentityMatch("Q1471888", "Indication", "")));

        assertEquals("Q668", india.exactMatch().qid());
    }

    /** Measured on the 45 US presidents: this is the one case where an exact label
     *  disagreed with the ranking, and the ranking was right. */
    @Test void anExactLabelBelowTheTopHitDoesNotWin() {
        InstanceIdentity fdr = new InstanceIdentity(
                "President", "fdr", "Franklin D. Roosevelt", "", List.of(
                new IdentityMatch("Q8007", "Franklin Delano Roosevelt",
                        "president of the United States from 1933 to 1945"),
                new IdentityMatch("Q1445234", "Franklin D. Roosevelt", "Paris Métro station")));

        assertNull(fdr.exactMatch());
    }

    @Test void exactMatchIsNullWhenNoLabelEqualsTheName() {
        InstanceIdentity x = new InstanceIdentity("State", "x", "Zubrowka", "", List.of(
                new IdentityMatch("Q1", "Poland", ""),
                new IdentityMatch("Q2", "Slovakia", "")));

        assertNull(x.exactMatch());
    }
}
