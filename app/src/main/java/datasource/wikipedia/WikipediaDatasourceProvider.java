package datasource.wikipedia;

import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;

import datasource.api.BindingScope;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;
import datasource.api.SourceReferenceSchema;
import datasource.api.acquisition.SourceAcquisitionOperation;
import datasource.api.acquisition.SourceAcquisitionRequest;
import datasource.EntityRef;
import datasource.evidence.SourceDocument;
import work.Query;
import work.QueryContext;

import java.util.List;

/** Wikipedia capabilities exposed without application-side provider branching. */
public final class WikipediaDatasourceProvider implements DatasourceProvider {
    public static final String ID = "wikipedia";

    /** The article itself, which everything else about an entity rides. */
    public static final String ARTICLE = "article";
    /** The infobox on that article, as versioned parameters rather than prose. */
    public static final String INFOBOX = "infobox";

    private final List<DatasourceOperation> operations = List.of(
            new WikipediaCategoryDiscoveryOperation(),
            document(ARTICLE, "Article",
                    "Retrieve the article an entity corresponds to, with the digest that "
                            + "says which revision was read."),
            document(INFOBOX, "Infobox parameters",
                    "The template's parameters, which are what the page SAID rather than "
                            + "what a field holds — evidence, versioned by a digest that "
                            + "follows the parameters and not the surrounding prose."));

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Wikipedia"; }
    @Override public List<? extends DatasourceOperation> operations() { return operations; }

    private static DatasourceOperation document(
            String id, String displayName, String help) {
        return new DocumentEvidence(id, displayName, help);
    }

    /**
     * Evidence retrieved about an entity, not a value of one of its fields.
     *
     * <p>It yields a document — what the source said, and when — which a configured
     * recipe then interprets into field values. Keeping the two apart is what lets a
     * category mean whatever the field's declared rule says it means, rather than
     * whatever the reader of the acquisition code assumed.
     *
     * <p>Bound at a class, because retrieval is per entity and needs the correspondence
     * Wikidata's sitelink supplies. Nothing here can be bound to a field: a document is
     * not a field value, and {@link SourceValueKind#DOCUMENT} says so.
     */
    private record DocumentEvidence(String id, String displayName, String help)
            implements SourceAcquisitionOperation<List<SourceDocument>> {

        @Override public BindingScope scope() { return BindingScope.DOCUMENT_EVIDENCE; }

        @Override public List<ParameterDescriptor> parameters() {
            return List.of(new ParameterDescriptor("wiki", "Wiki",
                            ParameterDescriptor.Kind.TEXT, false, "enwiki", List.of(),
                            "Which Wikipedia the document is read from."));
        }

        @Override public List<SourceReferenceSchema> inputReferences() {
            return List.of(new SourceReferenceSchema(
                    ID, SourceReferenceSchema.Kind.RECORD, true));
        }

        @Override public SourceValueSchema outputSchema() {
            return new SourceValueSchema(SourceValueKind.DOCUMENT, false, "");
        }

        @Override public Query<List<SourceDocument>> acquire(
                SourceAcquisitionRequest request) {
            SourceAcquisitionRequest safe = request == null
                    ? new SourceAcquisitionRequest(List.of(), java.util.Map.of()) : request;
            String wiki = safe.parameter("wiki").trim();
            if (wiki.isBlank()) wiki = "enwiki";
            if (!"enwiki".equalsIgnoreCase(wiki)) {
                throw new IllegalArgumentException(
                        "Wikipedia acquisition currently supports enwiki, not " + wiki);
            }
            List<String> titles = safe.subjects().stream()
                    .filter(ref -> ID.equalsIgnoreCase(ref.namespace()))
                    .map(EntityRef::id).map(String::trim).filter(title -> !title.isBlank())
                    .distinct().toList();
            if (titles.isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one Wikipedia page reference is required");
            }
            String operation = id;
            String selectedWiki = wiki;
            return new Query<>() {
                @Override public String purpose() {
                    return ARTICLE.equals(operation)
                            ? "Acquire Wikipedia articles" : "Acquire Wikipedia infoboxes";
                }
                @Override public String skeleton() {
                    return ARTICLE.equals(operation)
                            ? "page -> versioned article document"
                            : "page -> versioned infobox document";
                }
                @Override public String queryType() { return "Wikipedia API"; }
                @Override public java.util.Map<String, String> parameters() {
                    return java.util.Map.of("wiki", selectedWiki,
                            "pages", Integer.toString(titles.size()));
                }
                @Override public List<SourceDocument> execute(QueryContext context)
                        throws Exception {
                    java.util.ArrayList<SourceDocument> documents = new java.util.ArrayList<>();
                    wikipedia.WikipediaArticleClient articleClient = ARTICLE.equals(operation)
                            ? new wikipedia.WikipediaArticleClient() : null;
                    wikipedia.WikipediaInfoboxClient infoboxClient = ARTICLE.equals(operation)
                            ? null : new wikipedia.WikipediaInfoboxClient();
                    for (String title : titles) {
                        context.cancellation().throwIfCancelled();
                        if (ARTICLE.equals(operation)) {
                            wikipedia.WikipediaArticleClient.Article article =
                                    articleClient.byTitle(title).execute(context);
                            if (article != null) documents.add(article.document());
                        } else {
                            datasource.evidence.InfoboxParameters infobox =
                                    infoboxClient.byTitle(title).execute(context);
                            if (infobox != null) documents.add(infobox.document());
                        }
                    }
                    return List.copyOf(documents);
                }
                @Override public int rowCount(List<SourceDocument> result) {
                    return result == null ? 0 : result.size();
                }
                @Override public String summary(List<SourceDocument> result) {
                    return rowCount(result) + " versioned Wikipedia document(s)";
                }
            };
        }
    }
}
