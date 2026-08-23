package datasource.wikipedia;

import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;

import datasource.api.BindingScope;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;

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
            implements DatasourceOperation {

        @Override public BindingScope scope() { return BindingScope.DOCUMENT_EVIDENCE; }

        @Override public List<ParameterDescriptor> parameters() {
            return List.of(new ParameterDescriptor("wiki", "Wiki",
                    ParameterDescriptor.Kind.TEXT, false, "enwiki", List.of(),
                    "Which Wikipedia the document is read from."));
        }

        @Override public SourceValueSchema outputSchema() {
            return new SourceValueSchema(SourceValueKind.DOCUMENT, false, "");
        }
    }
}
