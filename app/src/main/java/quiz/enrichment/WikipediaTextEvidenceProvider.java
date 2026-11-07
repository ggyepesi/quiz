package quiz.enrichment;

import datasource.EntityRef;
import datasource.SourceRef;
import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.EvidenceFragment;
import datasource.evidence.ExtractedClaim;
import objectview.Viewable;
import wikidata.WikidataIds;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds configured textual evidence in the English Wikipedia article linked from a
 * subject's Wikidata entity. Exact mentions corroborate values already present; an
 * explicit article-passages recipe may instead propose bounded source prose as text. A
 * separately configured passage-instance recipe can offer one typed instance per passage,
 * with authored target-field mappings; review, never this provider, decides whether the
 * passage represents that instance.
 */
public final class WikipediaTextEvidenceProvider implements EnrichmentProvider {
    static final String RECIPE_VERSION = "exact-value-mention-v1";

    public enum CandidateEvidenceMode {
        NONE,
        LOADED_INTRODUCTION_MENTIONS,
        ARTICLE_PASSAGES,
        ARTICLE_PASSAGE_INSTANCES
    }

    private final WikimediaEntityLookup lookup;
    private final wikipedia.WikipediaArticleClient articles;
    private final wikidata.explore.model.WikipediaCategoryRule categoryRule;
    private final wikidata.explore.model.EntityKindRule referentKind;
    private final Map<String, List<MentionTerm>> mentionCandidates;
    private final CandidateEvidenceMode candidateEvidenceMode;
    private final datasource.wikipedia.WikipediaDatasourceProvider
            .ArticlePassageInstanceSpec passageInstanceSpec;

    public WikipediaTextEvidenceProvider() {
        this(WikimediaEntityLookup.defaultFetcher(), null, null, List.of(),
                CandidateEvidenceMode.NONE);
    }

    public WikipediaTextEvidenceProvider(
            wikidata.explore.model.WikipediaCategoryRule categoryRule) {
        this(WikimediaEntityLookup.defaultFetcher(), categoryRule, null, List.of(),
                CandidateEvidenceMode.NONE);
    }

    /** {@code referentKind} is the model's rule for the class the target field REFERS TO,
     *  or null when the model declares none for it. */
    public WikipediaTextEvidenceProvider(
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            wikidata.explore.model.EntityKindRule referentKind) {
        this(WikimediaEntityLookup.defaultFetcher(), categoryRule, referentKind, List.of(),
                CandidateEvidenceMode.NONE);
    }

    public WikipediaTextEvidenceProvider(
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            wikidata.explore.model.EntityKindRule referentKind,
            Collection<? extends Viewable> mentionCandidates) {
        this(WikimediaEntityLookup.defaultFetcher(), categoryRule, referentKind,
                mentionCandidates, CandidateEvidenceMode.LOADED_INTRODUCTION_MENTIONS);
    }

    public WikipediaTextEvidenceProvider(
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            wikidata.explore.model.EntityKindRule referentKind,
            Collection<? extends Viewable> mentionCandidates,
            CandidateEvidenceMode candidateEvidenceMode) {
        this(WikimediaEntityLookup.defaultFetcher(), categoryRule, referentKind,
                mentionCandidates, candidateEvidenceMode, null);
    }

    public WikipediaTextEvidenceProvider(
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            wikidata.explore.model.EntityKindRule referentKind,
            Collection<? extends Viewable> mentionCandidates,
            CandidateEvidenceMode candidateEvidenceMode,
            datasource.wikipedia.WikipediaDatasourceProvider.ArticlePassageInstanceSpec
                    passageInstanceSpec) {
        this(WikimediaEntityLookup.defaultFetcher(), categoryRule, referentKind,
                mentionCandidates, candidateEvidenceMode, passageInstanceSpec);
    }

    WikipediaTextEvidenceProvider(WikimediaEntityLookup.JsonFetcher fetcher) {
        this(fetcher, null, null, List.of(), CandidateEvidenceMode.NONE);
    }

    WikipediaTextEvidenceProvider(WikimediaEntityLookup.JsonFetcher fetcher,
            wikidata.explore.model.WikipediaCategoryRule categoryRule) {
        this(fetcher, categoryRule, null, List.of(), CandidateEvidenceMode.NONE);
    }

    WikipediaTextEvidenceProvider(WikimediaEntityLookup.JsonFetcher fetcher,
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            wikidata.explore.model.EntityKindRule referentKind) {
        this(fetcher, categoryRule, referentKind, List.of(), CandidateEvidenceMode.NONE);
    }

    WikipediaTextEvidenceProvider(WikimediaEntityLookup.JsonFetcher fetcher,
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            wikidata.explore.model.EntityKindRule referentKind,
            Collection<? extends Viewable> mentionCandidates) {
        this(fetcher, categoryRule, referentKind, mentionCandidates,
                CandidateEvidenceMode.LOADED_INTRODUCTION_MENTIONS);
    }

