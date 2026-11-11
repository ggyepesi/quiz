package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.EvidenceStatus;
import org.junit.jupiter.api.Test;
import work.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    private static final String FALCON = """
            {"entities":{"Q43489":{"labels":{"en":{"value":"falcon"}},"claims":{}}}}
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
                .discover(locationRequest()).execute(new QueryContext());

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
                .discover(locationRequest()).execute(new QueryContext());

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
                .discover(locationRequest()).execute(new QueryContext());

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
                .execute(new QueryContext());

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
    void retainedIntroductionProposesOnlyLoadedTypedCandidatesWithoutRefetching()
            throws Exception {
        var achilles = new wikidata.explore.extract.WikidataDynamicObject(
                "Q41746", "Achilles");
        var odysseus = new wikidata.explore.extract.WikidataDynamicObject(
                "Q472091", "Odysseus");
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(
                uri -> { throw new AssertionError("retained evidence must not be refetched"); },
                null, null, List.of(achilles, odysseus));
        String text = "Achilles pursues Hector after the death of Patroclus.";
        var introduction = new datasource.evidence.ArticleIntroduction(text,
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Achilles in the Trojan War", "42", text, List.of()));
        objectview.field.FieldRef characters = objectview.field.FieldRef.described(
                "characters", objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, "List<Character>", true, true,
                "Character", false, false, false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Episode", "Q999", "Q999", "Achilles in the Trojan War"),
                "characters", true, List.of(), characters, List.of(), List.of(),
                introduction);

        EnrichmentProposal result = provider.discover(request).execute(new QueryContext());

        assertEquals(1, result.fields().size());
        var candidate = result.fields().get(0);
        assertSame(achilles, candidate.proposedValue(),
                "the proposal reuses the domain's typed Character instance");
        assertEquals(EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                candidate.suggestedAction());
        assertEquals("42", candidate.evidence().get(0).evidence().get(0)
                .document().revision());
        assertTrue(candidate.evidence().get(0).extractionMethod().contains("loaded"));
    }

    @Test void anAmbiguousUnlinkedLabelCorroboratesCurrentButNominatesNoNamesake()
            throws Exception {
        var greekHero = new wikidata.explore.extract.WikidataDynamicObject(
                "Q41746", "Achilles");
        var namesake = new wikidata.explore.extract.WikidataDynamicObject(
                "Q21547790", "Achilles");
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(
                uri -> { throw new AssertionError("unlinked retained evidence needs no lookup"); },
                null, null, List.of(greekHero, namesake));
        String text = "Achilles was hidden on Skyros.";
        var introduction = new datasource.evidence.ArticleIntroduction(text,
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Achilles on Skyros", "42", text, List.of()));

        EnrichmentProposal result = provider.discover(
                        charactersRequest(introduction, List.of(greekHero)))
                .execute(new QueryContext());

        assertEquals(1, result.fields().size());
        assertSame(greekHero, result.fields().getFirst().proposedValue());
        assertEquals(EnrichmentProposal.ReviewAction.CORROBORATE,
                result.fields().getFirst().suggestedAction());
    }

    @Test void anArticleLinkResolvesAnAmbiguousLabelToItsWikidataIdentity()
            throws Exception {
        var greekHero = new wikidata.explore.extract.WikidataDynamicObject(
                "Q41746", "Achilles");
        greekHero.enwikiTitle("Achilles");
        var namesake = new wikidata.explore.extract.WikidataDynamicObject(
                "Q21547790", "Achilles");
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(
                uri -> { throw new AssertionError("retained correspondence avoids lookup"); },
                null, null, List.of(greekHero, namesake));
        String text = "Achilles was hidden on Skyros.";
        var link = new datasource.evidence.ArticleIntroduction.Link(
                "Achilles", "Achilles", 0, "Achilles".length());
        var introduction = new datasource.evidence.ArticleIntroduction(text,
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Achilles on Skyros", "42", text, List.of(), List.of(link)),
                List.of(link));

        EnrichmentProposal result = provider.discover(charactersRequest(introduction))
                .execute(new QueryContext());

        assertEquals(1, result.fields().size());
        assertSame(greekHero, result.fields().getFirst().proposedValue());
        assertTrue(result.fields().getFirst().evidence().getFirst()
                .extractionMethod().contains("linked"));
    }

    @Test void unrelatedLinksNeitherNominateByTargetNorTriggerTitleLookups()
            throws Exception {
        var ra = new wikidata.explore.extract.WikidataDynamicObject("Q2831", "Ra");
        ra.enwikiTitle("Ra");
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(
                uri -> {
                    requests.incrementAndGet();
                    return "{}";
                }, null, null, List.of(ra));
        String text = "Her father ruled Egypt.";
        var links = List.of(
                new datasource.evidence.ArticleIntroduction.Link(
                        "Her father", "Ra", 0, 10),
                new datasource.evidence.ArticleIntroduction.Link(
                        "Egypt", "Egypt", 17, 22));
        var introduction = new datasource.evidence.ArticleIntroduction(text,
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "A deity", "42", text, List.of(), links), links);

        EnrichmentProposal result = provider.discover(charactersRequest(introduction))
                .execute(new QueryContext());

        assertTrue(result.fields().isEmpty(),
                "an introduction link identifies an exact visible candidate label; "
                        + "it does not interpret 'Her father' as Ra");
        assertEquals(0, requests.get(),
                "links whose visible text names no loaded candidate are never resolved");
    }

    /** Full-article prose stays prose. The generic operation keeps bounded content with
     * exact section evidence and does not turn links into domain entities. */
    @Test void articlePassagesProposeBoundedContentAndOmitMaintenanceSections()
            throws Exception {
        String source = """
                {"entities":{"Q9000":{"labels":{"en":{"value":"Achilles"}},
                  "sitelinks":{"enwiki":{"title":"Achilles"}},"claims":{}}}}
                """;
        String article = """
                {"query":{"pages":[{"pageid":12,"title":"Achilles","lastrevid":77,
                  "extract":"<p>Achilles was a hero of the Trojan War.</p><h2>Myths</h2><p>He was hidden on Skyros.</p><p>Odysseus discovered him.</p><h2>References</h2><p>This citation list is not narrative content.</p>"}]}}
                """;
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(uri -> {
            if (uri.getHost().contains("wikipedia")) return article;
            return source;
        }, null, null, List.of(),
                WikipediaTextEvidenceProvider.CandidateEvidenceMode
                        .ARTICLE_PASSAGES);
        objectview.field.FieldRef field = objectview.field.FieldRef.described(
                "narrativeText", objectview.field.FieldKind.TEXT,
                objectview.field.FieldKind.TEXT, "String", false, false,
                "", false, false, false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Character", "Q9000", "Q9000", "Achilles"),
                "narrativeText", false, List.of(), field, null);

        EnrichmentProposal result = provider.discover(request).execute(new QueryContext());

        assertEquals(1, result.fields().size());
        var candidate = result.fields().getFirst();
        String proposed = (String) candidate.proposedValue();
        assertTrue(proposed.contains("Achilles was a hero"));
        assertTrue(proposed.contains("Myths\nHe was hidden on Skyros."));
        assertFalse(proposed.contains("citation list"));
        assertEquals(List.of("Introduction", "Myths", "Myths"),
                candidate.evidence().getFirst().evidence().stream()
                        .map(datasource.evidence.EvidenceFragment::section).toList());
        assertEquals("bounded-article-passages-v1",
                candidate.evidence().getFirst().recipeVersion());
        assertEquals("77", candidate.evidence().getFirst().evidence().getFirst()
                .document().revision());
        assertEquals(EnrichmentProposal.ReviewAction.FILL_IF_EMPTY,
                candidate.suggestedAction());
    }

    @Test void articlePassageBoundsArePartOfTheGenericOperation() {
        String html = java.util.stream.IntStream.range(0, 60)
                .mapToObj(index -> "<p>Passage " + index + " " + "x".repeat(700) + "</p>")
                .collect(java.util.stream.Collectors.joining());
        wikipedia.WikipediaArticleClient.ParsedArticleText parsed =
                wikipedia.WikipediaArticleClient.parseArticleText(html);
        var document = wikipedia.WikipediaArticleClient.sourceDocument(
                "Bounded article", "5", parsed.text(), List.of(), parsed.links());
        var article = new wikipedia.WikipediaArticleClient.Article(document, parsed.text(),
                List.of(), parsed.links(), parsed.sections());

        WikipediaTextEvidenceProvider.ArticlePassages passages =
                WikipediaTextEvidenceProvider.articlePassages(article);

        assertTrue(passages.passages().size()
                <= datasource.wikipedia.WikipediaDatasourceProvider.MAX_ARTICLE_PASSAGES);
        assertTrue(passages.text().length()
                <= datasource.wikipedia.WikipediaDatasourceProvider
                        .MAX_ARTICLE_PASSAGE_CHARACTERS);
        for (WikipediaTextEvidenceProvider.Passage passage : passages.passages()) {
            assertEquals(passage.text(), article.text().substring(passage.start(),
                    passage.start() + passage.text().length()));
        }
    }

    @Test void articlePassagesCanProposeTypedInstancesWithExplicitFieldMappings()
            throws Exception {
        String source = """
                {"entities":{"Q9000":{"labels":{"en":{"value":"Achilles"}},
                  "sitelinks":{"enwiki":{"title":"Achilles"}},"claims":{}}}}
                """;
        String article = """
                {"query":{"pages":[{"pageid":12,"title":"Achilles","lastrevid":77,
                  "extract":"<p>Achilles was a hero of the Trojan War.</p><h2>Myths</h2><p>He was hidden on Skyros.</p>"}]}}
                """;
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(uri -> {
            if (uri.getHost().contains("wikipedia")) return article;
            return source;
        }, null, null, List.of(),
                WikipediaTextEvidenceProvider.CandidateEvidenceMode
                        .ARTICLE_PASSAGE_INSTANCES,
                new datasource.wikipedia.WikipediaDatasourceProvider
                        .ArticlePassageInstanceSpec("summary", "characters"));
        objectview.field.FieldRef episodes = objectview.field.FieldRef.described(
                "episodes", objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, "List<Episode>", true, true,
                "Episode", false, false, false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Character", "Q9000", "Q9000", "Achilles"),
                "episodes", true, List.of(), episodes, List.of());

        EnrichmentProposal result = provider.discover(request).execute(new QueryContext());

        assertEquals(2, result.instances().size());
        assertEquals(2, result.fields().size());
        for (int i = 0; i < result.instances().size(); i++) {
            var instance = result.instances().get(i);
            assertEquals("Episode", instance.type());
            assertEquals("summary", instance.fields().get(0).field());
            assertTrue(String.valueOf(instance.fields().get(0).value()).length() > 10);
            assertEquals("characters", instance.fields().get(1).field());
            assertEquals(List.of(new EnrichmentProposal.TypedReference(
                    "Character", "Q9000")), instance.fields().get(1).references());
            assertEquals(instance.id(), ((objectview.Viewable) result.fields().get(i)
                    .proposedValue()).getIdentifier());
            assertEquals("article-passage-instances-v1",
                    instance.evidence().getFirst().recipeVersion());
        }

        EnrichmentRequest repeated = new EnrichmentRequest(request.subject(),
                "episodes", true, List.of(), episodes,
                List.of(result.fields().getFirst().proposedValue()));
        EnrichmentProposal remaining = provider.discover(repeated)
                .execute(new QueryContext());
        assertEquals(1, remaining.instances().size(),
                "an already accepted stable passage instance is not proposed again");
    }

    /** A link is identity evidence and wins over the label it carries. Here "Achilles"
     * links to the ship; the one loaded Achilles is the hero, so the unique-label pass
     * would have nominated the hero for a sentence about the ship. */
    @Test void aLinkToAnotherEntityIsNotOverruledByAUniqueLoadedLabel() throws Exception {
        var greekHero = new wikidata.explore.extract.WikidataDynamicObject(
                "Q41746", "Achilles");
        greekHero.enwikiTitle("Achilles");
        String ship = """
                {"entities":{"Q2823105":{"labels":{"en":{"value":"HMS Achilles"}},
                  "sitelinks":{"enwiki":{"title":"HMS Achilles (1863)"}},"claims":{}}}}
                """;
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(
                uri -> ship, null, null, List.of(greekHero));
        String text = "Achilles was launched in 1863.";
        var link = new datasource.evidence.ArticleIntroduction.Link(
                "Achilles", "HMS Achilles (1863)", 0, "Achilles".length());
        var introduction = new datasource.evidence.ArticleIntroduction(text,
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Warrior-class ironclad", "42", text, List.of(), List.of(link)),
                List.of(link));

        EnrichmentProposal result = provider.discover(charactersRequest(introduction))
                .execute(new QueryContext());

        assertTrue(result.fields().isEmpty(), result.fields().toString());
    }

    /** An accepted passage stays accepted after an unrelated edit. Its id was cut from the
     * claim id, which hashes the revision and the offset: lengthening the introduction gave
     * the unchanged Myths passage a new id, and the next review proposed it again. */
    @Test void aPassageKeepsItsIdentityWhenAnotherPartOfTheArticleChanges() throws Exception {
        EnrichmentProposal before = passageInstances("77",
                "<p>Achilles was a hero of the Trojan War.</p>");
        EnrichmentProposal after = passageInstances("78",
                "<p>Achilles was a hero of the Trojan War, son of Peleus and Thetis.</p>");

        String mythsBefore = before.instances().get(1).id();
        String mythsAfter = after.instances().get(1).id();
        assertEquals(mythsBefore, mythsAfter,
                "the unchanged passage is the same instance in the next revision");
        assertFalse(before.instances().get(0).id().equals(after.instances().get(0).id()),
                "an edited passage is a different passage");
    }

    private static EnrichmentProposal passageInstances(String revision, String introduction)
            throws Exception {
        String source = """
                {"entities":{"Q9000":{"labels":{"en":{"value":"Achilles"}},
                  "sitelinks":{"enwiki":{"title":"Achilles"}},"claims":{}}}}
                """;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String article = "{\"query\":{\"pages\":[{\"pageid\":12,\"title\":\"Achilles\","
                + "\"lastrevid\":" + revision + ",\"extract\":" + json.valueToTree(
                        introduction + "<h2>Myths</h2><p>He was hidden on Skyros.</p>")
                + "}]}}";
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(uri ->
                uri.getHost().contains("wikipedia") ? article : source,
                null, null, List.of(),
                WikipediaTextEvidenceProvider.CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES,
                new datasource.wikipedia.WikipediaDatasourceProvider
                        .ArticlePassageInstanceSpec("summary", "characters"));
        objectview.field.FieldRef episodes = objectview.field.FieldRef.described(
                "episodes", objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, "List<Episode>", true, true,
                "Episode", false, false, false, false, "", false);
        return provider.discover(new EnrichmentRequest(
                new EnrichmentProposal.Subject("Character", "Q9000", "Q9000", "Achilles"),
                "episodes", true, List.of(), episodes, List.of()))
                .execute(new QueryContext());
    }

    private static EnrichmentRequest charactersRequest(
            datasource.evidence.ArticleIntroduction introduction) {
        return charactersRequest(introduction, List.of());
    }

    private static EnrichmentRequest charactersRequest(
            datasource.evidence.ArticleIntroduction introduction, List<?> current) {
        objectview.field.FieldRef characters = objectview.field.FieldRef.described(
                "characters", objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, "List<Character>", true, true,
                "Character", false, false, false, false, "", false);
        return new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Episode", "Q999", "Q999", "Achilles on Skyros"),
                "characters", true, List.of(), characters, current, List.of(),
                introduction);
    }

    @Test void retainedIntroductionDoesNotReplaceCategoryVersionEvidence() throws Exception {
        wikidata.explore.model.WikipediaCategoryRule rule =
                new wikidata.explore.model.WikipediaCategoryRule();
        rule.pattern("Films set in <value>");
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(
                uri -> SIERRA_LEONE, rule, null, List.of());
        String text = "Blood Diamond is a film.";
        var introduction = new datasource.evidence.ArticleIntroduction(text,
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Blood Diamond", "intro-42", text, List.of()));
        var categoryDocument = wikipedia.WikipediaArticleClient.sourceDocument(
                "Blood Diamond", "categories-99", "",
                List.of("Films set in Sierra Leone"));
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Movie", "movie-17", "Q157058", "Blood Diamond"),
                "location", true, List.of(), locationField(), List.of(),
                List.of(new datasource.evidence.CategoryMembership(
                        "Films set in Sierra Leone", categoryDocument)), introduction);

        EnrichmentProposal result = provider.discover(request).execute(new QueryContext());

        assertEquals("categories-99", result.fields().getFirst().evidence().getFirst()
                .evidence().getFirst().document().revision(),
                "category claims retain the category acquisition's own source version");
    }

    @Test
    void absenceIsAnHonestEmptyProposal() throws Exception {
        WikipediaTextEvidenceProvider provider = provider();
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "Movie", "movie-17", "Q157058", "Blood Diamond"),
                "location", false, List.of(), null, "Budapest");

        EnrichmentProposal result = provider.discover(request)
                .execute(new QueryContext());

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
                .execute(new QueryContext());

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

    @Test
    void anExactCategoryMappingUsesTheConfiguredQidWithoutInferringATitle()
            throws Exception {
        wikidata.explore.model.WikipediaCategoryRule rule =
                new wikidata.explore.model.WikipediaCategoryRule();
        rule.mappings().add(new wikidata.explore.model.WikipediaCategoryMapping(
                "Falcon deities", "Q43489"));
        WikipediaTextEvidenceProvider provider = new WikipediaTextEvidenceProvider(
                uri -> FALCON, rule);
        var document = wikipedia.WikipediaArticleClient.sourceDocument(
                "Horus", "category-17", "", List.of("Falcon deities"));
        objectview.field.FieldRef symbols = objectview.field.FieldRef.described(
                "iconographicSymbol", objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, "List<IconographicSymbol>",
                true, true, "IconographicSymbol", false, false, false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "EgyptianDeity", "Q841", "Q841", "Horus"),
                "iconographicSymbol", true, List.of(), symbols, List.of(),
                List.of(new datasource.evidence.CategoryMembership(
                        "Falcon deities", document)), null);

        EnrichmentProposal result = provider.discover(request).execute(new QueryContext());

        assertEquals(1, result.fields().size());
        objectview.Viewable falcon = (objectview.Viewable) result.fields().getFirst()
                .proposedValue();
        assertEquals("Q43489", falcon.getIdentifier());
        assertEquals("falcon", falcon.getDisplayName());
        assertTrue(result.fields().getFirst().evidence().getFirst().evidence().getFirst()
                .excerpt().contains("Falcon deities"));
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
                .execute(new QueryContext());

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
                .execute(new QueryContext());

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
