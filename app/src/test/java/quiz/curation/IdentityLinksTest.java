package quiz.curation;

import objectview.Viewable;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An identity link outlives the class an instance happened to carry when it was written.
 * Nothing errors when a link stops matching — the instance simply reads as unidentified
 * again — so the keying rule is pinned here rather than left to each reader.
 */
class IdentityLinksTest {

    /** A dynamic instance: stable identity type, most-specific class, role memberships. */
    private record Instance(String id, String stable, String specific, Set<String> classes)
            implements Viewable {
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public String typeName() { return specific; }
        @Override public String identityTypeName() { return stable; }
        @Override public Set<String> directClassNames() { return classes; }
        @Override public objectview.field.FieldSet fields() {
            return objectview.field.FieldSet.of(this);
        }
    }

    private static IdentityLink link(String type, String targetId, String qid) {
        return new IdentityLink(type, targetId, IdentityLinks.WIKIDATA, qid,
                "https://www.wikidata.org/wiki/" + qid, targetId, "wikidata");
    }

    @Test void aNewLinkIsWrittenUnderTheStableIdentityType() {
        Instance usState = new Instance("Texas", "State", "USState", Set.of("USState"));

        assertEquals("State", IdentityLinks.stableType(usState));
    }

    /** The countries sidecar holds 50 links written under USState before this rule. */
    @Test void aLinkWrittenUnderTheSubclassStillMatches() {
        Instance usState = new Instance("Texas", "State", "USState", Set.of("USState"));

        assertTrue(IdentityLinks.matches(link("USState", "Texas", "Q1439"), usState,
                IdentityLinks.WIKIDATA));
        assertTrue(IdentityLinks.matches(link("State", "Texas", "Q1439"), usState,
                IdentityLinks.WIKIDATA));
    }

    /** Multirole: the carrier may be either role, so a link under either identifies it. */
    @Test void aLinkWrittenUnderEitherRoleStillMatches() {
        Instance shared = new Instance("Q204191", "ForWork", "ForWork",
                Set.of("ForWork", "Nominee"));

        assertTrue(IdentityLinks.matches(link("Nominee", "Q204191", "Q204191"), shared,
                IdentityLinks.WIKIDATA));
        assertTrue(IdentityLinks.matches(link("ForWork", "Q204191", "Q204191"), shared,
                IdentityLinks.WIKIDATA));
    }

    /** ⟨type, targetId⟩ still separates two different things that share an id. */
    @Test void anUnrelatedTypeDoesNotMatch() {
        Instance state = new Instance("France", "State", "State", Set.of("State"));

        assertFalse(IdentityLinks.matches(link("ViewableGroup", "France", "Q142"), state,
                IdentityLinks.WIKIDATA));
        assertFalse(IdentityLinks.matches(link("State", "Germany", "Q142"), state,
                IdentityLinks.WIKIDATA));
    }

    @Test void theSourceKindIsPartOfTheQuestion() {
        Instance state = new Instance("France", "State", "State", Set.of("State"));
        IdentityLink dbpedia = new IdentityLink("State", "France", "DBpedia", "France",
                "https://dbpedia.org/page/France", "France", "dbpedia");

        assertFalse(IdentityLinks.matches(dbpedia, state, IdentityLinks.WIKIDATA));
        assertTrue(IdentityLinks.matches(dbpedia, state), "any-source matching finds it");
    }

    @Test void anUntypedDynamicObjectNeverUsesItsJavaImplementationAsIdentityType() {
        var unknown = new wikidata.explore.extract.WikidataDynamicObject("local", "Unknown");
        assertNull(IdentityLinks.stableType(unknown));
        assertFalse(IdentityLinks.matchableTypes(unknown)
                .contains("WikidataDynamicObject"));
    }
}
