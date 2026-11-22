package domain.enrichment;

import domain.datasource.EntityRef;
import domain.datasource.SourceRef;
import domain.enrichment.EnrichmentProposal;
import domain.datasource.evidence.EvidenceFragment;
import domain.datasource.evidence.ExtractedClaim;
import domain.datasource.evidence.WikipediaArticle;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.datasource.wikipedia.WikipediaTableColumnLinks;
import objectview.Viewable;
import objectview.field.FieldKind;
import wikidata.WikidataIds;
import work.Query;
import work.QueryContext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Proposes loaded, typed references from one explicitly configured column of a retained
 * Wikipedia table. The source structure selects evidence; review decides the field role.
 */
public final class WikipediaTableReferenceEvidenceProvider implements EnrichmentProvider {
    static final String RECIPE_VERSION = "article-table-column-links-v1";

    private final WikipediaDatasourceProvider.ArticleTableColumnLinkSpec spec;
    private final WikipediaLinkedReferenceResolver candidates;
    private final WikimediaEntityLookup lookup;
    private final wikipedia.WikipediaArticleClient articles;

    public WikipediaTableReferenceEvidenceProvider(
            WikipediaDatasourceProvider.ArticleTableColumnLinkSpec spec,
            Collection<? extends Viewable> loadedCandidates) {
        this(spec, loadedCandidates, WikimediaEntityLookup.defaultFetcher());
    }

    WikipediaTableReferenceEvidenceProvider(
            WikipediaDatasourceProvider.ArticleTableColumnLinkSpec spec,
            Collection<? extends Viewable> loadedCandidates,
            WikimediaEntityLookup.JsonFetcher fetcher) {
        this.spec = java.util.Objects.requireNonNull(spec);
        candidates = new WikipediaLinkedReferenceResolver(loadedCandidates);
        lookup = new WikimediaEntityLookup(fetcher);
        articles = new wikipedia.WikipediaArticleClient(fetcher::fetch);
    }

