package wikidata.explore.transform;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.api.FactDemand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 3b: a reify can draw its subjects from a POPULATION — with no source-class
 * members in the pool, QualifierLoader discovers the entities carrying the
 * statement property into the value domain, stamps them the load type, adds them
 * to the pool, and loads their statements. Guarded: no value set => no discovery.
 */
class PopulationSubjectLoaderTest {

    @Test void requestIsVisibleBeforeSubjectDiscoveryBlocks() {
        List<String> events = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                events.add("query");
                return super.query(query);
            }
        };
        wikidata.explore.extract.GenerationLog log = new wikidata.explore.extract.GenerationLog() {
            @Override public void message(String text) { }
            @Override public void subquery(String title, String request, String summary) { }
            @Override public Running subqueryStarted(String title, String request) {
                events.add("started:" + request);
                return new Running() {
                    @Override public void done(String summary) { events.add("done:" + summary); }
                    @Override public void failed(String error) { events.add("failed:" + error); }
                };
            }
        };

        new PopulationSubjectLoader().discover(new ArrayList<>(), "P166", Set.of("Q38104"),
                "__subject", "Categories", sparql, log);

        assertTrue(events.getFirst().startsWith("started:SELECT DISTINCT"), events.toString());
        assertEquals("query", events.get(1));
        assertTrue(events.getFirst().contains("VALUES ?value { wd:Q38104 }"),
                events.toString());
        assertEquals("done:ok", events.get(2));
    }

    @Test void inspectionCanBoundSubjectDiscoveryWithoutChangingProductionDefault() {
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                requests.add(query);
                return List.of();
            }
        };

        new PopulationSubjectLoader().discover(new ArrayList<>(), "P166", Set.of("Q38104"),
                "__subject", "Categories", sparql, null, 9);
        new PopulationSubjectLoader().discover(new ArrayList<>(), "P166", Set.of("Q38104"),
                "__subject", "Categories", sparql, null);

        assertTrue(requests.getFirst().endsWith("LIMIT 9"));
        assertTrue(requests.get(1).contains("VALUES ?value { wd:Q38104 }"));
        assertFalse(requests.get(1).contains("LIMIT"),
                "generation keeps complete discovery unless a caller explicitly bounds it");
    }

    @Test void aRelationalObjectPopulationIsResolvedThenReversedInBoundedBatches() {
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            int reverse;
            boolean populatedBand;
            @Override public List<wikidata.WikidataBinding> query(String query) {
                requests.add(query);
                if (query.startsWith("SELECT DISTINCT ?value WHERE")) {
                    if (populatedBand) return List.of();
                    populatedBand = true;
                    List<wikidata.WikidataBinding> values = new ArrayList<>();
                    for (int i = 1; i <= 105; i++) {
                        values.add(new wikidata.WikidataBinding(
                                Map.of("value", "Q" + (10_000 + i))));
                    }
                    return values;
                }
                reverse++;
                return List.of(new wikidata.WikidataBinding(
                        Map.of("subject", "Q" + (90_000 + reverse))));
            }
        };

        List<WikidataDynamicObject> created = new PopulationSubjectLoader().discover(
                new ArrayList<>(), "P39", Set.of(),
                EntityBound.instancesOf("Q4164871"), EntityBound.unbounded(),
                "__OfficeHolding", "positions", sparql, null, 0);

        List<String> objectRequests = requests.stream()
                .filter(q -> q.startsWith("SELECT DISTINCT ?value WHERE")).toList();
        List<String> reverseRequests = requests.stream()
                .filter(q -> q.startsWith("SELECT DISTINCT ?subject WHERE")).toList();
        assertEquals(3, objectRequests.size(),
                "one data page, one empty page, and its completion probe");
        assertTrue(objectRequests.getFirst().contains("?value wdt:P31 ?valueKind"));
        assertTrue(objectRequests.getFirst().contains("LIMIT 1000"));
        assertTrue(objectRequests.getLast().contains("LIMIT 1"));
        assertEquals(3, reverseRequests.size());
        for (String reverse : reverseRequests) {
            assertTrue(reverse.contains("VALUES ?value {"), reverse);
            assertFalse(reverse.contains("?value wdt:P31 ?valueKind"), reverse);
            assertTrue(qidsInValues(reverse) <= 50, reverse);
        }
        assertEquals(3, created.size());
    }

    @Test void alreadyResolvedRelationalObjectsAreNotJoinedAgainInOneHugeQuery() {
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                requests.add(query);
                return List.of();
            }
        };
        Set<String> resolvedObjects = new LinkedHashSet<>();
        for (int i = 1; i <= 105; i++) resolvedObjects.add("Q" + (20_000 + i));

        new PopulationSubjectLoader().discover(
                new ArrayList<>(), "P39", resolvedObjects,
                EntityBound.instancesOf("Q4164871"), EntityBound.unbounded(),
                "__OfficeHolding", "positions", sparql, null, 0);

        assertEquals(3, requests.size());
        for (String request : requests) {
            assertTrue(request.contains("VALUES ?value {"), request);
            assertFalse(request.contains("?value wdt:P31 ?valueKind"), request);
            assertTrue(qidsInValues(request) <= 50, request);
        }
    }

    @Test void anAnsweredEmptyRelationalObjectPopulationDoesNotFallBackToABroadJoin() {
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                requests.add(query);
                return List.of();
            }
        };

        List<WikidataDynamicObject> created = new PopulationSubjectLoader().discover(
                new ArrayList<>(), "P39", Set.of(),
                EntityBound.instancesOf("Q4164871"), EntityBound.unbounded(),
                "__OfficeHolding", "positions", sparql, null, 0);

        assertTrue(created.isEmpty());
        assertEquals(2, requests.size(), "empty page plus its completion probe");
        assertTrue(requests.stream().allMatch(
                q -> q.startsWith("SELECT DISTINCT ?value WHERE")));
    }

    @Test void processCancellationIsNotReportedAsPopulationFailure() {
        work.CancellationToken token = new work.CancellationToken();
        token.cancel();

        assertThrows(CancellationException.class, () ->
                new PopulationSubjectLoader().cancellation(token).discover(
                        new ArrayList<>(), "P39", Set.of(),
                        EntityBound.instancesOf("Q4164871"), EntityBound.unbounded(),
                        "__OfficeHolding", "positions",
                        new FakeWikidataSparqlClient(), null, 0));
    }

    @Test void aTruncatedReverseBatchIsSplitInsteadOfRetriedUnchanged() {
        List<Integer> reverseBatchSizes = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            boolean populatedPage;
            @Override public List<wikidata.WikidataBinding> query(String query) {
                if (query.startsWith("SELECT DISTINCT ?value WHERE")) {
                    if (populatedPage) return List.of();
                    populatedPage = true;
                    return List.of("Q101", "Q102", "Q103", "Q104").stream()
                            .map(qid -> new wikidata.WikidataBinding(Map.of("value", qid)))
                            .toList();
                }
                int size = qidsInValues(query);
                reverseBatchSizes.add(size);
                if (size > 1) {
                    throw new wikidata.WikidataSparqlClient.TruncatedResponseException(
                            "partial JSON", new IllegalStateException("truncated"));
                }
                Matcher qid = Pattern.compile("VALUES \\?value \\{ wd:(Q\\d+)")
                        .matcher(query);
                assertTrue(qid.find(), query);
                return List.of(new wikidata.WikidataBinding(
                        Map.of("subject", "Q9" + qid.group(1).substring(1))));
            }
        };

        List<WikidataDynamicObject> created = new PopulationSubjectLoader().discover(
                new ArrayList<>(), "P39", Set.of(),
                EntityBound.instancesOf("Q4164871"), EntityBound.unbounded(),
                "__OfficeHolding", "positions", sparql, null, 0);

        assertEquals(List.of(4, 2, 1, 1, 2, 1, 1), reverseBatchSizes,
                "an escaped transport truncation splits immediately; it is not retried");
        assertEquals(4, created.size());
    }

    private static int qidsInValues(String query) {
        Matcher values = Pattern.compile("VALUES \\?value \\{([^}]*)}")
                .matcher(query);
        assertTrue(values.find(), query);
        Matcher qids = Pattern.compile("wd:Q\\d+").matcher(values.group(1));
        int count = 0;
        while (qids.find()) count++;
        return count;
    }

    private static final class RecordingApi extends FakeWikidataApiClient {
        List<String> requestedPids = List.of();
        Set<FactDemand.EntityMetadata> requestedMetadata = Set.of();

        @Override public Map<String, ApiEntity> getEntities(
                List<String> qids, List<String> pids, BatchLog log) {
            if (pids != null && !pids.isEmpty()) requestedPids = List.copyOf(pids);
            return super.getEntities(qids, pids, log);
        }

        @Override public Map<String, ApiEntity> getEntities(
                List<String> qids, List<String> pids,
                Collection<FactDemand.EntityMetadata> metadata, BatchLog log) {
            requestedMetadata = metadata == null ? Set.of() : Set.copyOf(metadata);
            return getEntities(qids, pids, log);
        }
    }

    private static QualifierLoadConfig cfg(boolean discover, List<String> valueQids) {
        return new QualifierLoadConfig(
                "OscarNominations",
                "P1411",
                "__Nomination",
                "Nomination",
                "category",
                EntityBound.explicit(valueQids),
                List.of(),
                discover,
                "");
    }

    @Test void discoversSubjectsAndLoadsTheirStatements() {
        // Membership query returns the film; its P1411 statement is a Best Picture.
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of("subject", "Q105883400"));
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q105883400", "The Whale")              // label for the subject
                .statement("Q105883400", "P1411", "Q105883400$s", "Q102427", Map.of());

        List<WikidataDynamicObject> pool = new ArrayList<>();   // NO source-class members
        List<WikidataDynamicObject> created = new QualifierLoader().api(api)
                .enrich(pool, cfg(true, List.of("Q102427")), sparql, null);

        WikidataDynamicObject subject = pool.stream()
                .filter(o -> "Q105883400".equals(o.qid())).findFirst().orElseThrow();
        assertEquals("OscarNominations", subject.typeName(),
                "the population subject was discovered, stamped, and pooled");
        assertEquals("The Whale", subject.getDisplayName(),
                "its label was resolved, not left as a bare QID");
        Object provenance = objectview.field.FieldSet.of(subject)
                .read("wikidataSource");
        assertEquals("Q105883400", ((quiz.source.WikidataSource)
                assertInstanceOf(List.class, provenance).getFirst()).qid(),
                "direct discovery publishes the same source field as ordinary entities");
        assertFalse(created.isEmpty(),
                "its statement was loaded, ready to reify");
    }

    @Test void discoverySeedsDoNotFilterOtherStatementsOfTheReachedSubject() {
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of("subject", "Q82686"));
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q82686", "Bela II of Hungary")
                .statement("Q82686", "P39", "Q82686$king", "Q6412254", Map.of())
                .statement("Q82686", "P39", "Q82686$ban", "Q253779", Map.of());

        QualifierLoadConfig config = new QualifierLoadConfig(
                "__subject_OfficeHolding",
                "P39",
                "__OfficeHolding",
                "OfficeHolding",
                "position",
                EntityBound.unbounded(),
                List.of(),
                List.of("Q6412254"),
                true,
                "");

        List<WikidataDynamicObject> created = new QualifierLoader().api(api)
                .enrich(new ArrayList<>(), config, sparql, null);

        assertEquals(Set.of("Q6412254", "Q253779"), created.stream()
                .map(statement -> (WikidataDynamicObject) statement.get("position"))
                .map(WikidataDynamicObject::qid).collect(java.util.stream.Collectors.toSet()),
                "the seed bounds reverse discovery; it is not the forward edge filter");
    }

    @Test void refusesToDiscoverWithoutAValueSet() {
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of("subject", "Q105883400"));
        List<WikidataDynamicObject> pool = new ArrayList<>();

        // discoverSubjects=true but no valueQids/valueType => the guard refuses.
        new QualifierLoader().api(new FakeWikidataApiClient())
                .enrich(pool, cfg(true, List.of()), sparql, null);

        assertTrue(pool.isEmpty(), "no unbounded membership scan without a value set");
    }

    @Test void firstSubjectRequestCarriesItsProspectiveRoleClosure() {
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of("subject", "Q105883400"));
        RecordingApi api = new RecordingApi();
        api.entity("Q105883400", "The Whale")
                .statement("Q105883400", "P1411", "Q105883400$s",
                        "Q102427", Map.of());
        StatementFactDemands demands = new StatementFactDemands(
                List.of(new FactDemand("semantic convergence", "Nominee",
                        Set.of("P31", "P569", "P734"),
                        Set.of(FactDemand.EntityMetadata.ALIASES),
                        "future role fields")),
                Map.of());

        new QualifierLoader().api(api).factDemands(demands)
                .enrich(new ArrayList<>(), cfg(true, List.of("Q102427")), sparql, null);

        assertEquals(Set.of("P1411", "P31", "P569", "P734"),
                Set.copyOf(api.requestedPids));
        assertEquals(Set.of(FactDemand.EntityMetadata.LABEL,
                FactDemand.EntityMetadata.ALIASES), api.requestedMetadata);
    }
}
