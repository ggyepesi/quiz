package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleTreeExtractorBatchedFieldTest {

    /**
     * A REQUIRED field has to narrow membership at the point membership is decided.
     * The backbone query is that point — the value itself arrives later, over the
     * members — so its triple must appear there, non-OPTIONAL, and the class
     * ranking (which decides WHICH `limit` instances are kept) must survive too.
     * Both were dropped by sampleCopy, so 20,000 films were selected unranked and
     * 40% of them had no narrative location at all.
     */
    @Test
    void theBackboneRequiresTheRequiredFieldAndKeepsTheRanking() {
        RuleNode node = moviesWithLocations();
        node.includedFields().getFirst().optional(false);   // Required
        node.rankBySitelinks(true);
        node.requireSitelink(true);

        String backbone = new RuleTreeExtractor(null).previewQueries(node, 0).stream()
                .filter(q -> q.contains("Root membership (backbone)"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no backbone query in plan"));

        assertTrue(backbone.contains("wdt:P840"),
                   "the required field must constrain membership: " + backbone);
        assertFalse(backbone.contains("OPTIONAL"),
                    "a required field is not optional: " + backbone);
        assertTrue(backbone.contains("wikibase:sitelinks"),
                   "the ranking must survive into the backbone: " + backbone);
        assertTrue(backbone.contains("ORDER BY DESC"), backbone);
        // Constraint only: selecting a multi-valued property would make the LIMIT
        // count value-pairs instead of films.
        assertFalse(backbone.contains("?locations"),
                    "the required field is a constraint, not a selected value: " + backbone);
    }

    /** An OPTIONAL field must NOT narrow membership — instances lacking it are
     *  legitimate members whose value is simply absent. */
    @Test
    void anOptionalFieldDoesNotConstrainMembership() {
        String backbone = new RuleTreeExtractor(null)
                .previewQueries(moviesWithLocations(), 0).stream()
                .filter(q -> q.contains("Root membership (backbone)"))
                .findFirst().orElseThrow();

        assertFalse(backbone.contains("wdt:P840"),
                    "an optional field must not filter the member set: " + backbone);
    }

    private static RuleNode moviesWithLocations() {
        RuleNode node = new RuleNode("Movies", "movies");
        node.sourceQid("Q11424");
        node.propertyPid("P31");
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.limit(500);

        RuleIncludedField locations = new RuleIncludedField(
                "locations", "P840", "narrative location",
                RuleIncludedField.FieldKind.ENTITY, true);
        locations.collection(true);
        locations.direction(RuleDirection.ROOT_TO_ITEM);
        node.addIncludedField(locations);
        return node;
    }

    @Test
    void previewUsesMembershipThenBoundedApiClaimsWithoutGroupConcat() {
        RuleTreeExtractor extractor = new RuleTreeExtractor(null);

        List<String> plan = extractor.previewQueries(moviesWithLocations(), 0);
        String joined = String.join("\n", plan);

        assertTrue(joined.contains("Root membership (backbone)"), joined);
        assertTrue(joined.contains("locations\" via wbgetentities"), joined);
        assertTrue(joined.contains("ids batched by 50"), joined);
        assertFalse(joined.contains("GROUP_CONCAT"), joined);
        assertFalse(joined.contains("locations_inlined"), joined);
    }

    @Test
    void loadDoesNotIssueASecondSparqlQueryForOutgoingEntityClaims() throws Exception {
        AtomicInteger sparqlCalls = new AtomicInteger();
        try (WikidataSparqlClient sparql = new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String query) {
                sparqlCalls.incrementAndGet();
                return List.of(new WikidataBinding(Map.of(
                        "value", "http://www.wikidata.org/entity/Q11",
                        "valueLabel", "Example film")));
            }
        }) {
            FakeWikidataApiClient api = new FakeWikidataApiClient()
                    .entity("Q11", "Example film", Map.of("P840", List.of("Q1044")))
                    .entity("Q1044", "Sierra Leone");

            List<WikidataDynamicObject> movies =
                    new RuleTreeExtractor(sparql).api(api).load(moviesWithLocations(), 0);

            assertEquals(1, sparqlCalls.get(), "only the membership backbone uses SPARQL");
            assertEquals(1, movies.size());
            WikidataDynamicObject location =
                    (WikidataDynamicObject) movies.getFirst().get("locations");
            assertEquals("Q1044", location.qid());
            assertEquals("Sierra Leone", location.getDisplayName());
        }
    }

    /** A scalar field must not re-scan the class: the members are already known,
     *  so its query is bounded by VALUES and batched like every other Stage-2 pass.
     *  The whole-class form is what soft-timed-out on a large membership. */
    @Test
    void scalarFieldsAreFetchedOverTheKnownMembersNotTheWholeClass() {
        RuleNode node = moviesWithLocations();
        RuleIncludedField duration = new RuleIncludedField(
                "duration", "P2047", "duration",
                RuleIncludedField.FieldKind.AUTO, true);
        node.addIncludedField(duration);

        List<String> plan = new RuleTreeExtractor(null).previewQueries(node, 0);
        String residual = plan.stream()
                              .filter(p -> p.contains("Residual scalar/media"))
                              .findFirst()
                              .orElseThrow(() -> new AssertionError(
                                      "no residual query in plan: " + plan));

        assertTrue(residual.contains("VALUES ?value"), residual);
        assertTrue(residual.contains("<member batch>"), residual);
        assertTrue(residual.contains("P2047"), residual);
        assertFalse(residual.contains("P840"),
                    "the entity-list field is captured by Stage 2, not the residual");
    }

    @Test
    void outgoingClaimFailureAbortsInsteadOfCreatingFalseMissingValues() throws Exception {
        try (WikidataSparqlClient sparql = new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String query) {
                return List.of(new WikidataBinding(Map.of(
                        "value", "http://www.wikidata.org/entity/Q11",
                        "valueLabel", "Example film")));
            }
        }) {
            WikidataApiClient failingApi = new WikidataApiClient("test") {
                @Override public Map<String, ApiEntity> getEntities(
                        List<String> qids, List<String> pids, BatchLog log)
                        throws Exception {
                    throw new Exception("simulated wbgetentities failure");
                }
            };

            Exception error = assertThrows(Exception.class,
                                           () -> new RuleTreeExtractor(sparql).api(failingApi)
                                                                              .load(moviesWithLocations(), 0));

            assertTrue(error.getMessage().contains("simulated wbgetentities failure"));
        }
    }
}