    @Override public String name() { return "Wikipedia table-column link evidence"; }

    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.subject() != null
                && WikidataIds.isQid(request.subject().id())
                && request.targetField() != null && !request.targetField().isBlank()
                && request.targetSchema() != null
                && request.targetSchema().collection()
                && request.targetSchema().valueKind() == FieldKind.REFERENCE
                && spec.configured();
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException("Wikipedia table-column evidence requires "
                    + "a configured table column and a reference-list target field");
        }
        return new Query<>() {
            private WikipediaTableColumnLinks.Selection selection;
            private int resolvedLinks;
            private int resolvedTargets;
            private List<String> unmatchedExamples = List.of();

            @Override public String purpose() {
                return "Resolve one Wikipedia table column to loaded instances";
            }

            @Override public String skeleton() {
                return "Read " + spec.description()
                        + "; admit only loaded instances of the configured target class";
            }

            @Override public String queryType() { return "Retained Wikipedia evidence"; }

            @Override public String description() {
                return "Propose typed references for explicit review; create no entities";
            }

            @Override public Map<String, String> parameters() {
                return Map.of("field", request.targetField(),
                        "section", spec.sectionPath(), "table", spec.table(),
                        "column", spec.column());
            }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                WikipediaArticle article = article(request, context);
                selection = WikipediaTableColumnLinks.select(article, spec);
                List<WikipediaTableColumnLinks.SelectedLink> selected = selection.links();
                LinkedHashMap<WikipediaLinkedReferenceResolver.Link,
                        WikipediaTableColumnLinks.SelectedLink> evidence = new LinkedHashMap<>();
                for (WikipediaTableColumnLinks.SelectedLink value : selected) {
                    WikipediaArticle.ArticleLink link = value.link();
                    evidence.put(new WikipediaLinkedReferenceResolver.Link(
                            link.text(), link.targetTitle(), link.start(), link.end()), value);
                }
                WikipediaLinkedReferenceResolver.Resolution resolution =
                        candidates.resolveDetailed(List.copyOf(evidence.keySet()), lookup, context);
                resolvedLinks = resolution.resolvedLinks();
                resolvedTargets = resolution.matches().size();
                unmatchedExamples = resolution.unresolvedLinks().stream()
                        .map(evidence::get).filter(java.util.Objects::nonNull)
                        .map(value -> value.cellText().isBlank()
                                ? value.link().text() : value.cellText())
                        .filter(value -> !value.isBlank()).distinct().limit(8).toList();
                Set<String> current = currentIds(request.currentValue());
                List<EnrichmentProposal.FieldCandidate> fields = resolution.matches().stream()
                        .filter(match -> !current.contains(match.candidate().getIdentifier()))
                        .map(match -> candidate(request, article,
                                evidence.get(match.link()), match))
                        .toList();
                return new EnrichmentProposal(request.subject(), List.of(), fields, List.of());
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.fields().size();
            }

            @Override public String summary(EnrichmentProposal result) {
                if (selection == null) return "No table-column evidence read";
                return selection.matchingSections() + " matching section(s), "
                        + selection.matchingTables() + " matching table(s), "
                        + selection.matchingRows() + " data row(s), "
                        + selection.links().size() + " retained link(s); "
                        + resolvedLinks + " link(s) resolved to " + resolvedTargets
                        + " loaded target(s), "
                        + Math.max(0, selection.links().size() - resolvedLinks)
                        + " did not resolve to a loaded target"
                        + (unmatchedExamples.isEmpty() ? "" : " (for example: "
                                + String.join("; ", unmatchedExamples) + ")") + "; "
                        + rowCount(result) + " proposed";
            }
        };
    }

    private WikipediaArticle article(EnrichmentRequest request, QueryContext context)
            throws Exception {
        if (request.wikipediaArticle() != null) return request.wikipediaArticle();
        WikimediaEntityLookup.EntityRecord entity =
                lookup.byQid(request.subject().id()).execute(context);
        String title = entity == null ? "" : entity.sitelink(
                wikidata.WikidataLanguageDefaults.wikipediaSite());
        if (title.isBlank()) return null;
        wikipedia.WikipediaArticleClient.StructuredPage page =
                articles.structuredByTitle(title).execute(context);
        return page == null ? null : page.article().structuredArticle();
    }

    private EnrichmentProposal.FieldCandidate candidate(
            EnrichmentRequest request, WikipediaArticle article,
            WikipediaTableColumnLinks.SelectedLink selected,
            WikipediaLinkedReferenceResolver.Match match) {
        Viewable value = match.candidate();
        String semantic = "field:" + request.targetField();
        String sourceLabel = selected.sectionPath() + " — " + selected.table()
                + " — " + selected.column();
        SourceRef source = new SourceRef(article.document().kind(),
                article.document().sourceId(), article.document().url(), semantic,
                sourceLabel, "linked table cell");
        ExtractedClaim claim = new ExtractedClaim(subjectRef(request), semantic, null,
                EntityRef.wikidata(value.getIdentifier()),
                List.of(EvidenceFragment.excerpt(article.document(), sourceLabel,
                        selected.cellText())),
                "linked target in configured article-table column", RECIPE_VERSION, "",
                0.92, List.of("The configured source structure selects the cell and its "
                        + "link identifies the entity; review decides whether the row "
                        + "expresses this field role"));
        return new EnrichmentProposal.FieldCandidate(
                "table-column-link:" + request.subject().targetId() + ':'
                        + request.targetField() + ':' + value.getIdentifier(),
                "", request.targetField(), request.currentValue(), value, source,
                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                null, true, List.of(claim));
    }

    private static Set<String> currentIds(Object currentValue) {
        Collection<?> values = currentValue instanceof Collection<?> collection
                ? collection : currentValue == null ? List.of() : List.of(currentValue);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof Viewable viewable && viewable.getIdentifier() != null) {
                ids.add(viewable.getIdentifier());
            } else if (value instanceof String id) {
                ids.add(id);
            }
        }
        return Set.copyOf(ids);
    }

    private static EntityRef subjectRef(EnrichmentRequest request) {
        String id = request.subject().targetId() == null
                ? "" : request.subject().targetId().trim();
        return WikidataIds.isQid(id) ? EntityRef.wikidata(id)
                : new EntityRef("domain:"
                        + request.subject().type().toLowerCase(Locale.ROOT), id);
    }
}
