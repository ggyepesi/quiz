package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.api.FactDemand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 3b: a reify can draw its subjects from a POPULATION — with no source-class
 * members in the pool, QualifierLoader discovers the entities carrying the
 * statement property into the value domain, stamps them the load type, adds them
 * to the pool, and loads their statements. Guarded: no value set => no discovery.
 */
class PopulationSubjectLoaderTest {

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
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "category", "", List.of(), valueQids, discover);
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
        assertEquals("https://www.wikidata.org/wiki/Q105883400",
                subject.get("wikidata"),
                "direct discovery keeps the same source link as ordinary entities");
        assertFalse(created.isEmpty(),
                "its statement was loaded, ready to reify");
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
