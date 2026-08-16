package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A populated field is left alone when values are assigned — so asking Wikidata for it
 * downloads a property already known and discards the answer. Over a whole domain that
 * is most of the requests: re-running for one new field re-fetched every old one too.
 */
class ReferentFieldLoadSkipTest {

    @Test void sameSizedPoolStillLoadsAReplacementEntity() throws Exception {
        GeneratedProjectModel project = project();
        List<List<String>> asked = new ArrayList<>();
        var previous = new wikidata.explore.extract.LoadedDeclaration(
                "ForWork", "genre", "P136", List.of("Q1"));

        ReferentFieldLoad.load(project, List.of(work("Q2")), recording(asked), null,
                List.of(previous));

        assertEquals(List.of(List.of("Q2")), asked,
                "identity coverage, not an equal object count, controls the skip");
    }

    @Test void failedRequestIsNotRecordedAsCovered() throws Exception {
        GeneratedProjectModel project = project();
        WikidataApiClient failing = new WikidataApiClient(
                WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public PartialEntities getEntityClaimsPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                throw new RuntimeException("network down");
            }
        };

        ReferentFieldLoad.Result result = ReferentFieldLoad.load(
                project, List.of(work("Q1")), failing, null, List.of());

        assertTrue(result.completed().isEmpty(),
                "a failed request must be retried by the next enrich");
    }

    @Test void asksOnlyForTheEntitiesStillMissingTheField() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        GeneratedFieldModel ref = nomination.addField(
                "forWork", FieldType.ENTITY, FieldCardinality.SINGLE);
        ref.entityClassName("ForWork");
        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        GeneratedFieldModel genre = forWork.addField(
                "genre", FieldType.ENTITY, FieldCardinality.SINGLE);
        genre.mapping().propertyPid("P136");
        project.rootClass(nomination);
        project.addClass(forWork);

        WikidataDynamicObject filled = work("Q1");
        filled.put("genre", new WikidataDynamicObject("Q130232", "drama film"));
        WikidataDynamicObject empty = work("Q2");
        List<List<String>> asked = new ArrayList<>();

        ReferentFieldLoad.apply(project, List.of(filled, empty), recording(asked), null);

        assertEquals(1, asked.size(), "one request round");
        assertEquals(List.of("Q2"), asked.getFirst(),
                "the work that already has a genre is not asked about again");
    }

    @Test void asksNothingWhenEveryInstanceIsFilled() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        GeneratedFieldModel ref = nomination.addField(
                "forWork", FieldType.ENTITY, FieldCardinality.SINGLE);
        ref.entityClassName("ForWork");
        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        forWork.addField("genre", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P136");
        project.rootClass(nomination);
        project.addClass(forWork);

        WikidataDynamicObject filled = work("Q1");
        filled.put("genre", new WikidataDynamicObject("Q130232", "drama film"));
        List<List<String>> asked = new ArrayList<>();

        int loaded = ReferentFieldLoad.apply(
                project, List.of(filled), recording(asked), null);

        assertEquals(0, loaded);
        assertTrue(asked.isEmpty(), "no request at all when nothing is missing");
    }

    /**
     * A load over thousands of entities normally answers for nearly all of them. When
     * three batches of 50 went unreachable, the run reported the whole declaration
     * unresolved for 4,972 entities and recorded nothing — so the next enrich re-asked
     * Wikidata for every one of them. What answered is covered; only what no batch
     * reached is unresolved.
     */
    @Test void whatTheReachableBatchesAnsweredIsKept() throws Exception {
        GeneratedProjectModel project = project();
        WikidataApiClient partly = new WikidataApiClient(
                WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public PartialEntities getEntityClaimsPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                Map<String, ApiEntity> out = new LinkedHashMap<>();
                out.put("Q1", new ApiEntity("Q1", "Q1",
                        Map.of("P136", List.of("Q130232")), false, Map.of()));
                return new PartialEntities(out, 1, List.of("Q2"));
            }
        };

        ReferentFieldLoad.Result result = ReferentFieldLoad.load(project,
                List.of(work("Q1"), work("Q2")), partly, null, List.of(), true);

        assertEquals(1, result.loaded(), "the entity that answered is loaded");
        assertEquals(List.of("Q1"), result.completed().getFirst().coveredQids(),
                "and covered, so the next run does not ask about it again");
        assertEquals(List.of("Q2"), result.failed().getFirst().coveredQids(),
                "only the unreached entity is unresolved");
    }

    private static WikidataDynamicObject work(String qid) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, qid);
        o.type("ForWork");
        o.typeKey("ForWork");
        o.assignClass("ForWork");
        return o;
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        GeneratedFieldModel ref = nomination.addField(
                "forWork", FieldType.ENTITY, FieldCardinality.SINGLE);
        ref.entityClassName("ForWork");
        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        forWork.addField("genre", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P136");
        project.rootClass(nomination);
        project.addClass(forWork);
        return project;
    }

    /** A claim load asks through the PARTIAL entry point, so that a batch nobody could
     *  reach costs only its own entities rather than the whole declaration. */
    private static WikidataApiClient recording(List<List<String>> asked) {
        return new WikidataApiClient(WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public PartialEntities getEntityClaimsPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                if (pids != null && !pids.isEmpty()) asked.add(List.copyOf(qids));
                Map<String, ApiEntity> out = new LinkedHashMap<>();
                for (String q : qids) {
                    out.put(q, new ApiEntity(q, q, Map.of(), false, Map.of()));
                }
                return new PartialEntities(out, 0);
            }
        };
    }
}
