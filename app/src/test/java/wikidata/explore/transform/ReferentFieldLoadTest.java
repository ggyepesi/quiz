package wikidata.explore.transform;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelValidator;
import wikidata.explore.model.OwnedFieldSource;
import wikidata.explore.model.OwnedFieldSources;
import wikidata.explore.model.RuleDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferentFieldLoadTest {

    @Test void incomingFieldsBelongOnlyToTheBoundedFieldQuery() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel deity = new GeneratedClassModel("EgyptianDeity");
        GeneratedFieldModel symbolizedBy = deity.addField(
                "symbolizedBy", FieldType.ENTITY, FieldCardinality.COLLECTION);
        symbolizedBy.unclassedEntity(true);
        symbolizedBy.mapping().propertyPid("P4878");
        symbolizedBy.mapping().direction(RuleDirection.ITEM_TO_ROOT);
        model.rootClass(deity);

        WikidataDynamicObject sekhmet = new WikidataDynamicObject("Q146104", "Sekhmet");
        sekhmet.type("EgyptianDeity");
        RecordingApi api = new RecordingApi();
        // If this pass incorrectly reads the member's outgoing claims, it will assign
        // this deliberately opposite-direction value and claim the declaration done.
        api.entity("Q146104", "Sekhmet", Map.of("P4878", List.of("Q760218")))
                .entity("Q760218", "Eye of Ra");
        List<String> messages = new ArrayList<>();

        ReferentFieldLoad.Result result = ReferentFieldLoad.load(
                model, List.of(sekhmet), api,
                wikidata.explore.extract.GenerationLog.of(messages::add), List.of());

        assertEquals(0, result.loaded());
        assertNull(sekhmet.get("symbolizedBy"));
        assertTrue(result.completed().isEmpty(),
                "an outgoing-claims response cannot complete an incoming declaration");
        assertTrue(api.everyClaimPid.isEmpty(),
                "P4878 is acquired by the bounded incoming query, not wbgetentities");
        assertTrue(ReferentFieldLoad.compileManifest(model)
                .propertiesFor("EgyptianDeity").isEmpty());
        assertTrue(messages.stream().noneMatch(message ->
                        message.contains("symbolizedBy")),
                "the unrelated semantic-completion phase must not report the field");
    }

    @Test void outgoingCompletionLogSaysItCheckedOnlyPreviouslyMissingMembers() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel deity = new GeneratedClassModel("EgyptianDeity");
        GeneratedFieldModel domain = deity.addField(
                "domain", FieldType.ENTITY, FieldCardinality.COLLECTION);
        domain.unclassedEntity(true);
        domain.mapping().propertyPid("P2925");
        domain.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.rootClass(deity);

        WikidataDynamicObject deityWithoutDomain =
                new WikidataDynamicObject("Q1", "A deity");
        deityWithoutDomain.type("EgyptianDeity");
        RecordingApi api = new RecordingApi();
        api.entity("Q1", "A deity");
        List<String> messages = new ArrayList<>();

        ReferentFieldLoad.load(model, List.of(deityWithoutDomain), api,
                wikidata.explore.extract.GenerationLog.of(messages::add), List.of());

        assertTrue(messages.contains("Semantic completion EgyptianDeity.domain (P2925): "
                + "checked 1 previously missing member(s), added 0 value(s), "
                + "0 unavailable.\n"));
    }

    @Test void semanticReloadDoesNotRestoreValuesOutsideTheAllowedVocabulary() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel element = new GeneratedClassModel("Element");
        GeneratedFieldModel family = element.addField(
                "partOf", FieldType.ENTITY, FieldCardinality.COLLECTION);
        family.entityClassName("ElementFamily");
        family.mapping().propertyPid("P361");
        family.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        family.mapping().allowedQids().add("Q19557");
        model.rootClass(element);
        model.addClass(new GeneratedClassModel("ElementFamily"));

        WikidataDynamicObject hydrogen = new WikidataDynamicObject("Q556", "hydrogen");
        hydrogen.type("Element");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q556", "hydrogen", Map.of(
                        "P361", List.of("Q10801007", "Q191936", "Q19600", "Q283")))
                .entity("Q10801007", "group 1")
                .entity("Q191936", "period 1")
                .entity("Q19600", "nonmetal")
                .entity("Q283", "water");

        assertEquals(0, ReferentFieldLoad.apply(model, List.of(hydrogen), api, null));
        assertNull(hydrogen.get("partOf"),
                "a semantic reload must not restore values removed by allowedQids");
        assertEquals(0, ReferentClassStamp.apply(model, List.of(hydrogen)),
                "rejected values must never become ElementFamily instances");
    }

    @Test void aConfiguredArticleLoadsForAFieldlessNestedReferent() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel body = new GeneratedClassModel("CelestialBody");
        body.addField("discoverer", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Discoverer");
        GeneratedClassModel discovererClass = new GeneratedClassModel("Discoverer");
        model.rootClass(body);
        model.addClass(discovererClass);

        WikidataDynamicObject discoverer =
                new WikidataDynamicObject("Q42", "Douglas Adams");
        discoverer.type("Discoverer");
        FakeWikidataApiClient api = new FakeWikidataApiClient() {
            @Override public Map<String, WikidataApiClient.ApiEntity> getEntities(
                    List<String> qids, List<String> claimPids,
                    WikidataApiClient.BatchLog batchLog) {
                return Map.of("Q42", new WikidataApiClient.ApiEntity(
                        "Q42", "Douglas Adams", Map.of(), false, Map.of(),
                        List.of(), false, Map.of(), "Douglas_Adams", Map.of()));
            }
        };

        ReferentFieldLoad.load(model, List.of(discoverer), api, null, List.of(),
                true, ReferentFieldLoad.compileManifest(model), Set.of(),
                Set.of("Discoverer"));

        assertEquals("Douglas_Adams", discoverer.enwikiTitle(),
                "source correspondence is metadata of the referent, not a domain field");
    }

    @Test void sourceMembersDiscoveredOutsideTheirRootQueryStillLoadImages() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        person.addField("image", FieldType.IMAGE, FieldCardinality.COLLECTION)
                .mapping().propertyPid("P18");
        model.rootClass(person);

        WikidataDynamicObject discovered = new WikidataDynamicObject("Q1", "Person");
        discovered.type("Person");
        RecordingApi api = new RecordingApi();
        api.entity("Q1", "Person").statement(
                "Q1", "P18", "Q1$image", "Portrait.jpg", Map.of());

        assertEquals(1, ReferentFieldLoad.apply(model, List.of(discovered), api, null));
        Object image = ((List<?>) discovered.get("image")).getFirst();
        assertInstanceOf(wikidata.explore.extract.WikidataMediaValue.class, image);
        assertEquals("Portrait.jpg",
                ((objectview.media.MediaValue) image).mediaLabel());
    }

    @Test void retentionPreflightSeesAllKnownConsumersBeforeAnyLoaderRuns() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        nomination.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        GeneratedFieldModel nomineeType = nominee.addField(
                "type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        nomineeType.mapping().propertyPid("P31");
        nomineeType.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        GeneratedFieldModel genre = forWork.addField(
                "genre", FieldType.ENTITY, FieldCardinality.COLLECTION);
        genre.mapping().propertyPid("P136");
        genre.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.rootClass(nomination);
        model.addClass(nominee);
        model.addClass(forWork);

        WikidataDynamicObject record = new WikidataDynamicObject("N1", "Nomination");
        record.type("Nomination");
        record.put("nominee", new WikidataDynamicObject("Q1", "Nominee"));
        record.put("forWork", new WikidataDynamicObject("Q2", "ForWork"));
        RecordingApi api = new RecordingApi();

        ReferentClassStamp.apply(model, List.of(record));
        ReferentFieldLoad.RetentionPlan plan = ReferentFieldLoad.planRetention(
                List.of(record), api, ReferentFieldLoad.compileManifest(model), List.of());

        assertEquals(2, plan.classes());
        assertEquals(2, plan.entities());
        assertEquals(2, plan.factPairs());
        assertEquals(0, plan.coveredPairs());
        assertEquals(0, api.claimLoads,
                "preflight registers intent without starting acquisition");
    }

    /**
     * A plan describes what will be ASKED FOR. A declaration already loaded for an
     * entity will not be asked for again, so planning it says "keep this" about a fact
     * whose use is over — and since every planned document ranks equal under pressure,
     * planning everything conceivable leaves eviction with no order to follow at the
     * one moment it needs one.
     */
    @Test void preflightDoesNotPlanADeclarationAlreadyLoadedForThatEntity() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("oscars");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        nomination.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        GeneratedFieldModel nomineeType = nominee.addField(
                "type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        nomineeType.mapping().propertyPid("P31");
        nomineeType.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        GeneratedFieldModel genre = forWork.addField(
                "genre", FieldType.ENTITY, FieldCardinality.COLLECTION);
        genre.mapping().propertyPid("P136");
        genre.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.rootClass(nomination);
        model.addClass(nominee);
        model.addClass(forWork);

        WikidataDynamicObject record = new WikidataDynamicObject("N1", "Nomination");
        record.type("Nomination");
        record.put("nominee", new WikidataDynamicObject("Q1", "Nominee"));
        record.put("forWork", new WikidataDynamicObject("Q2", "ForWork"));
        RecordingApi api = new RecordingApi();
        ReferentClassStamp.apply(model, List.of(record));

        var manifest = ReferentFieldLoad.compileManifest(model);
        String nomineePid = manifest.propertiesFor("Nominee").iterator().next();
        ReferentFieldLoad.RetentionPlan plan = ReferentFieldLoad.planRetention(
                List.of(record), api, manifest,
                List.of(new wikidata.explore.extract.LoadedDeclaration(
                        "Nominee", "type", nomineePid, List.of("Q1"))));

        assertEquals(1, plan.coveredPairs(),
                "the loaded declaration is not planned again");
        assertEquals(1, plan.factPairs(),
                "what remains unloaded still is");
        assertEquals(2, plan.entities(), "both members are still reachable");
    }

    @Test void coverageForOneClassDoesNotHideTheSamePendingQidPidOnAnother() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel recordClass = new GeneratedClassModel("Record");
        recordClass.addField("subject", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("RoleA");
        recordClass.addField("otherSubject", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("RoleB");
        GeneratedClassModel roleA = new GeneratedClassModel("RoleA");
        GeneratedFieldModel roleAType = roleA.addField(
                "type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        roleAType.mapping().propertyPid("P31");
        roleAType.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        GeneratedClassModel roleB = new GeneratedClassModel("RoleB");
        GeneratedFieldModel roleBType = roleB.addField(
                "type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        roleBType.mapping().propertyPid("P31");
        roleBType.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.rootClass(recordClass);
        model.addClass(roleA);
        model.addClass(roleB);

        WikidataDynamicObject shared = new WikidataDynamicObject("Q1", "Shared");
        shared.assignClass("RoleA");
        shared.assignClass("RoleB");
        WikidataDynamicObject record = new WikidataDynamicObject("R1", "Record");
        record.type("Record");
        record.put("subject", shared);
        record.put("otherSubject", shared);

        ReferentFieldLoad.RetentionPlan plan = ReferentFieldLoad.planRetention(
                List.of(record), new RecordingApi(), ReferentFieldLoad.compileManifest(model),
                List.of(new wikidata.explore.extract.LoadedDeclaration(
                        "RoleA", "type", "P31", List.of("Q1"))));

        assertEquals(1, plan.coveredPairs());
        assertEquals(1, plan.factPairs(),
                "RoleB still needs Q1/P31 even though RoleA already loaded it");
    }

    private static final class RecordingApi extends FakeWikidataApiClient {
        int claimLoads;
        int literalLoads;
        List<String> loadedPids = List.of();
        List<String> loadedLiteralPids = List.of();
        final java.util.Set<String> everyClaimPid = new java.util.LinkedHashSet<>();

        @Override public Map<String, WikidataApiClient.ApiEntity> getEntities(
                List<String> qids, List<String> claimPids,
                WikidataApiClient.BatchLog batchLog) {
            if (claimPids != null && !claimPids.isEmpty()) {
                claimLoads++;
                loadedPids = List.copyOf(claimPids);
                everyClaimPid.addAll(claimPids);
            }
            return super.getEntities(qids, claimPids, batchLog);
        }

        @Override public Map<String, Map<String, List<WikidataApiClient.ApiStatement>>>
                getStatementsByProperty(List<String> qids, List<String> statementPids,
                                        WikidataApiClient.BatchLog batchLog) {
            literalLoads++;
            loadedLiteralPids = List.copyOf(statementPids);
            return super.getStatementsByProperty(qids, statementPids, batchLog);
        }
    }

    @Test void ownedComponentFieldsLoadFromTheOwnerQid() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        GeneratedFieldModel structuredName = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structuredName.entityClassName("Name");
        structuredName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        GeneratedFieldModel given = name.addField(
                "givenName", FieldType.ENTITY, FieldCardinality.SINGLE);
        given.entityClassName("GivenName");
        given.mapping().propertyPid("P735");
        given.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        GeneratedFieldModel family = name.addField(
                "familyName", FieldType.ENTITY, FieldCardinality.SINGLE);
        family.entityClassName("FamilyName");
        family.mapping().propertyPid("P734");
        family.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.rootClass(person);
        model.addClass(name);

        WikidataDynamicObject owner = new WikidataDynamicObject("Q42", "Douglas Adams");
        owner.type("Person");
        OwnedComponents.Result made = OwnedComponents.apply(
                model, List.of(owner), null, null);
        RecordingApi api = new RecordingApi();
        api.entity("Q42", "Douglas Adams", Map.of(
                        "P735", List.of("Q463035"), "P734", List.of("Q351735")))
                .entity("Q463035", "Douglas")
                .entity("Q351735", "Adams");

        assertEquals(2, ReferentFieldLoad.apply(model, List.of(owner), api, null));
        assertEquals(1, api.claimLoads);
        assertEquals(java.util.Set.of("P734", "P735"),
                new java.util.LinkedHashSet<>(api.loadedPids));
        WikidataDynamicObject component = made.components().get(0);
        assertEquals("Douglas",
                ((WikidataDynamicObject) component.get("givenName")).getDisplayName());
        assertEquals("Adams",
                ((WikidataDynamicObject) component.get("familyName")).getDisplayName());
    }

    @Test void theSameOwnedFieldMayUseDifferentPropertiesAtDifferentOwnerSites() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        GeneratedFieldModel personName = person.addField(
                "personalName", FieldType.ENTITY, FieldCardinality.SINGLE);
        personName.entityClassName("Name");
        personName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel organisation = new GeneratedClassModel("Organisation");
        organisation.membership(EntityBound.relation("P31", List.of("Q43229"), false));
        GeneratedFieldModel legalName = organisation.addField(
                "legalName", FieldType.ENTITY, FieldCardinality.SINGLE);
        legalName.entityClassName("Name");
        legalName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        GeneratedFieldModel part = name.addField(
                "primaryPart", FieldType.ENTITY, FieldCardinality.SINGLE);
        part.mapping().propertyPid("P735");
        part.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        OwnedFieldSource override = OwnedFieldSources.ensureOverride(
                legalName, "primaryPart");
        override.mapping().copyAcquisitionFrom(part.mapping());
        override.mapping().propertyPid("P734");
        model.rootClass(person);
        model.addClass(organisation);
        model.addClass(name);
        // Owners of two kinds: the reference targets the authored Owner role.
        GeneratedClassModel owner = new GeneratedClassModel(
                wikidata.explore.model.OwnedClassSemantics.OWNER_ROLE);
        model.addClass(owner);
        model.representationClasses(owner, List.of("Person", "Organisation"));

        ReferentFieldLoad.AcquisitionManifest manifest =
                ReferentFieldLoad.compileManifest(model);
        assertTrue(manifest.propertiesFor("Person").contains("P735"));
        assertTrue(manifest.propertiesFor("Organisation").contains("P734"));
        assertEquals(Set.of("P734", "P735"),
                manifest.propertiesFor("Name"));

        WikidataDynamicObject human = new WikidataDynamicObject("Q1", "Human owner");
        human.type("Person");
        WikidataDynamicObject company = new WikidataDynamicObject("Q2", "Company owner");
        company.type("Organisation");
        OwnedComponents.apply(model, List.of(human, company), null, null);
        RecordingApi api = new RecordingApi();
        api.entity("Q1", "Human owner", Map.of("P735", List.of("Q11")))
                .entity("Q2", "Company owner", Map.of("P734", List.of("Q22")))
                .entity("Q11", "Human name part")
                .entity("Q22", "Organisation name part");

        assertEquals(2, ReferentFieldLoad.apply(
                model, List.of(human, company), api, null));
        WikidataDynamicObject humanName =
                (WikidataDynamicObject) human.get("personalName");
        WikidataDynamicObject companyName =
                (WikidataDynamicObject) company.get("legalName");
        assertEquals("Human name part", ((WikidataDynamicObject)
                humanName.get("primaryPart")).getDisplayName());
        assertEquals("Organisation name part", ((WikidataDynamicObject)
                companyName.get("primaryPart")).getDisplayName());
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }

    /** An owned class is admitted with its property-less fields, because a site override
     *  may supply one; a site without an override must still not ask for them. */
    @Test void aSiteWithoutAnOverrideLoadsOnlyTheOwnedFieldsThatNameAProperty() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        GeneratedFieldModel personName = person.addField(
                "personalName", FieldType.ENTITY, FieldCardinality.SINGLE);
        personName.entityClassName("Name");
        personName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        GeneratedFieldModel primaryPart = name.addField(
                "primaryPart", FieldType.ENTITY, FieldCardinality.SINGLE);
        primaryPart.mapping().propertyPid("P735");
        primaryPart.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        name.addField("unsourced", FieldType.ENTITY, FieldCardinality.SINGLE);
        model.rootClass(person);
        model.addClass(name);

        WikidataDynamicObject human = new WikidataDynamicObject("Q1", "Human owner");
        human.type("Person");
        OwnedComponents.apply(model, List.of(human), null, null);
        RecordingApi api = new RecordingApi();
        api.entity("Q1", "Human owner", Map.of("P735", List.of("Q11")))
                .entity("Q11", "Human name part");

        ReferentFieldLoad.apply(model, List.of(human), api, null);

        assertEquals(Set.of("P735"), api.everyClaimPid,
                "a field with no property is not requested under a blank one");
        assertEquals("Human name part", ((WikidataDynamicObject) ((WikidataDynamicObject)
                human.get("personalName")).get("primaryPart")).getDisplayName());
    }

    @Test void firstKindEvidenceFetchRetainsThePossibleKindAndOwnedFieldClosure() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        GeneratedFieldModel nomineeType = nominee.addField(
                "type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        nomineeType.mapping().propertyPid("P31");
        nomineeType.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("birthDate", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().propertyPid("P569");
        GeneratedFieldModel name = person.addField(
                "name", FieldType.ENTITY, FieldCardinality.SINGLE);
        name.entityClassName("Name");
        name.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel nameClass = new GeneratedClassModel("Name");
        nameClass.ownedClass(true);
        GeneratedFieldModel familyName = nameClass.addField(
                "familyName", FieldType.ENTITY, FieldCardinality.SINGLE);
        familyName.mapping().propertyPid("P734");
        familyName.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.rootClass(nomination);
        model.addClass(nominee);
        model.addClass(person);
        model.addClass(nameClass);
        model.addEntityKindRule(new wikidata.explore.model.EntityKindRule(
                "Person", List.of("Q5")));

        WikidataDynamicObject candidate = new WikidataDynamicObject("Q42", "Adams");
        candidate.type("Nominee");
        RecordingApi api = new RecordingApi();
        api.entity("Q42", "Adams", Map.of("P31", List.of("Q5")));

        assertEquals(1, ReferentFieldLoad.apply(model, List.of(candidate), api, null));
        assertEquals(java.util.Set.of("P31", "P569", "P734"),
                new java.util.LinkedHashSet<>(api.loadedPids),
                "the first claims response banks the known downstream property closure");
    }

    @Test void loadsFieldsForEveryDirectRoleOfASharedReferent() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        nomination.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        model.addClass(nomination);

        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        GeneratedFieldModel occupation = nominee.addField(
                "occupation", FieldType.ENTITY, FieldCardinality.COLLECTION);
        occupation.mapping().propertyPid("P106");
        occupation.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.addClass(nominee);
        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        GeneratedFieldModel genre = forWork.addField(
                "genre", FieldType.ENTITY, FieldCardinality.COLLECTION);
        genre.mapping().propertyPid("P136");
        genre.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.addClass(forWork);
        model.rootClass(nomination);

        WikidataDynamicObject shared = new WikidataDynamicObject("Q42", "Shared entity");
        shared.type("Nominee");
        shared.assignClass("ForWork");
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q42", "Shared entity", Map.of(
                        "P106", List.of("Q33999"), "P136", List.of("Q130232")))
                .entity("Q33999", "actor")
                .entity("Q130232", "drama film");

        assertEquals(2, ReferentFieldLoad.apply(model, List.of(shared), api, null));
        assertEquals("actor", ((WikidataDynamicObject)
                ((List<?>) shared.get("occupation")).get(0)).getDisplayName());
        assertEquals("drama film", ((WikidataDynamicObject)
                ((List<?>) shared.get("genre")).get(0)).getDisplayName());
    }

    /** ForWork is referenced-only (Nomination.forWork targets it) with a declared
     *  genre (P136) field; a ForWork referent gets its genre loaded from P136. */
    @Test void loadsDeclaredPropertyFieldOntoReferencedClassReferents() {
        GeneratedProjectModel model = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        model.addClass(nom);

        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        GeneratedFieldModel genre =
                forWork.addField("genre", FieldType.ENTITY, FieldCardinality.COLLECTION);
        genre.mapping().propertyPid("P136");
        genre.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        model.addClass(forWork);
        model.rootClass(nom);

        WikidataDynamicObject work = new WikidataDynamicObject("Q7", "The Iron Lady");
        work.type("ForWork");   // already class-stamped, as after ReferentClassStamp

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q7", "The Iron Lady", Map.of("P136", List.of("Q130232")))
                .entity("Q130232", "drama film");

        int loaded = ReferentFieldLoad.apply(model, List.of(work), api, null);

        assertEquals(1, loaded);
        Object genreVal = work.get("genre");
        assertInstanceOf(List.class, genreVal);   // COLLECTION -> a list
        assertEquals("drama film",
                ((WikidataDynamicObject) ((List<?>) genreVal).get(0)).getDisplayName());
    }

    /** A DATE property-field on a referenced class loads as a FlexibleDate from the
     *  statement's literal value — e.g. a Ceremony's year/date (P585). */
    @Test void loadsADateLiteralFieldOntoReferents() {
        GeneratedProjectModel model = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("ceremony", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Ceremony");
        model.addClass(nom);

        GeneratedClassModel ceremonyClass = new GeneratedClassModel("Ceremony");
        GeneratedFieldModel year =
                ceremonyClass.addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().propertyPid("P585");
        model.addClass(ceremonyClass);
        model.rootClass(nom);

        WikidataDynamicObject ceremony =
                new WikidataDynamicObject("Q100", "97th Academy Awards");
        ceremony.type("Ceremony");

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .statement("Q100", "P585", "Q100$s1", "+2024-03-10T00:00:00Z", Map.of());

        int loaded = ReferentFieldLoad.apply(model, List.of(ceremony), api, null);

        assertEquals(1, loaded);
        Object y = ceremony.get("year");
        assertInstanceOf(aux.FlexibleDate.class, y);
        assertEquals(2024, ((aux.FlexibleDate) y).getYear());
    }

    @Test void siblingLiteralFieldsShareOneClaimsLoad() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.addField("ceremony", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Ceremony");
        GeneratedClassModel ceremonyClass = new GeneratedClassModel("Ceremony");
        ceremonyClass.addField("date", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().propertyPid("P585");
        ceremonyClass.addField("officialName", FieldType.STRING, FieldCardinality.SINGLE)
                .mapping().propertyPid("P1448");
        model.rootClass(nomination);
        model.addClass(ceremonyClass);

        WikidataDynamicObject ceremony =
                new WikidataDynamicObject("Q100", "97th Academy Awards");
        ceremony.type("Ceremony");
        RecordingApi api = new RecordingApi();
        api.statement("Q100", "P585", "Q100$s1", "+2025-03-02T00:00:00Z", Map.of())
                .statement("Q100", "P1448", "Q100$s2", "Academy Awards", Map.of());

        assertEquals(2, ReferentFieldLoad.apply(model, List.of(ceremony), api, null));
        assertEquals(1, api.literalLoads);
        assertEquals(java.util.Set.of("P585", "P1448"),
                new java.util.LinkedHashSet<>(api.loadedLiteralPids));
        assertEquals("Academy Awards", ceremony.get("officialName"));
    }

    @Test void directMonolingualLiteralUsesTheSameLanguageSelectionAsAQualifier() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel personClass = new GeneratedClassModel("Person");
        GeneratedFieldModel motto = personClass.addField(
                "motto", FieldType.TEXT, FieldCardinality.SINGLE);
        motto.mapping().propertyPid("P1451");
        motto.mapping().valueLanguage("Q1860");
        model.rootClass(personClass);

        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Person");
        person.type("Person");
        RecordingApi api = new RecordingApi();
        api.statement("Q1", "P1451", "Q1$untagged", "fallback", Map.of())
                .statement("Q1", "P1451", "Q1$sv",
                        wikidata.MonolingualTextCodec.encode("svensk", "sv"), Map.of())
                .statement("Q1", "P1451", "Q1$en",
                        wikidata.MonolingualTextCodec.encode("English", "en"), Map.of());

        assertEquals(1, ReferentFieldLoad.apply(model, List.of(person), api, null));
        assertEquals("English", person.get("motto"),
                "Q1860 and en are the same request, and exact beats untagged");
    }

    /** A referent that exists ONLY nested inside another record (never a top-level
     *  pool entry) is still found and loaded — e.g. a Ceremony as a Nomination's P805
     *  qualifier value. The pool passed in contains only the Nomination. */
    @Test void loadsOntoAReferentReachableOnlyThroughANestedField() {
        GeneratedProjectModel model = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("ceremony", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Ceremony");
        model.addClass(nom);

        GeneratedClassModel ceremonyClass = new GeneratedClassModel("Ceremony");
        ceremonyClass.addField("year", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().propertyPid("P585");
        model.addClass(ceremonyClass);
        model.rootClass(nom);

        // The ceremony is stamped and nested inside the nomination, but NOT a member
        // of the pool the caller passes (mirrors a qualifier-only referent).
        WikidataDynamicObject ceremony =
                new WikidataDynamicObject("Q100", "97th Academy Awards");
        ceremony.type("Ceremony");
        WikidataDynamicObject nomination =
                new WikidataDynamicObject("Q900$stmt", "The Brutalist — Best Picture");
        nomination.type("Nomination");
        nomination.put("ceremony", ceremony);

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .statement("Q100", "P585", "Q100$s1", "+2025-03-02T00:00:00Z", Map.of());

        int loaded = ReferentFieldLoad.apply(
                model, List.of(nomination), api, null);   // pool = Nomination only

        assertEquals(1, loaded);
        assertInstanceOf(aux.FlexibleDate.class, ceremony.get("year"));
        assertEquals(2025, ((aux.FlexibleDate) ceremony.get("year")).getYear());
    }

    /** No declared property-fields on the referenced class -> nothing loads. */
    @Test void doesNothingWhenTheReferencedClassHasNoPropertyFields() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        model.addClass(nom);
        model.addClass(new GeneratedClassModel("ForWork"));   // bare, no fields
        model.rootClass(nom);

        WikidataDynamicObject work = new WikidataDynamicObject("Q7", "The Iron Lady");
        work.type("ForWork");
        FakeWikidataApiClient api = new FakeWikidataApiClient().entity("Q7", "The Iron Lady");

        assertEquals(0, ReferentFieldLoad.apply(model, List.of(work), api, null));
        assertNull(work.get("genre"));
    }
}
