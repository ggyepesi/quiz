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

    @Test void exactMatchIsNullWhenNoLabelEqualsTheName() {
        InstanceIdentity x = new InstanceIdentity("State", "x", "Zubrowka", "", List.of(
                new IdentityMatch("Q1", "Poland", ""),
                new IdentityMatch("Q2", "Slovakia", "")));

        assertNull(x.exactMatch());
    }
}
