package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.EvidenceStatus;
import org.junit.jupiter.api.Test;
import wikidata.explore.query.core.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikipediaTextEvidenceProviderTest {
    private static final String ENTITY = """
            {"entities":{"Q157058":{
              "labels":{"en":{"language":"en","value":"Blood Diamond"}},
              "sitelinks":{"enwiki":{"title":"Blood Diamond"}},"claims":{}}}}
            """;
    private static final String ARTICLE = """
            {"query":{"pages":[{"pageid":123,"title":"Blood Diamond",
              "lastrevid":987654,"extract":"Blood Diamond is a film.\\nThe story follows a fisherman during the Sierra Leone Civil War.\\nReception"}]}}
            """;
    private static final String ARTICLE_WITH_CATEGORIES = """
            {"query":{"pages":[{"pageid":123,"title":"Blood Diamond",
              "lastrevid":987654,"extract":"Blood Diamond is a film.",
              "categories":[{"ns":14,"title":"Category:Films set in Sierra Leone"},
                {"ns":14,"title":"Category:Films set in 1999"}]}]}}
            """;
    private static final String SIERRA_LEONE = """
            {"entities":{"Q1044":{"labels":{"en":{"value":"Sierra Leone"}},
              "descriptions":{"en":{"value":"country in West Africa"}},
              "claims":{"P31":[{"rank":"normal","mainsnak":{"snaktype":"value",
                "property":"P31","datatype":"wikibase-item","datavalue":{"type":
                "wikibase-entityid","value":{"entity-type":"item","id":"Q6256"}}}}]}}}}
            """;
    private static final String YEAR_1999 = """
            {"entities":{"Q2470":{"labels":{"en":{"value":"1999"}},
              "descriptions":{"en":{"value":"year"}},
              "claims":{"P31":[{"rank":"normal","mainsnak":{"snaktype":"value",
                "property":"P31","datatype":"wikibase-item","datavalue":{"type":
                "wikibase-entityid","value":{"entity-type":"item","id":"Q3186692"}}}}]}}}}
            """;
    private static final String NAMELESS_THING = """
            {"entities":{"Q9999":{"labels":{"en":{"value":"Somewhere"}},"claims":{}}}}
            """;

    /** Answers each category value with its own entity, so a year and a country are told
     *  apart the way the API tells them apart — by their claims. */
    private static WikipediaTextEvidenceProvider categoryProvider(
            wikidata.explore.model.EntityKindRule referentKind, String valueEntity) {
        wikidata.explore.model.WikipediaCategoryRule rule =
                new wikidata.explore.model.WikipediaCategoryRule();
        rule.pattern("Films set in <value>");
        return new WikipediaTextEvidenceProvider(uri -> {
            String query = uri.getQuery() == null ? "" : uri.getQuery();
            if (uri.getHost().contains("wikidata") && query.contains("sites=enwiki")) {
                if (query.contains("titles=1999")) return valueEntity;
                return SIERRA_LEONE;
            }
            return uri.getHost().contains("wikidata") ? ENTITY : ARTICLE_WITH_CATEGORIES;
        }, rule, referentKind);
    }

    private static objectview.field.FieldRef locationField() {
        return objectview.field.FieldRef.described(
                "location", objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, "List<Location>", true, true,
                "Location", false, false, false, false, "", false);
    }

    private static EnrichmentRequest locationRequest() {
        return new EnrichmentRequest(
                new EnrichmentProposal.Subject("Movie", "movie-17", "Q157058", "Blood Diamond"),
                "location", true, List.of(), locationField(), List.of());
    }

    /**
     * A pattern matches by title alone, so "Films set in 1999" is as good a match for
     * {@code Films set in <value>} as "Films set in Sierra Leone" — and the year resolves
     * to a real entity, which was then offered as a Location. Review caught it every time,
     * which was the problem: it was noise a reviewer rejected on every single run.
     *
     * <p>What refuses it is the model's OWN kind rule for the class the field refers to —
     * the same declaration, read the same way, that decides an entity's kind during
     * generation. Nothing infers anything from the field being called location.
     */
    @Test
    void aCategoryValueOfTheWrongKindForTheFieldIsNotOffered() throws Exception {
        wikidata.explore.model.EntityKindRule places =
                new wikidata.explore.model.EntityKindRule("Location", List.of("Q6256"));

        EnrichmentProposal result = categoryProvider(places, YEAR_1999)
                .discover(locationRequest()).execute(new QueryContext(null, null));

        assertEquals(1, result.fields().size(),
                "the year matched the pattern honestly but is not a Location");
        var place = (objectview.Viewable) result.fields().get(0).proposedValue();
        assertEquals("Q1044", place.getIdentifier());
    }

    /**
     * "Cannot confirm" is not "fits". An entity with no evidence for the kind property is
     * left out, matching what generation does with a candidate it cannot classify — the
     * alternative admits every unclassified entity through the widest possible door.
     */
    @Test
    void aCategoryValueWithNoKindEvidenceIsNotOffered() throws Exception {
        wikidata.explore.model.EntityKindRule places =
                new wikidata.explore.model.EntityKindRule("Location", List.of("Q6256"));

        EnrichmentProposal result = categoryProvider(places, NAMELESS_THING)
                .discover(locationRequest()).execute(new QueryContext(null, null));

        assertEquals(1, result.fields().size());
        assertEquals("Q1044", ((objectview.Viewable) result.fields().get(0)
                .proposedValue()).getIdentifier());
    }

    /**
     * A class the model declares no kind rule for admits everything, exactly as before.
     * The check is only ever as good as what the model says, and that is the one place to
     * improve it — not a heuristic that guesses when the model is silent.
     */
    @Test
    void aClassTheModelSaysNothingAboutStillAdmitsEveryCandidate() throws Exception {
        EnrichmentProposal result = categoryProvider(null, YEAR_1999)
                .discover(locationRequest()).execute(new QueryContext(null, null));

        assertEquals(2, result.fields().size());
    }

    @Test
    void exactMentionProducesVersionedReviewEvidence() throws Exception {
        WikipediaTextEvidenceProvider provider = provider();
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Movie", "movie-17", "Q157058", "Blood Diamond"),
                "location", false, List.of(), null, "Sierra Leone");

        EnrichmentProposal result = provider.discover(request)
                .execute(new QueryContext(null, null));

        assertEquals(1, result.fields().size());
        EnrichmentProposal.FieldCandidate field = result.fields().get(0);
        assertEquals("Sierra Leone", field.proposedValue());
        assertEquals(EnrichmentProposal.ReviewAction.CORROBORATE, field.suggestedAction());
        assertEquals("field:location", field.source().propertyId());
        assertEquals(1, field.evidence().size());
        var claim = field.evidence().get(0);
        assertTrue(claim.evidence().get(0).excerpt().contains("Sierra Leone Civil War"));
        assertTrue(claim.evidence().get(0).document().versionId()
                .startsWith("revision:987654;content:sha256:"));
        assertEquals(EvidenceStatus.CURRENT, claim.statusAgainst(
                claim.evidence().get(0).document(),
                WikipediaTextEvidenceProvider.RECIPE_VERSION, ""));
        assertFalse(claim.claimId().isBlank());
    }

    @Test
    void absenceIsAnHonestEmptyProposal() throws Exception {
        WikipediaTextEvidenceProvider provider = provider();
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Movie", "movie-17", "Q157058", "Blood Diamond"),
                "location", false, List.of(), null, "Budapest");

        EnrichmentProposal result = provider.discover(request)
                .execute(new QueryContext(null, null));

        assertTrue(result.fields().isEmpty());
    }

    @Test
    void filmsSetInCategoryDiscoversAReferenceForTheLocationField() throws Exception {
        // The relation comes from the model, never from the field being called location:
        // a category means something for a field because the domain says so.
        wikidata.explore.model.WikipediaCategoryRule rule =
                new wikidata.explore.model.WikipediaCategoryRule();
        rule.pattern("Films set in <value>");
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(uri -> {
            if (uri.getHost().contains("wikidata") && uri.getQuery().contains("sites=enwiki")) {
                return SIERRA_LEONE;
            }
            return uri.getHost().contains("wikidata") ? ENTITY : ARTICLE_WITH_CATEGORIES;
        }, rule);
        objectview.field.FieldRef location = objectview.field.FieldRef.described(
                "location", objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, "List<Location>", true, true,
                "Location", false, false, false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Movie", "movie-17", "Q157058", "Blood Diamond"),
                "location", true, List.of(), location, List.of());

        EnrichmentProposal result = provider.discover(request)
                .execute(new QueryContext(null, null));

        // Both categories match the declared pattern, including "Films set in 1999" —
        // the rule says what a category means, and a pattern that also admits a year is
        // a modelling fact the reviewer sees rather than a year exception buried in code.
        assertEquals(2, result.fields().size());
        var candidate = result.fields().get(0);
        assertEquals(EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                candidate.suggestedAction());
        var place = (objectview.Viewable) candidate.proposedValue();
        assertEquals("Q1044", place.getIdentifier());
        assertEquals("Location", place.typeName());
        assertTrue(candidate.evidence().get(0).evidence().get(0).excerpt()
                .contains("Films set in Sierra Leone"));
    }

    /**
     * Without a configured relation there is nothing to read: the same article and the
     * same field name yield no candidate. A rule that lived in code as "location means
     * Films set in" would have inferred a relation from a name, in every domain that
     * happens to call a field location.
     */
    @Test
    void aCategoryMeansNothingForAFieldThatDeclaresNoRelation() throws Exception {
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(uri -> {
            if (uri.getHost().contains("wikidata") && uri.getQuery().contains("sites=enwiki")) {
                return SIERRA_LEONE;
            }
            return uri.getHost().contains("wikidata") ? ENTITY : ARTICLE_WITH_CATEGORIES;
        });
        objectview.field.FieldRef location = objectview.field.FieldRef.described(
                "location", objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, "List<Location>", true, true,
                "Location", false, false, false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Movie", "movie-17", "Q157058", "Blood Diamond"),
                "location", true, List.of(), location, List.of());

        EnrichmentProposal result = provider.discover(request)
                .execute(new QueryContext(null, null));

        assertTrue(result.fields().isEmpty());
    }

    /**
     * A date is a value like any other here, and the provider must not decide otherwise
     * on its own: it kept a second list of accepted types, FlexibleDate was not on it,
     * and every date-valued field went uncorroborated with nothing said. The claim owns
     * that rule now, and a date carries its canonical form into the lineage.
     */
    @Test
    void corroboratesADateValueTheClaimCanCarry() throws Exception {
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(uri ->
                uri.getHost().contains("wikidata") ? ENTITY : """
                    {"query":{"pages":[{"pageid":123,"title":"Blood Diamond",
                      "lastrevid":987654,"extract":"Released on 2006-12-08 in the US."}]}}
                    """);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Movie", "movie-17", "Q157058", "Blood Diamond"),
                "publicationDate", false, List.of(), null,
                aux.FlexibleDate.parse("2006-12-08"));

        EnrichmentProposal result = provider.discover(request)
                .execute(new QueryContext(null, null));

        assertEquals(1, result.fields().size(), "a date mention corroborates like any other");
        var claim = result.fields().get(0).evidence().get(0);
        assertFalse(claim.claimId().isBlank(),
                "and its identity is built from the date's canonical form");
    }

    @Test
    void rejectsMissingValuesAndSubjectsWithoutAWikidataIdentity() {
        WikipediaTextEvidenceProvider provider = provider();
        assertFalse(provider.supports(new EnrichmentRequest(
                new EnrichmentProposal.Subject("Movie", "local", "", "Movie"),
                "location", false, List.of(), null, "Paris")));
        assertFalse(provider.supports(new EnrichmentRequest(
                new EnrichmentProposal.Subject("Movie", "local", "Q157058", "Movie"),
                "location", false, List.of(), null, null)));
    }

    @Test
    void mentionSearchDoesNotMatchInsideAnotherWord() {
        assertEquals(-1, WikipediaTextEvidenceProvider.findMention("A comparison follows", "Paris"));
        assertEquals(2, WikipediaTextEvidenceProvider.findMention("A Paris story", "Paris"));
    }

    private static WikipediaTextEvidenceProvider provider() {
        return new WikipediaTextEvidenceProvider(uri ->
                uri.getHost().contains("wikidata") ? ENTITY : ARTICLE);
    }
}
