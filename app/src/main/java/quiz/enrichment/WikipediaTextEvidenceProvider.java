package quiz.enrichment;

import datasource.EntityRef;
import datasource.SourceRef;
import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.EvidenceFragment;
import datasource.evidence.ExtractedClaim;
import objectview.Viewable;
import wikidata.WikidataIds;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds exact textual support in the English Wikipedia article linked from a subject's
 * Wikidata entity. This deliberately corroborates values already present in the selected
 * field; it does not pretend that an arbitrary field can be inferred from prose without a
 * configured extraction recipe.
 */
public final class WikipediaTextEvidenceProvider implements EnrichmentProvider {
    static final String RECIPE_VERSION = "exact-value-mention-v1";

    private final WikimediaEntityLookup lookup;
    private final wikipedia.WikipediaArticleClient articles;
    private final wikidata.explore.model.WikipediaCategoryRule categoryRule;

    public WikipediaTextEvidenceProvider() {
        this(WikimediaEntityLookup.defaultFetcher(), null);
    }

    public WikipediaTextEvidenceProvider(
            wikidata.explore.model.WikipediaCategoryRule categoryRule) {
        this(WikimediaEntityLookup.defaultFetcher(), categoryRule);
    }

    WikipediaTextEvidenceProvider(WikimediaEntityLookup.JsonFetcher fetcher) {
        this(fetcher, null);
    }

    WikipediaTextEvidenceProvider(WikimediaEntityLookup.JsonFetcher fetcher,
            wikidata.explore.model.WikipediaCategoryRule categoryRule) {
        this.lookup = new WikimediaEntityLookup(fetcher);
        this.articles = new wikipedia.WikipediaArticleClient(fetcher::fetch);
        this.categoryRule = categoryRule == null ? null : categoryRule.copy();
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
                        && categoryRule != null && categoryRule.configured()))
                && (request.targetSchema() == null
                    || request.targetSchema().kind() != objectview.field.FieldKind.MEDIA);
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException(
                    "Wikipedia evidence requires a Wikidata identity and either an existing "
                            + "value or acquired category memberships");
        }
        return new Query<>() {
            @Override public String purpose() { return "Find Wikipedia text evidence"; }
            @Override public String skeleton() {
                return "Resolve the linked English article and find exact value mentions";
            }
            @Override public String queryType() { return "Wikipedia API"; }
            @Override public String description() {
                return "Corroborate the current field value from versioned article text";
            }
            @Override public Map<String, String> parameters() {
                return Map.of("qid", request.subject().id(),
                        "field", request.targetField(),
                        "recipe", RECIPE_VERSION);
            }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                wikipedia.WikipediaArticleClient.Article article;
                if (!request.categoryMemberships().isEmpty()) {
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
                for (String category : article.categories()) {
                    String valueTitle = categoryValue(category, categoryRule);
                    if (valueTitle == null) continue;
                    WikimediaEntityLookup.EntityRecord value =
                            lookup.byWikipediaTitle(valueTitle).execute(context);
                    if (value != null && WikidataIds.isQid(value.qid())) {
                        categoryValues.add(new CategoryValue(category, value));
                    }
                }
                return proposal(request, article, categoryValues, categoryRule);
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.fields().size();
            }
        };
    }

    private static EnrichmentProposal proposal(EnrichmentRequest request,
            wikipedia.WikipediaArticleClient.Article article,
            List<CategoryValue> categoryValues,
            wikidata.explore.model.WikipediaCategoryRule categoryRule) {
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
                    List.of(EvidenceFragment.excerpt(article.document(), "Categories",
                            "Member of Category:" + categoryValue.category())),
                    "Wikipedia category relation", "category-relation-v1", "", 0.92,
                    List.of("Category title matches: Films set in <place>"));
            fields.add(new EnrichmentProposal.FieldCandidate(
                    identityId + ':' + request.targetField() + ":category:" + fields.size(),
                    identityId, request.targetField(), request.currentValue(), value, source,
                    existing != null ? EnrichmentProposal.ReviewAction.CORROBORATE
                            : request.collection()
                            ? EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION
                            : EnrichmentProposal.ReviewAction.FILL_IF_EMPTY,
                    null, request.collection(), List.of(claim)));
        }
        return new EnrichmentProposal(request.subject(), List.of(identity), fields, List.of());
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
    static String categoryValue(String category,
            wikidata.explore.model.WikipediaCategoryRule configured) {
        if (configured == null || !configured.configured() || category == null) return null;
        String pattern = configured.pattern();
        int placeholder = pattern.indexOf("<value>");
        String prefix = pattern.substring(0, placeholder);
        String suffix = pattern.substring(placeholder + "<value>".length());
        if (!category.startsWith(prefix) || !category.endsWith(suffix)
                || category.length() < prefix.length() + suffix.length()) return null;
        String value = category.substring(prefix.length(), category.length() - suffix.length())
                .trim();
        return value.isBlank() ? null : value;
    }

    private static Viewable existingEntity(Object current, String qid) {
        for (Object value : values(current)) {
            if (value instanceof Viewable viewable && qid.equals(viewable.getIdentifier())) {
                return viewable;
            }
        }
        return null;
    }

    private record CategoryValue(String category, WikimediaEntityLookup.EntityRecord entity) { }

    private static List<?> values(Object current) {
        if (current == null) return List.of();
        return current instanceof Collection<?> collection
                ? collection.stream().filter(java.util.Objects::nonNull).toList()
                : List.of(current);
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