    WikipediaTextEvidenceProvider(WikimediaEntityLookup.JsonFetcher fetcher,
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            wikidata.explore.model.EntityKindRule referentKind,
            Collection<? extends Viewable> mentionCandidates,
            CandidateEvidenceMode candidateEvidenceMode) {
        this(fetcher, categoryRule, referentKind, mentionCandidates,
                candidateEvidenceMode, null);
    }

    WikipediaTextEvidenceProvider(WikimediaEntityLookup.JsonFetcher fetcher,
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            wikidata.explore.model.EntityKindRule referentKind,
            Collection<? extends Viewable> mentionCandidates,
            CandidateEvidenceMode candidateEvidenceMode,
            datasource.wikipedia.WikipediaDatasourceProvider.ArticlePassageInstanceSpec
                    passageInstanceSpec) {
        this.lookup = new WikimediaEntityLookup(fetcher);
        this.articles = new wikipedia.WikipediaArticleClient(fetcher::fetch);
        this.categoryRule = categoryRule == null ? null : categoryRule.copy();
        this.referentKind = referentKind == null ? null : referentKind.copy();
        this.candidateEvidenceMode = candidateEvidenceMode == null
                ? CandidateEvidenceMode.NONE : candidateEvidenceMode;
        this.passageInstanceSpec = passageInstanceSpec;
        java.util.LinkedHashMap<String, Viewable> unique = new java.util.LinkedHashMap<>();
        if (mentionCandidates != null) {
            for (Viewable candidate : mentionCandidates) {
                if (candidate == null || candidate.getIdentifier() == null
                        || !WikidataIds.isQid(candidate.getIdentifier())) continue;
                unique.putIfAbsent(candidate.getIdentifier(), candidate);
            }
        }
        java.util.LinkedHashMap<String, List<MentionTerm>> index = new java.util.LinkedHashMap<>();
        for (Viewable candidate : unique.values()) {
            String phrase = clean(candidate.getDisplayName());
            String first = firstWord(phrase);
            if (first.isBlank()) continue;
            index.computeIfAbsent(first.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(new MentionTerm(candidate, phrase,
                            candidate instanceof wikidata.explore.extract.WikidataDynamicObject w
                                    ? clean(w.enwikiTitle()) : ""));
        }
        java.util.LinkedHashMap<String, List<MentionTerm>> immutable =
                new java.util.LinkedHashMap<>();
        index.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        this.mentionCandidates = Map.copyOf(immutable);
    }

    @Override public String name() { return "Wikipedia text evidence"; }

    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.subject() != null
                && WikidataIds.isQid(request.subject().id())
                && request.subject().targetId() != null
                && !request.subject().targetId().isBlank()
                && request.targetField() != null && !request.targetField().isBlank()
                && (request.currentValue() != null
                    || (!request.categoryMemberships().isEmpty()
                        && categoryRule != null && categoryRule.configured())
                    || (request.articleIntroduction() != null
                        && candidateEvidenceMode
                            == CandidateEvidenceMode.LOADED_INTRODUCTION_MENTIONS
                        && !mentionCandidates.isEmpty())
                    || (candidateEvidenceMode == CandidateEvidenceMode.ARTICLE_PASSAGES
                        && passageTarget(request))
                    || (candidateEvidenceMode
                            == CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES
                        && passageInstanceTarget(request)))
                && (request.targetSchema() == null
                    || request.targetSchema().kind() != objectview.field.FieldKind.MEDIA);
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException(
                    "Wikipedia evidence requires a Wikidata identity and an existing value, "
                            + "acquired category memberships, configured loaded-target mentions, "
                            + "or configured bounded article passages");
        }
        return new Query<>() {
            @Override public String purpose() {
                return candidateEvidenceMode == CandidateEvidenceMode.ARTICLE_PASSAGES
                        || candidateEvidenceMode
                            == CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES
                        ? "Find bounded passages in a full Wikipedia article"
                        : "Find Wikipedia text evidence";
            }
            @Override public String skeleton() {
                return candidateEvidenceMode == CandidateEvidenceMode.ARTICLE_PASSAGES
                        || candidateEvidenceMode
                            == CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES
                        ? "Read one full English Wikipedia article; retain at most "
                                + datasource.wikipedia.WikipediaDatasourceProvider
                                        .MAX_ARTICLE_PASSAGES
                                + " passages and "
                                + datasource.wikipedia.WikipediaDatasourceProvider
                                        .MAX_ARTICLE_PASSAGE_CHARACTERS
                                + " characters"
                        : "Resolve the linked English article and find exact value mentions";
            }
            @Override public String queryType() { return "Wikipedia API"; }
            @Override public String description() {
                return candidateEvidenceMode == CandidateEvidenceMode.ARTICLE_PASSAGES
                        ? "Propose section-labelled article text for explicit review"
                        : candidateEvidenceMode
                            == CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES
                        ? "Propose one target-class instance per bounded passage"
                        : "Corroborate the current field value from versioned article text";
            }
            @Override public Map<String, String> parameters() {
                return Map.of("qid", request.subject().id(),
                        "field", request.targetField(),
                        "recipe", switch (candidateEvidenceMode) {
                            case ARTICLE_PASSAGES -> "bounded-article-passages-v1";
                            case ARTICLE_PASSAGE_INSTANCES ->
                                    "article-passage-instances-v1";
                            default -> RECIPE_VERSION;
                        });
            }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                wikipedia.WikipediaArticleClient.Article article;
                if (candidateEvidenceMode == CandidateEvidenceMode.ARTICLE_PASSAGES
                        || candidateEvidenceMode
                            == CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES) {
                    if (request.wikipediaArticle() != null) {
                        article = new wikipedia.WikipediaArticleClient.Article(
                                request.wikipediaArticle());
                    } else {
                        WikimediaEntityLookup.EntityRecord entity =
                                lookup.byQid(request.subject().id()).execute(context);
                        String title = entity.sitelink("enwiki");
                        if (title.isBlank()) return empty(request);
                        article = articles.byTitle(title).execute(context);
                    }
                } else if (request.articleIntroduction() != null) {
                    datasource.evidence.ArticleIntroduction introduction =
                            request.articleIntroduction();
                    article = new wikipedia.WikipediaArticleClient.Article(
                            introduction.document(), introduction.text(),
                            request.categoryMemberships().stream()
                                    .map(datasource.evidence.CategoryMembership::category)
                                    .toList(), introduction.links());
                } else if (!request.categoryMemberships().isEmpty()) {
                    var first = request.categoryMemberships().get(0);
                    article = new wikipedia.WikipediaArticleClient.Article(first.document(), "",
                            request.categoryMemberships().stream()
                                    .map(datasource.evidence.CategoryMembership::category).toList());
                } else {
                    WikimediaEntityLookup.EntityRecord entity =
                            lookup.byQid(request.subject().id()).execute(context);
                    String title = entity.sitelink("enwiki");
                    if (title.isBlank()) return empty(request);
                    article = articles.byTitle(title).execute(context);
                }
                if (article == null) return empty(request);
                List<CategoryValue> categoryValues = new ArrayList<>();
                List<CategoryEvidence> categories = !request.categoryMemberships().isEmpty()
                        ? request.categoryMemberships().stream()
                                .map(value -> new CategoryEvidence(
                                        value.category(), value.document())).toList()
                        : article.categories().stream()
                                .map(value -> new CategoryEvidence(value, article.document()))
                                .toList();
                for (CategoryEvidence evidence : categories) {
                    String category = evidence.category();
                    wikidata.explore.model.WikipediaCategoryRule.Match match =
                            categoryMatch(category, categoryRule);
                    if (match == null) continue;
                    WikimediaEntityLookup.EntityRecord value = !match.valueQid().isBlank()
                            ? lookup.byQid(match.valueQid()).execute(context)
                            : lookup.byWikipediaTitle(match.valueTitle()).execute(context);
                    if (value != null && WikidataIds.isQid(value.qid())
                            && isReferentOfTargetKind(value)) {
                        categoryValues.add(new CategoryValue(
                                category, value, evidence.document()));
                    }
                }
                List<MentionHit> candidates = switch (candidateEvidenceMode) {
                    case LOADED_INTRODUCTION_MENTIONS ->
                            mentionedCandidates(article, context);
                    case NONE, ARTICLE_PASSAGES, ARTICLE_PASSAGE_INSTANCES -> List.of();
                };
                return proposal(request, article, categoryValues, categoryRule, candidates,
                        candidateEvidenceMode == CandidateEvidenceMode.ARTICLE_PASSAGES
                                || candidateEvidenceMode
                                    == CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES
                                ? articlePassages(article) : null,
                        candidateEvidenceMode
                                == CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES
                                ? passageInstanceSpec : null);
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.fields().size();
            }
        };
    }

    private static EnrichmentProposal proposal(EnrichmentRequest request,
            wikipedia.WikipediaArticleClient.Article article,
            List<CategoryValue> categoryValues,
            wikidata.explore.model.WikipediaCategoryRule categoryRule,
            List<MentionHit> mentionCandidates,
            ArticlePassages passages,
            datasource.wikipedia.WikipediaDatasourceProvider.ArticlePassageInstanceSpec
                    passageInstanceSpec) {
        String identityId = "wikipedia:" + article.title();
        SourceRef source = new SourceRef("Wikipedia (English)", article.title(),
                article.url(), semanticProperty(request.targetField()));
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(identityId, article.title(), List.of(),
                        "English Wikipedia article linked from Wikidata", source, 1.0,
                        List.of("Wikidata enwiki sitelink"), List.of());
        List<EnrichmentProposal.FieldCandidate> fields = new ArrayList<>();
        for (Object value : values(request.currentValue())) {
            if (!supportedValue(value)) continue;
            String needle = displayValue(value);
            if (needle.isBlank()) continue;
            int hit = findMention(article.text(), needle);
            if (hit < 0) continue;
            int start = contextStart(article.text(), hit);
            int end = contextEnd(article.text(), hit + needle.length());
            String excerpt = article.text().substring(start, end);
            EntityRef proposedEntity = entityRef(value);
            Object proposedLiteral = proposedEntity == null ? value : null;
            ExtractedClaim claim = new ExtractedClaim(subjectRef(request),
                    semanticProperty(request.targetField()), proposedLiteral, proposedEntity,
                    List.of(EvidenceFragment.positioned(
                            article.document(), "Article", start, excerpt)),
                    "exact value-label mention", RECIPE_VERSION, "", 0.72,
                    List.of("Exact mention corroborates the value but does not prove its field role"));
            fields.add(new EnrichmentProposal.FieldCandidate(
                    identityId + ':' + request.targetField() + ':' + fields.size(), identityId,
                    request.targetField(), request.currentValue(), value, source,
                    EnrichmentProposal.ReviewAction.CORROBORATE,
                    null, request.collection(), List.of(claim)));
        }
        for (CategoryValue categoryValue : categoryValues) {
            WikimediaEntityLookup.EntityRecord entity = categoryValue.entity();
            Viewable existing = existingEntity(request.currentValue(), entity.qid());
            if (existing == null && categoryRule != null && categoryRule.policy()
                    == wikidata.explore.model.CategoryCandidatePolicy.EVIDENCE_ONLY) continue;
            Viewable value = existing;
            if (value == null) value = new quiz.transform.DynamicViewable(
                    entity.qid(), entity.label().isBlank() ? entity.qid() : entity.label());
            String targetType = request.targetSchema() == null
                    ? null : request.targetSchema().targetType();
            if (value instanceof quiz.transform.DynamicViewable dynamic
                    && targetType != null && !targetType.isBlank()) dynamic.type(targetType);
            String semantic = semanticProperty(request.targetField());
            ExtractedClaim claim = new ExtractedClaim(subjectRef(request), semantic, null,
                    EntityRef.wikidata(entity.qid()),
                    List.of(EvidenceFragment.excerpt(categoryValue.document(), "Categories",
                            "Member of Category:" + categoryValue.category())),
                    "Wikipedia category relation", "category-relation-v1", "", 0.92,
                    List.of("Category title matches the field's configured Wikipedia "
                            + "category rule"));
            fields.add(new EnrichmentProposal.FieldCandidate(
                    identityId + ':' + request.targetField() + ":category:" + fields.size(),
                    identityId, request.targetField(), request.currentValue(), value, source,
                    existing != null ? EnrichmentProposal.ReviewAction.CORROBORATE
                            : request.collection()
                            ? EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION
                            : EnrichmentProposal.ReviewAction.FILL_IF_EMPTY,
                    null, request.collection(), List.of(claim)));
        }
        if (passages != null && !passages.text().isBlank() && passageInstanceSpec == null) {
            List<EvidenceFragment> evidence = passages.passages().stream()
                    .map(passage -> EvidenceFragment.positioned(article.document(),
                            passage.section(), passage.start(), passage.text()))
                    .toList();
            ExtractedClaim claim = new ExtractedClaim(subjectRef(request),
                    semanticProperty(request.targetField()), passages.text(), null, evidence,
                    "bounded Wikipedia article passages", "bounded-article-passages-v1", "",
                    0.60, List.of("Maintenance sections are omitted, but the retained prose "
                            + "still requires review and is not interpreted as entities or events"));
            fields.add(new EnrichmentProposal.FieldCandidate(
                    identityId + ':' + request.targetField() + ":passages", identityId,
                    request.targetField(), request.currentValue(), passages.text(), source,
                    hasValue(request.currentValue())
                            ? EnrichmentProposal.ReviewAction.REPLACE
                            : EnrichmentProposal.ReviewAction.FILL_IF_EMPTY,
                    null, false, List.of(claim)));
        }
        java.util.Set<String> currentIds = values(request.currentValue()).stream()
                .filter(Viewable.class::isInstance).map(Viewable.class::cast)
                .map(Viewable::getIdentifier).collect(java.util.stream.Collectors.toSet());
        List<EnrichmentProposal.InstanceCandidate> instances = new ArrayList<>();
        if (passages != null && passageInstanceSpec != null
                && passageInstanceSpec.configured()) {
            String targetType = request.targetSchema() == null
                    ? "" : clean(request.targetSchema().targetType());
            for (Passage passage : passages.passages()) {
                EvidenceFragment fragment = EvidenceFragment.positioned(
                        article.document(), passage.section(), passage.start(), passage.text());
                ExtractedClaim contentClaim = new ExtractedClaim(subjectRef(request),
                        semanticProperty(request.targetField()), passage.text(), null,
                        List.of(fragment), "bounded article passage proposed as an instance",
                        "article-passage-instances-v1", "", 0.45,
                        List.of("The source bounds and preserves the passage; review decides "
                                + "whether it is one instance of " + targetType));
                String instanceId = passageInstanceId(request, passage);
                if (currentIds.contains(instanceId)) continue;
                String label = passageLabel(request.subject().displayName(),
                        passage.section(), passage.text());
                EnrichmentProposal.InstanceCandidate instance =
                        new EnrichmentProposal.InstanceCandidate(
                                identityId + ":instance:" + instanceId,
                                targetType, instanceId, label,
                                List.of(
                                        EnrichmentProposal.InstanceField.literal(
                                                passageInstanceSpec.textField(),
                                                passage.text()),
                                        EnrichmentProposal.InstanceField.references(
                                                passageInstanceSpec.sourceField(),
                                                List.of(new EnrichmentProposal.TypedReference(
                                                        request.subject().type(),
                                                        request.subject().targetId())))),
                                source, List.of(contentClaim));
                instances.add(instance);
                quiz.transform.DynamicViewable proposed =
                        new quiz.transform.DynamicViewable(instanceId, label);
                proposed.type(targetType);
                ExtractedClaim referenceClaim = new ExtractedClaim(subjectRef(request),
                        semanticProperty(request.targetField()), null,
                        new EntityRef("wikipedia-passage", instanceId), List.of(fragment),
                        "reviewed article passage instance",
                        "article-passage-instances-v1", "", 0.45,
                        List.of("Review decides whether this passage is a " + targetType
                                + " and belongs in the field"));
                fields.add(new EnrichmentProposal.FieldCandidate(
                        identityId + ':' + request.targetField() + ":passage:"
                                + passage.start(), identityId, request.targetField(),
                        request.currentValue(), proposed, source,
                        EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, true, List.of(referenceClaim)));
            }
        }
        for (MentionHit mention : mentionCandidates) {
            Viewable candidate = mention.candidate();
            if (currentIds.contains(candidate.getIdentifier())) continue;
            String needle = mention.phrase();
            int hit = mention.offset();
            int start = contextStart(article.text(), hit);
            int end = contextEnd(article.text(), hit + needle.length());
            String excerpt = article.text().substring(start, end);
            ExtractedClaim claim = new ExtractedClaim(subjectRef(request),
                    semanticProperty(request.targetField()), null,
                    entityRef(candidate),
                    List.of(EvidenceFragment.positioned(
                            article.document(), mention.section(), start, excerpt)),
                    mention.linked()
                            ? "linked mention of a loaded target-class instance"
                            : "unique exact mention of a loaded target-class instance",
                    mention.recipe(), "", mention.linked() ? 0.82 : 0.68,
                    List.of(mention.linked()
                            ? "The article link resolves the mention to this Wikidata entity; "
                                    + "review still decides whether it has this field role"
                            : "The unlinked label is unique among the loaded target-class "
                                    + "instances; review still decides whether it has this "
                                    + "field role"));
            fields.add(new EnrichmentProposal.FieldCandidate(
                    identityId + ':' + request.targetField() + ":mention:"
                            + candidate.getIdentifier(),
                    identityId, request.targetField(), request.currentValue(), candidate,
                    source, request.collection()
                            ? EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION
                            : EnrichmentProposal.ReviewAction.FILL_IF_EMPTY,
                    null, request.collection(), List.of(claim)));
        }
        return new EnrichmentProposal(request.subject(), List.of(identity), fields, List.of(),
                instances);
    }

    private boolean passageInstanceTarget(EnrichmentRequest request) {
        objectview.field.FieldRef schema = request.targetSchema();
        return passageInstanceSpec != null && passageInstanceSpec.configured()
                && schema != null && schema.collection() && schema.reference()
                && !clean(schema.targetType()).isBlank();
    }

    /**
     * What the passage IS: its subject, field, section and text. The claim id also hashes
     * the article revision and the passage's offset, so an edit anywhere in the article
     * gave every accepted passage a new id, and the next review proposed it again.
     */
    static String passageInstanceId(EnrichmentRequest request, Passage passage) {
        String material = request.subject().type() + '\0' + request.subject().targetId()
                + '\0' + request.targetField() + '\0' + clean(passage.section())
                + '\0' + clean(passage.text());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "wikipedia-passage:" + java.util.HexFormat.of().formatHex(digest)
                    .substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String passageLabel(String source, String section, String text) {
        String sentence = clean(text).replace('\n', ' ');
        int period = sentence.indexOf('.');
        if (period > 0) sentence = sentence.substring(0, period + 1);
        if (sentence.length() > 100) sentence = sentence.substring(0, 97) + "…";
        String context = clean(source);
        String heading = clean(section);
        if (!heading.isBlank() && !"Introduction".equalsIgnoreCase(heading)) {
            context = context.isBlank() ? heading : context + " — " + heading;
        }
        return sentence.isBlank() ? context
                : context.isBlank() ? sentence : context + " — " + sentence;
    }

    /**
     * Exact matching indexed by the first word of each candidate label. A domain may
     * hold thousands of target instances; scanning every label across every article
     * would turn one explicit review action into subjects × candidates × article-length
     * work. The index changes only lookup, never matching semantics.
     */
    private static List<MentionHit> mentionedCandidates(
            String text, Map<String, List<MentionTerm>> candidates,
            List<datasource.evidence.ArticleIntroduction.Link> links) {
        if (text == null || text.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashMap<String, MentionHit> hits = new java.util.LinkedHashMap<>();
        for (int start = 0; start < text.length();) {
            if (!Character.isLetterOrDigit(text.charAt(start))
                    || start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) end++;
            if (insideLink(start, links)) {
                start = end;
                continue;
            }
            String first = text.substring(start, end).toLowerCase(Locale.ROOT);
            for (MentionTerm candidate : candidates.getOrDefault(first, List.of())) {
                String phrase = candidate.phrase();
                long identitiesWithLabel = candidates.getOrDefault(first, List.of()).stream()
                        .filter(term -> term.phrase().equalsIgnoreCase(phrase))
                        .map(term -> term.candidate().getIdentifier()).distinct().count();
                if (identitiesWithLabel != 1) continue;
                int phraseEnd = start + phrase.length();
                if (phraseEnd > text.length()
                        || !text.regionMatches(true, start, phrase, 0, phrase.length())) continue;
                boolean right = phraseEnd == text.length()
                        || !Character.isLetterOrDigit(text.charAt(phraseEnd));
                if (right) hits.putIfAbsent(candidate.candidate().getIdentifier(),
                        new MentionHit(candidate.candidate(), phrase, start, false,
                                "Article introduction", "typed-introduction-mention-v2"));
            }
            start = end;
        }
        return List.copyOf(hits.values());
    }

    /**
     * Article links are identity evidence, while an unlinked label is only usable when
     * it names one loaded target instance. This is what prevents two different loaded
     * entities called Achilles from nominating one another.
     */
    private List<MentionHit> mentionedCandidates(
            wikipedia.WikipediaArticleClient.Article article, QueryContext context)
            throws Exception {
        java.util.LinkedHashMap<String, MentionHit> hits = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, WikimediaEntityLookup.EntityRecord> resolved =
                new java.util.LinkedHashMap<>();
        for (datasource.evidence.ArticleIntroduction.Link link : article.links()) {
            String phrase = clean(link.text());
            String first = firstWord(phrase).toLowerCase(Locale.ROOT);
            List<MentionTerm> matching = mentionCandidates.getOrDefault(first, List.of())
                    .stream().filter(term -> term.phrase().equalsIgnoreCase(phrase)).toList();
            if (matching.isEmpty()) continue;
            List<MentionTerm> corresponding = matching.stream()
                    .filter(term -> term.articleTitle().equalsIgnoreCase(link.targetTitle()))
                    .toList();
            if (corresponding.size() == 1) {
                MentionTerm candidate = corresponding.getFirst();
                hits.putIfAbsent(candidate.candidate().getIdentifier(), new MentionHit(
                        candidate.candidate(), phrase, link.start(), true,
                        "Article introduction", "typed-introduction-mention-v2"));
                continue;
            }
            WikimediaEntityLookup.EntityRecord entity = resolved.get(link.targetTitle());
            if (!resolved.containsKey(link.targetTitle())) {
                entity = lookup.byWikipediaTitle(link.targetTitle()).execute(context);
                resolved.put(link.targetTitle(), entity);
            }
            if (entity == null) continue;
            for (MentionTerm candidate : matching) {
                if (entity.qid().equals(candidate.candidate().getIdentifier())) {
                    hits.putIfAbsent(entity.qid(), new MentionHit(
                            candidate.candidate(), phrase, link.start(), true,
                            "Article introduction", "typed-introduction-mention-v2"));
                }
            }
        }
        // Linked text was answered by its link above, including when the link names an
        // entity that is not loaded: a unique loaded label must not overrule it.
        for (MentionHit hit : mentionedCandidates(
                article.text(), mentionCandidates, article.links())) {
            hits.putIfAbsent(hit.candidate().getIdentifier(), hit);
        }
        return List.copyOf(hits.values());
    }

    /** Extract bounded content paragraphs while dropping Wikipedia's standard navigation
     * and citation sections. Relevance comes from the explicitly selected source article,
     * not from domain words hidden in Java. */
    static ArticlePassages articlePassages(
            wikipedia.WikipediaArticleClient.Article article) {
        if (article == null || article.text().isBlank()) {
            return new ArticlePassages("", List.of());
        }
        int remaining = datasource.wikipedia.WikipediaDatasourceProvider
                .MAX_ARTICLE_PASSAGE_CHARACTERS;
        List<Passage> kept = new ArrayList<>();
        StringBuilder combined = new StringBuilder();
        String lastSection = "";
        for (wikipedia.WikipediaArticleClient.ArticleSection section : article.sections()) {
            if (maintenanceSection(section.title())) continue;
            String sectionText = article.text().substring(section.start(), section.end());
            int local = 0;
            while (local < sectionText.length()
                    && kept.size() < datasource.wikipedia.WikipediaDatasourceProvider
                            .MAX_ARTICLE_PASSAGES
                    && remaining > 0) {
                int lineEnd = sectionText.indexOf('\n', local);
                if (lineEnd < 0) lineEnd = sectionText.length();
                int start = local;
                while (start < lineEnd && Character.isWhitespace(sectionText.charAt(start))) {
                    start++;
                }
                int end = lineEnd;
                while (end > start && Character.isWhitespace(sectionText.charAt(end - 1))) {
                    end--;
                }
                local = lineEnd + 1;
                if (end <= start) continue;
                String paragraph = sectionText.substring(start, end);
                if (paragraph.equalsIgnoreCase(section.title())) continue;
                if (!section.title().equals(lastSection)) {
                    String heading = combined.isEmpty() ? section.title()
                            : "\n\n" + section.title();
                    if (heading.length() >= remaining) break;
                    combined.append(heading).append('\n');
                    remaining -= heading.length() + 1;
                    lastSection = section.title();
                } else if (!combined.isEmpty()) {
                    if (remaining < 2) break;
                    combined.append("\n\n");
                    remaining -= 2;
                }
                int length = Math.min(paragraph.length(), remaining);
                if (length <= 0) break;
                String retained = paragraph.substring(0, length);
                combined.append(retained);
                kept.add(new Passage(section.title(), section.start() + start, retained));
                remaining -= retained.length();
            }
            if (kept.size() >= datasource.wikipedia.WikipediaDatasourceProvider
                    .MAX_ARTICLE_PASSAGES || remaining <= 0) break;
        }
        return new ArticlePassages(combined.toString(), kept);
    }

    private static boolean maintenanceSection(String title) {
        String normalized = clean(title).toLowerCase(Locale.ROOT);
        return java.util.Set.of("references", "notes", "sources", "bibliography",
                "external links", "further reading", "see also").contains(normalized);
    }

    private static boolean insideLink(
            int offset, List<datasource.evidence.ArticleIntroduction.Link> links) {
        if (links == null) return false;
        for (datasource.evidence.ArticleIntroduction.Link link : links) {
            if (offset >= link.start() && offset < link.end()) return true;
        }
        return false;
    }

    private static String firstWord(String phrase) {
        if (phrase == null || phrase.isBlank()
                || !Character.isLetterOrDigit(phrase.charAt(0))) return "";
        int end = 1;
        while (end < phrase.length() && Character.isLetterOrDigit(phrase.charAt(end))) end++;
        return phrase.substring(0, end);
    }

    private record MentionTerm(Viewable candidate, String phrase, String articleTitle) { }
    private record MentionHit(Viewable candidate, String phrase, int offset, boolean linked,
                              String section, String recipe) { }
    record Passage(String section, int start, String text) { }
    record ArticlePassages(String text, List<Passage> passages) {
        ArticlePassages {
            text = text == null ? "" : text;
            passages = passages == null ? List.of() : List.copyOf(passages);
        }
    }

    /**
     * Whether a resolved category value is the KIND of thing the target field refers to.
     *
     * <p>A pattern matches by title alone, so "Films set in 1999" is exactly as good a
     * match for {@code Films set in <value>} as "Films set in Sierra Leone" — and the
     * year resolves to a real entity, which was then offered as a Location. Review caught
     * it every time, which is the point: it was noise a reviewer had to reject on every
     * run.
     *
     * <p>The test is the model's OWN {@link wikidata.explore.model.EntityKindRule} for the
     * class the field refers to — the same declaration, read the same way, that decides an
     * entity's kind during generation. Nothing here infers a kind from a field or class
     * NAME. A class the model declares no kind rule for admits everything, exactly as
     * before: the check is only ever as good as what the model says, and that is the one
     * place to improve it.
     */
    private boolean isReferentOfTargetKind(WikimediaEntityLookup.EntityRecord value) {
        if (referentKind == null || !referentKind.isConfigured()) return true;
        return value.entityQids(referentKind.propertyPid()).stream()
                .anyMatch(referentKind.evidenceQids()::contains);
    }

    /**
     * The value a category names for a field, according to the field's OWN rule.
     *
     * <p>Category parsing is deliberately not generic named-entity extraction: a
     * category means something for a field only because the model says it does. There
     * used to be a fallback that read "Films set in X" into any field called location or
     * setting, which inferred a relation from a field's NAME and buried a domain's
     * vocabulary in code — so a location field in an unrelated domain silently collected
     * film settings. A field without a configured rule now yields nothing, which is the
     * honest answer and the one the model can change.
     */
    static wikidata.explore.model.WikipediaCategoryRule.Match categoryMatch(String category,
            wikidata.explore.model.WikipediaCategoryRule configured) {
        return configured == null ? null : configured.match(category);
    }

    /** Compatibility seam retained for the existing pattern tests: exact mappings have
     * no inferred title, while a pattern still exposes the title it names. */
    static String categoryValue(String category,
            wikidata.explore.model.WikipediaCategoryRule configured) {
        var match = categoryMatch(category, configured);
        return match == null || match.valueTitle().isBlank() ? null : match.valueTitle();
    }

    private static Viewable existingEntity(Object current, String qid) {
        for (Object value : values(current)) {
            if (value instanceof Viewable viewable && qid.equals(viewable.getIdentifier())) {
                return viewable;
            }
        }
        return null;
    }

    private record CategoryEvidence(
            String category, datasource.evidence.SourceDocument document) { }
    private record CategoryValue(String category,
            WikimediaEntityLookup.EntityRecord entity,
            datasource.evidence.SourceDocument document) { }

    private static List<?> values(Object current) {
        if (current == null) return List.of();
        return current instanceof Collection<?> collection
                ? collection.stream().filter(java.util.Objects::nonNull).toList()
                : List.of(current);
    }

    private static boolean hasValue(Object current) {
        if (current == null) return false;
        if (current instanceof String text) return !text.isBlank();
        if (current instanceof Collection<?> values) return !values.isEmpty();
        return true;
    }

    private static boolean passageTarget(EnrichmentRequest request) {
        return request != null && request.targetSchema() != null
                && request.targetSchema().kind() == objectview.field.FieldKind.TEXT
                && !request.targetSchema().collection();
    }

    private static String displayValue(Object value) {
        if (value instanceof Viewable viewable) return clean(viewable.getDisplayName());
        return clean(String.valueOf(value));
    }

    private static boolean supportedValue(Object value) {
        if (value instanceof Viewable viewable) {
            return viewable.getIdentifier() != null && !viewable.getIdentifier().isBlank();
        }
        // The claim decides what it can carry. Keeping a second list here decided the
        // same question silently and differently — a FlexibleDate was skipped, so a date
        // of birth could never be corroborated and nothing said why.
        return ExtractedClaim.isStableValue(value);
    }

    /** Case-insensitive exact phrase search which does not accept Paris in Comparison. */
    static int findMention(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) return -1;
        for (int hit = 0; hit <= text.length() - phrase.length(); hit++) {
            if (!text.regionMatches(true, hit, phrase, 0, phrase.length())) continue;
            int end = hit + phrase.length();
            boolean left = hit == 0 || !Character.isLetterOrDigit(text.charAt(hit - 1));
            boolean right = end == text.length()
                    || !Character.isLetterOrDigit(text.charAt(end));
            if (left && right) return hit;
        }
        return -1;
    }

    private static EntityRef entityRef(Object value) {
        if (!(value instanceof Viewable viewable)) return null;
        String id = clean(viewable.getIdentifier());
        if (id.isBlank()) return null;
        return WikidataIds.isQid(id) ? EntityRef.wikidata(id) : new EntityRef("domain", id);
    }

    private static EntityRef subjectRef(EnrichmentRequest request) {
        String id = clean(request.subject().targetId());
        return WikidataIds.isQid(id) ? EntityRef.wikidata(id)
                : new EntityRef("domain:" + request.subject().type().toLowerCase(Locale.ROOT), id);
    }

    private static int contextStart(String text, int hit) {
        int start = Math.max(0, hit - 120);
        int boundary = text.lastIndexOf('\n', hit);
        return boundary >= start ? boundary + 1 : start;
    }

    private static int contextEnd(String text, int hitEnd) {
        int end = Math.min(text.length(), hitEnd + 120);
        int boundary = text.indexOf('\n', hitEnd);
        return boundary >= 0 && boundary <= end ? boundary : end;
    }

    private static String semanticProperty(String field) { return "field:" + field; }

    private static EnrichmentProposal empty(EnrichmentRequest request) {
        return new EnrichmentProposal(request.subject(), List.of(), List.of(), List.of());
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
