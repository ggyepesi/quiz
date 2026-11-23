package domain.enrichment;

import domain.datasource.EntityRef;
import domain.datasource.SourceRef;
import domain.enrichment.EnrichmentProposal;
import domain.datasource.evidence.EvidenceFragment;
import domain.datasource.evidence.ExtractedClaim;
import domain.datasource.evidence.WikipediaArticle;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.datasource.wikipedia.WikipediaTableRows;
import objectview.Viewable;
import objectview.field.FieldKind;
import wikidata.WikidataIds;
import work.Query;
import work.QueryContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Proposes one configured target-class instance per data row of one retained Wikipedia
 * table. The table structure supplies identity and fields; links may only point at already
 * loaded instances of the separately declared reference target.
 */
public final class WikipediaTableRowInstanceEvidenceProvider
        implements EnrichmentProvider {
    static final String RECIPE_VERSION = "article-table-row-instances-v1";

    private final WikipediaDatasourceProvider.ArticleTableRowInstanceSpec spec;
    private final WikipediaLinkedReferenceResolver linkedCandidates;
    private final String linkedReferenceType;
    private final WikimediaEntityLookup lookup;
    private final wikipedia.WikipediaArticleClient articles;
    private final domain.DomainModel domain;

    public WikipediaTableRowInstanceEvidenceProvider(
            WikipediaDatasourceProvider.ArticleTableRowInstanceSpec spec,
            Collection<? extends Viewable> loadedReferenceCandidates,
            String linkedReferenceType, domain.DomainModel domain) {
        this(spec, loadedReferenceCandidates, linkedReferenceType, domain,
                WikimediaEntityLookup.defaultFetcher());
    }

    WikipediaTableRowInstanceEvidenceProvider(
            WikipediaDatasourceProvider.ArticleTableRowInstanceSpec spec,
            Collection<? extends Viewable> loadedReferenceCandidates,
            String linkedReferenceType,
            domain.DomainModel domain,
            WikimediaEntityLookup.JsonFetcher fetcher) {
        this.spec = java.util.Objects.requireNonNull(spec);
        linkedCandidates = new WikipediaLinkedReferenceResolver(loadedReferenceCandidates);
        this.linkedReferenceType = clean(linkedReferenceType);
        this.domain = java.util.Objects.requireNonNull(domain);
        lookup = new WikimediaEntityLookup(fetcher);
        articles = new wikipedia.WikipediaArticleClient(fetcher::fetch);
    }

    @Override public String name() { return "Wikipedia table-row instances"; }

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
            throw new IllegalArgumentException("Wikipedia table-row instances require a "
                    + "configured table and a reference-list target field");
        }
        return new Query<>() {
            private WikipediaTableRows.Selection selection;
            private int resolvedLinks;
            private int unresolvedLinks;
            private int alreadyRepresentedRows;
            private int linkedMembersProposed;

            @Override public String purpose() {
                return "Build typed instances from one Wikipedia table";
            }

            @Override public String skeleton() {
                return "Read " + spec.description() + "; propose one "
                        + spec.targetClass() + " per data row";
            }

            @Override public String queryType() { return "Retained Wikipedia evidence"; }

            @Override public String description() {
                return "Propose configured row instances and their source evidence for review";
            }

            @Override public Map<String, String> parameters() {
                return Map.of("field", request.targetField(),
                        "section", spec.sectionPath(), "table", spec.table(),
                        "displayColumn", spec.displayColumn(),
                        "targetClass", spec.targetClass());
            }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                WikipediaArticle article = article(request, context);
                selection = WikipediaTableRows.select(article, spec.sectionPath(),
                        spec.table(), spec.displayColumn());
                List<WikipediaLinkedReferenceResolver.Link> links = selection.rows().stream()
                        .flatMap(row -> WikipediaLinkedReferenceResolver
                                .fragmentLinks(row.displayLinks()).stream())
                        .distinct().toList();
                WikipediaLinkedReferenceResolver.LinkResolution resolution =
                        linkedCandidates.resolveEachDetailed(links, lookup, context);
                resolvedLinks = resolution.resolvedLinks();
                unresolvedLinks = resolution.unresolvedLinks().size();

                Set<String> current = currentIds(request.currentValue());
                List<EnrichmentProposal.InstanceCandidate> instances = new ArrayList<>();
                List<EnrichmentProposal.FieldCandidate> fields = new ArrayList<>();
                Map<String, Integer> occurrences = new java.util.HashMap<>();
                for (WikipediaTableRows.SelectedRow row : selection.rows()) {
                    int occurrence = occurrences.merge(rowKey(row), 1, Integer::sum);
                    String instanceId = rowInstanceId(request, row, occurrence);
                    if (current.contains(instanceId)) continue;
                    List<Viewable> resolvedMembers = linkedMembers(
                            row, resolution.matches());
                    SourceRef source = source(article, request, row);
                    EvidenceFragment fragment = EvidenceFragment.excerpt(
                            article.document(), row.sectionPath(), rowEvidence(row));
                    if (resolvedMembers.size() == 1
                            && FieldValueCompatibility.problem(domain,
                                    request.targetSchema(), resolvedMembers.getFirst()) == null) {
                        Viewable member = resolvedMembers.getFirst();
                        if (current.contains(member.getIdentifier())) {
                            alreadyRepresentedRows++;
                        } else {
                            fields.add(linkedMemberCandidate(
                                    request, member, source, fragment));
                            linkedMembersProposed++;
                        }
                        continue;
                    }
                    List<EnrichmentProposal.TypedReference> linked = linkedReferences(
                            row, resolution.matches());
                    ExtractedClaim contentClaim = new ExtractedClaim(subjectRef(request),
                            semanticProperty(request.targetField()), rowEvidence(row), null,
                            List.of(fragment), "configured Wikipedia table data row",
                            RECIPE_VERSION, "", 0.95,
                            List.of("The configured source structure defines one row instance; "
                                    + "review decides whether it belongs in this field."));

                    List<EnrichmentProposal.InstanceField> instanceFields = new ArrayList<>();
                    instanceFields.add(EnrichmentProposal.InstanceField.literal(
                            spec.detailsField(), row.cells().stream()
                                    .filter(cell -> !cell.text().isBlank())
                                    .map(cell -> cell.column() + ": " + cell.text()).toList()));
                    instanceFields.add(EnrichmentProposal.InstanceField.references(
                            spec.sourceField(), List.of(new EnrichmentProposal.TypedReference(
                                    request.subject().type(), request.subject().targetId()))));
                    if (!spec.referencesField().isBlank() && !linked.isEmpty()) {
                        instanceFields.add(EnrichmentProposal.InstanceField.references(
                                spec.referencesField(), linked));
                    }

                    String label = row.displayText().isBlank()
                            ? spec.targetClass() + " table row" : row.displayText();
                    instances.add(new EnrichmentProposal.InstanceCandidate(
                            "table-row-instance:" + instanceId, spec.targetClass(), instanceId,
                            label, instanceFields, source, List.of(contentClaim), true));

                    domain.transform.DynamicViewable proposed =
                            new domain.transform.DynamicViewable(instanceId, label);
                    proposed.type(spec.targetClass());
                    ExtractedClaim referenceClaim = new ExtractedClaim(subjectRef(request),
                            semanticProperty(request.targetField()), null,
                            new EntityRef("wikipedia-table-row", instanceId),
                            List.of(fragment), "reviewed Wikipedia table-row instance",
                            RECIPE_VERSION, "", 0.95,
                            List.of("The configured table row creates this typed instance; "
                                    + "unresolved links never create another entity."));
                    fields.add(new EnrichmentProposal.FieldCandidate(
                            "table-row-reference:" + instanceId, "", request.targetField(),
                            request.currentValue(), proposed, source,
                            EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                            null, true, List.of(referenceClaim)));
                }
                return new EnrichmentProposal(
                        request.subject(), List.of(), fields, List.of(), instances);
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.instances().size();
            }

            @Override public String summary(EnrichmentProposal result) {
                if (selection == null) return "No table rows read";
                return selection.matchingSections() + " matching section(s), "
                        + selection.matchingTables() + " matching table(s), "
                        + selection.rows().size() + " data row(s); "
                        + resolvedLinks + " display-cell link(s) resolved, "
                        + unresolvedLinks + " unresolved; " + rowCount(result)
                        + " member(s) proposed (" + linkedMembersProposed
                        + " loaded linked member(s), "
                        + (result == null ? 0 : result.instances().size())
                        + " table-row instance(s))"
                        + (alreadyRepresentedRows == 0 ? "" : "; "
                                + alreadyRepresentedRows
                                + " row(s) already represented by current members");
            }
        };
    }

    private static EnrichmentProposal.FieldCandidate linkedMemberCandidate(
            EnrichmentRequest request, Viewable member, SourceRef source,
            EvidenceFragment fragment) {
        String semantic = semanticProperty(request.targetField());
        String memberId = member.getIdentifier();
        EntityRef entity = WikidataIds.isQid(memberId)
                ? EntityRef.wikidata(memberId)
                : new EntityRef("domain:" + member.typeName().toLowerCase(Locale.ROOT),
                        memberId);
        ExtractedClaim claim = new ExtractedClaim(subjectRef(request), semantic, null,
                entity, List.of(fragment),
                "linked target in configured Wikipedia table row", RECIPE_VERSION, "",
                0.95, List.of("The configured display-cell link identifies an already-"
                        + "loaded compatible instance; that instance represents the row."));
        return new EnrichmentProposal.FieldCandidate(
                "table-row-linked-member:" + request.subject().targetId() + ':'
                        + request.targetField() + ':' + memberId,
                "", request.targetField(), request.currentValue(), member, source,
                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                null, true, List.of(claim));
    }

    private static List<Viewable> linkedMembers(
            WikipediaTableRows.SelectedRow row,
            Map<WikipediaLinkedReferenceResolver.Link, Viewable> matches) {
        LinkedHashMap<String, Viewable> result = new LinkedHashMap<>();
        for (WikipediaLinkedReferenceResolver.Link link :
                WikipediaLinkedReferenceResolver.fragmentLinks(row.displayLinks())) {
            Viewable value = matches.get(link);
            if (value == null || value.getIdentifier() == null) continue;
            result.putIfAbsent(value.typeName() + '\0' + value.getIdentifier(), value);
        }
        return List.copyOf(result.values());
    }

    private List<EnrichmentProposal.TypedReference> linkedReferences(
            WikipediaTableRows.SelectedRow row,
            Map<WikipediaLinkedReferenceResolver.Link, Viewable> matches) {
        if (spec.referencesField().isBlank() || linkedReferenceType.isBlank()) return List.of();
        LinkedHashMap<String, EnrichmentProposal.TypedReference> result =
                new LinkedHashMap<>();
        for (WikipediaLinkedReferenceResolver.Link link :
                WikipediaLinkedReferenceResolver.fragmentLinks(row.displayLinks())) {
            Viewable value = matches.get(link);
            if (value == null || value.getIdentifier() == null) continue;
            result.putIfAbsent(value.getIdentifier(), new EnrichmentProposal.TypedReference(
                    linkedReferenceType, value.getIdentifier()));
        }
        return List.copyOf(result.values());
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

    private static SourceRef source(
            WikipediaArticle article,
            EnrichmentRequest request,
            WikipediaTableRows.SelectedRow row) {
        String semantic = semanticProperty(request.targetField());
        String sourceLabel = row.sectionPath() + " — " + row.table()
                + " — " + row.displayColumn();
        return new SourceRef(article.document().kind(), article.document().sourceId(),
                article.document().url(), semantic, sourceLabel, "structured table row");
    }

    /**
     * A row's identity is its owner, target field, configured table and display cell,
     * plus its occurrence among rows with the same display cell. It deliberately leaves
     * out the other cells, so an edit to a task or provenance cell in the article keeps
     * the accepted instance instead of proposing a second one; the occurrence keeps two
     * rows that share a display label apart. Renaming the display cell is a new row.
     */
    private static String rowInstanceId(
            EnrichmentRequest request,
            WikipediaTableRows.SelectedRow row,
            int occurrence) {
        String material = request.subject().type() + '\0' + request.subject().targetId()
                + '\0' + request.targetField() + '\0' + rowKey(row) + '\0' + occurrence;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "wikipedia-table-row:" + HexFormat.of().formatHex(digest)
                    .substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String rowKey(WikipediaTableRows.SelectedRow row) {
        return row.sectionPath() + '\0' + row.table() + '\0' + row.displayColumn()
                + '\0' + row.displayText();
    }

    private static String rowEvidence(WikipediaTableRows.SelectedRow row) {
        return row.cells().stream().filter(cell -> !cell.text().isBlank())
                .map(cell -> cell.column() + ": " + cell.text())
                .collect(java.util.stream.Collectors.joining("\n"));
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
        String id = clean(request.subject().targetId());
        return WikidataIds.isQid(id) ? EntityRef.wikidata(id)
                : new EntityRef("domain:"
                        + request.subject().type().toLowerCase(Locale.ROOT), id);
    }

    private static String semanticProperty(String field) {
        return "field:" + clean(field);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
