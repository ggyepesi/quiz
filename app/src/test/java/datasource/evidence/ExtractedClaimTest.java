package datasource.evidence;

import datasource.EntityRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExtractedClaimTest {
    @Test
    void claimAndAssessmentIdentitySeparateSourceFactFromReviewAssessment() {
        ExtractedClaim first = claim(0.91, List.of("review narrative meaning"));
        ExtractedClaim reassessed = claim(0.75, List.of("corroborated"));

        assertEquals(first.claimId(), reassessed.claimId());
        assertNotEquals(first.assessmentId(), reassessed.assessmentId());
    }

    @Test
    void evidenceCanReferToAnyDatasourceEntity() {
        ExtractedClaim claim = new ExtractedClaim(
                new EntityRef("local", "film-17"), "narrative-location", null,
                new EntityRef("geonames", "2403846"),
                List.of(EvidenceFragment.excerpt(document("123"), "Plot", "In Sierra Leone.")),
                "linked-place-cues", "v1", "movies-a", 0.9, List.of());

        assertEquals("geonames:2403846", claim.proposedEntity().qualifiedId());
    }

    @Test
    void positionedEvidencePreservesWhitespaceAndChecksExactRangeLength() {
        EvidenceFragment span = EvidenceFragment.positioned(
                document("123"), "Lead", 10, " text");
        assertEquals(" text", span.excerpt());
        assertThrows(IllegalArgumentException.class, () -> new EvidenceFragment(
                document("123"), "Lead", 10, 14, " text"));
    }

    @Test
    void stalenessHasAnExplicitReason() {
        ExtractedClaim claim = claim(0.9, List.of());
        assertEquals(EvidenceStatus.CURRENT,
                claim.statusAgainst(document("123"), "v1", "movies-a"));
        assertEquals(EvidenceStatus.STALE_SOURCE,
                claim.statusAgainst(document("124"), "v1", "movies-a"));
        assertEquals(EvidenceStatus.STALE_RECIPE,
                claim.statusAgainst(document("123"), "v2", "movies-a"));
        assertEquals(EvidenceStatus.STALE_MODEL,
                claim.statusAgainst(document("123"), "v1", "movies-b"));
        assertEquals(EvidenceStatus.SOURCE_UNAVAILABLE,
                claim.statusAgainst(null, "v1", "movies-a"));
    }

    @Test
    void lineageRejectsProcessLocalObjectStrings() {
        assertThrows(IllegalArgumentException.class, () -> new ExtractedClaim(
                EntityRef.wikidata("Q1"), "example", new Object(), null,
                List.of(EvidenceFragment.excerpt(document("123"), "Lead", "evidence")),
                "extract", "v1", "model", 1, List.of()));
    }

    @Test
    void stableEqualityDoesNotEquateDisplayCompatibleTypes() {
        assertTrue(ExtractedClaim.sameStableValue(1L, 1L));
        assertFalse(ExtractedClaim.sameStableValue(1L, "1"));
        assertFalse(ExtractedClaim.isStableValue(null));
    }

    @Test
    void revalidationLooksUpASharedDocumentOnlyOnce() {
        ExtractedClaim first = claim(0.9, List.of());
        ExtractedClaim second = claim(0.8, List.of("check"));
        AtomicInteger lookups = new AtomicInteger();

        List<EvidenceRevalidator.Assessment> result = EvidenceRevalidator.assess(
                List.of(first, second), accepted -> {
                    lookups.incrementAndGet();
                    return accepted;
                }, "v1", "movies-a");

        assertEquals(1, lookups.get());
        assertEquals(List.of(EvidenceStatus.CURRENT, EvidenceStatus.CURRENT),
                result.stream().map(EvidenceRevalidator.Assessment::status).toList());
    }

    private static ExtractedClaim claim(double confidence, List<String> warnings) {
        return new ExtractedClaim(
                EntityRef.wikidata("Q157058"), "P840", null,
                EntityRef.wikidata("Q1044"),
                List.of(EvidenceFragment.excerpt(document("123"), "Plot",
                        "The story takes place in Sierra Leone.")),
                "linked-place-cues", "v1", "movies-a", confidence, warnings);
    }

    private static SourceDocument document(String revision) {
        return new SourceDocument("Wikipedia", "Blood_Diamond", "Blood Diamond",
                "https://en.wikipedia.org/wiki/Blood_Diamond", revision,
                new ContentDigest("sha256", "abc"), "2026-08-21T09:00:00Z");
    }
}
