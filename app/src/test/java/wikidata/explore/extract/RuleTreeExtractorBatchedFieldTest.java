package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.RuleDirection;
import wikidata.api.FactDemand;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void rootFetchRetainsAProspectiveDownstreamPropertyWithoutMaterializingIt()
            throws Exception {
        AtomicReference<List<String>> requested = new AtomicReference<>(List.of());
        try (WikidataSparqlClient sparql = new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String query) {
                return List.of(new WikidataBinding(Map.of(
                        "value", "http://www.wikidata.org/entity/Q11",
                        "valueLabel", "Example film")));
            }
        }) {
            FakeWikidataApiClient api = new FakeWikidataApiClient() {
                @Override public Map<String, ApiEntity> getEntities(
                        List<String> qids, List<String> pids, BatchLog log) {
                    if (!pids.isEmpty()) requested.set(List.copyOf(pids));
                    return super.getEntities(qids, pids, log);
                }
            };
            api.entity("Q11", "Example film", Map.of(
                    "P840", List.of("Q1044"), "P31", List.of("Q11424")))
               .entity("Q1044", "Sierra Leone")
               .entity("Q11424", "film");

            List<WikidataDynamicObject> movies = new RuleTreeExtractor(sparql)
                    .api(api)
                    .factDemands(List.of(FactDemand.of(
                            "disambiguation prune", "Movies", List.of("P31"),
                            "vet internal pages")))
                    .load(moviesWithLocations(), 0);

            assertEquals(List.of("P840", "P31"), requested.get());
            assertTrue(movies.getFirst().get("locations") instanceof WikidataDynamicObject);
            assertNull(movies.getFirst().get("type"),
                    "a retained fact must not invent a configured field");
        }
    }

    /** An outgoing scalar rides the same claims documents as outgoing entity fields;
     * the plan must not schedule the old residual SPARQL pass. */
    @Test
    void outgoingScalarFieldsRideTheRootEntityRequest() {
        RuleNode node = moviesWithLocations();
        RuleIncludedField duration = new RuleIncludedField(
                "duration", "P2047", "duration",
                RuleIncludedField.FieldKind.AUTO, true);
        node.addIncludedField(duration);

        List<String> plan = new RuleTreeExtractor(null).previewQueries(node, 0);
        String joined = String.join("\n", plan);
        assertTrue(joined.contains("duration\" via wbgetentities"), joined);
        assertFalse(joined.contains("Residual scalar/media"), joined);
    }

    @Test
    void malformedOutgoingPropertyFailsInsteadOfProducingASilentEmptyField() {
        RuleNode node = moviesWithLocations();
        RuleIncludedField broken = new RuleIncludedField(
                "composer", "", "composer",
                RuleIncludedField.FieldKind.ENTITY, true);
        node.addIncludedField(broken);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new RuleTreeExtractor(null).previewQueries(node, 0));

        assertTrue(error.getMessage().contains("Movies.composer"), error.getMessage());
        assertTrue(error.getMessage().contains("property PID"), error.getMessage());
    }

    @Test
    void loadDecodesOutgoingDateAndEntityFromOneApiPass() throws Exception {
        RuleNode node = moviesWithLocations();
        RuleIncludedField released = new RuleIncludedField(
                "publicationDate", "P577", "publication date",
                RuleIncludedField.FieldKind.AUTO, true);
        node.addIncludedField(released);

        AtomicInteger sparqlCalls = new AtomicInteger();
        AtomicReference<List<String>> requested = new AtomicReference<>(List.of());
        try (WikidataSparqlClient sparql = new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String query) {
                sparqlCalls.incrementAndGet();
                return List.of(new WikidataBinding(Map.of(
                        "value", "http://www.wikidata.org/entity/Q11",
                        "valueLabel", "Example film")));
            }
        }) {
            FakeWikidataApiClient api = new FakeWikidataApiClient() {
                @Override public Map<String, ApiEntity> getEntities(
                        List<String> qids, List<String> pids, BatchLog log) {
                    if (!pids.isEmpty()) requested.set(List.copyOf(pids));
                    return super.getEntities(qids, pids, log);
                }
            };
            api.entity("Q11", "Example film", Map.of(
                    "P840", List.of("Q1044"),
                    "P577", List.of("+1974-06-20T00:00:00Z")))
               .entity("Q1044", "Sierra Leone");

            WikidataDynamicObject movie = new RuleTreeExtractor(sparql).api(api)
                    .load(node, 0).getFirst();

            assertEquals(1, sparqlCalls.get(), "only membership uses SPARQL");
            assertEquals(List.of("P840", "P577"), requested.get());
            assertTrue(movie.get("locations") instanceof WikidataDynamicObject);
            assertTrue(movie.get("publicationDate") instanceof aux.FlexibleDate);
        }
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
