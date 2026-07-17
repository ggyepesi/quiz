package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionMatcherTest {

    private WikidataDynamicObject record(String subj, String value, String role) {
        WikidataDynamicObject r =
                new WikidataDynamicObject(subj + "-" + value + "-" + role, "rec");
        r.put("subject", new WikidataDynamicObject(subj, "subject"));
        r.put("category", new WikidataDynamicObject(value, "value"));
        r.put("nominee", new WikidataDynamicObject(role, "role"));
        return r;
    }

    @Test
    void matchesOnlyTheCompanionWithSameSubjectValueAndRole() {
        // Oscars example: Scent of a Woman / Best Actor / Al Pacino won.
        WikidataDynamicObject won = record("Q321561", "Q103916", "Q41163");
        WikidataDynamicObject sameCatOtherRole = record("Q321561", "Q103916", "Q999");
        WikidataDynamicObject otherValue = record("Q321561", "Q111", "Q41163");
        WikidataDynamicObject otherSubject = record("Q222", "Q103916", "Q41163");

        Set<List<String>> companions = Set.of(List.of("Q321561", "Q103916", "Q41163"));

        int matched = CompanionMatcher.apply(
                List.of(won, sameCatOtherRole, otherValue, otherSubject),
                companions, "subject", "category", "nominee", "won", null);

        assertEquals(1, matched);
        assertEquals(Boolean.TRUE, won.get("won"));
        assertEquals(Boolean.FALSE, sameCatOtherRole.get("won"));
        assertEquals(Boolean.FALSE, otherValue.get("won"));
        assertEquals(Boolean.FALSE, otherSubject.get("won"));
    }

    @Test
    void keyIsNullWhenAPartIsMissing() {
        WikidataDynamicObject r = new WikidataDynamicObject("x", "rec");
        r.put("subject", new WikidataDynamicObject("Q1", "s"));
        r.put("category", new WikidataDynamicObject("Q2", "v"));
        // no role
        assertEquals(null, CompanionMatcher.key(r, "subject", "category", "nominee"));
    }
}
