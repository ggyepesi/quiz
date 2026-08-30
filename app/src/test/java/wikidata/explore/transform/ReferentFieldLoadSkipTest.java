package wikidata.explore.transform;

import datasource.schema.FieldType;

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

    @Test void emptyAliasesAreDurablyCoveredAcrossClients() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("birthDate", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().propertyPid("P569");
        project.addClass(person);
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        WikidataDynamicObject q1 = new WikidataDynamicObject("Q1", "One");
        q1.type("Person"); q1.typeKey("Person"); q1.assignClass("Person");

        class AliasCountingClient extends WikidataApiClient {
            int aliasRequests;
            AliasCountingClient() { super(DEFAULT_USER_AGENT); }
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                return new PartialStatements(Map.of(), 0, List.of());
            }
            @Override public Map<String, List<String>> getAliases(
                    List<String> qids, BatchLog log) {
                aliasRequests++;
                Map<String, List<String>> result = new LinkedHashMap<>();
                qids.forEach(qid -> result.put(qid, List.of()));
                return result;
            }
        }
        AliasCountingClient first = new AliasCountingClient();
        ReferentFieldLoad.Result initial = ReferentFieldLoad.load(
                project, List.of(q1), first, null, List.of());
        assertEquals(1, first.aliasRequests);

        AliasCountingClient nextRun = new AliasCountingClient();
        ReferentFieldLoad.load(project, List.of(q1), nextRun, null, initial.completed());
        assertEquals(0, nextRun.aliasRequests,
                "an empty alias answer is coverage, not an invitation to ask forever");
    }

    /**
     * The entity request carries aliases, so a class with entity-valued fields needs no
     * second identity pass — and the coverage it banks is true. When it went the other
     * way (coverage recorded from a response that had never been asked for aliases), the
     * names were never fetched, the declaration said they had been, and no later Enrich
     * could repair it.
     */
    @Test void anEntityLoadAnswersAliasesWithoutASecondPass() throws Exception {
        GeneratedProjectModel project = project();
        WikidataDynamicObject work = work("Q1");

        class Probe extends WikidataApiClient {
            int aliasRequests;
            final boolean answersAliases;
            Probe(boolean answersAliases) {
                super(DEFAULT_USER_AGENT);
                this.answersAliases = answersAliases;
            }
            @Override public PartialEntities getEntityClaimsPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                Map<String, ApiEntity> out = new LinkedHashMap<>();
                for (String qid : qids) {
                    out.put(qid, answersAliases
                            // props=labels|claims|aliases: an answer, values or not
                            ? new ApiEntity(qid, qid, Map.of("P136", List.of("Q130232")),
                                    false, Map.of(), List.of("Alias of " + qid))
                            // a response that never carried aliases
                            : new ApiEntity(qid, qid, Map.of("P136", List.of("Q130232")),
                                    false, Map.of()));
                }
                return new PartialEntities(out, 0);
            }
            @Override public Map<String, List<String>> getAliases(
                    List<String> qids, BatchLog log) {
                aliasRequests++;
                Map<String, List<String>> out = new LinkedHashMap<>();
                qids.forEach(qid -> out.put(qid, List.of("Alias of " + qid)));
                return out;
            }
        }

        Probe answering = new Probe(true);
        ReferentFieldLoad.Result answered = ReferentFieldLoad.load(
                project, List.of(work), answering, null, List.of(), true);

        assertEquals(List.of("Alias of Q1"), work.aliases(),
                "the entity response names the entity as well as its claims");
        assertEquals(0, answering.aliasRequests, "no second pass over the same entities");
        assertTrue(answered.completed().stream().anyMatch(
                        d -> d.key().endsWith("@aliases") && d.coveredQids().contains("Q1")),
                "what was answered is covered");

        WikidataDynamicObject other = work("Q1");
        Probe silent = new Probe(false);
        ReferentFieldLoad.load(
                project, List.of(other), silent, null, List.of(), true);

        assertEquals(1, silent.aliasRequests,
                "a response that did not answer aliases must not be banked as if it had");
        assertEquals(List.of("Alias of Q1"), other.aliases());
    }

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
            @Override public Map<String, List<String>> getAliases(
                    List<String> qids, BatchLog log) {
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
            @Override public Map<String, List<String>> getAliases(
                    List<String> qids, BatchLog log) {
                Map<String, List<String>> out = new LinkedHashMap<>();
                qids.forEach(qid -> out.put(qid, List.of()));
                return out;
            }
        };

        ReferentFieldLoad.Result result = ReferentFieldLoad.load(project,
                List.of(work("Q1"), work("Q2")), partly, null, List.of(), true);

        assertEquals(1, result.loaded(), "the entity that answered is loaded");
        var fieldCoverage = result.completed().stream()
                .filter(d -> d.fieldName().equals("genre")).findFirst().orElseThrow();
        assertEquals(List.of("Q1"), fieldCoverage.coveredQids(),
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
            @Override public Map<String, List<String>> getAliases(
                    List<String> qids, BatchLog log) {
                Map<String, List<String>> out = new LinkedHashMap<>();
                qids.forEach(qid -> out.put(qid, List.of()));
                return out;
            }
        };
    }
}
