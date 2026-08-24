package datasource.wikipedia;

import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;

import datasource.api.BindingScope;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;
import datasource.api.SourceReferenceSchema;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceRecipe;
import datasource.api.PreparedSourceOperation;
import datasource.api.SourceInputRequirement;

import java.util.List;

/** Wikipedia capabilities exposed without application-side provider branching. */
public final class WikipediaDatasourceProvider implements DatasourceProvider {
    public static final String ID = "wikipedia";

    /** The article itself, which everything else about an entity rides. */
    public static final String ARTICLE = "article";
    /** The infobox on that article, as versioned parameters rather than prose. */
    public static final String INFOBOX = "infobox";
    /** One configured parameter interpreted as a model field value. */
    public static final String INFOBOX_PARAMETER = "infobox-parameter";
    public static final String FAMILY_INFOBOX_FIELD = "wikipedia-infobox-field";

    private final List<DatasourceOperation> operations = List.of(
            new WikipediaCategoryDiscoveryOperation(),
            new InfoboxParameter(),
            document(ARTICLE, "Article",
                    "Retrieve the article an entity corresponds to, with the digest that "
                            + "says which revision was read."),
            document(INFOBOX, "Infobox parameters",
                    "The template's parameters, which are what the page SAID rather than "
                            + "what a field holds — evidence, versioned by a digest that "
                            + "follows the parameters and not the surrounding prose."));

    private record InfoboxParameter() implements DatasourceOperation {
        @Override public String id() { return INFOBOX_PARAMETER; }
        @Override public String displayName() { return "Infobox parameter"; }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(new ParameterDescriptor("property", "Template.parameter",
                    ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                    "The infobox template and parameter supplying this field."));
        }
        @Override public SourceValueSchema outputSchema() {
            return new SourceValueSchema(SourceValueKind.MODEL_VALUE, true, "");
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            String parameter = infoboxParameter(binding);
            // A recipe still being filled in is DESCRIBED, not thrown. Every operation
            // compiles a plan, so throwing here takes Generate, Enrich and Remap down
            // at their first line with a stack trace instead of a run that says which
            // field is unfinished and carries on without it.
            if (parameter == null) return new PreparedSourceOperation(
                    FAMILY_INFOBOX_FIELD, "Wikipedia infobox field",
                    PreparedSourceOperation.Execution.RETAIN,
                    "Incomplete Wikipedia infobox recipe at "
                            + binding.target().className() + "."
                            + binding.target().fieldPath(),
                    java.util.Map.of(), null);
            return new PreparedSourceOperation(FAMILY_INFOBOX_FIELD,
                    "Wikipedia infobox field", PreparedSourceOperation.Execution.ACQUIRE,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — native Infobox " + parameter,
                    java.util.Map.of(
                            "input", "Wikipedia pages linked from reachable entity IDs",
                            "operation", "Parse " + parameter + " from versioned infoboxes",
                            "output", "Fill values according to the configured source slot"),
                    parameter, articleCorrespondence());
        }
    }

    private static List<SourceInputRequirement> articleCorrespondence() {
        return List.of(new SourceInputRequirement(datasource.EntityRef.WIKIDATA,
                SourceInputRequirement.Kind.ARTICLE_CORRESPONDENCE));
    }

    /**
     * The infobox parameter this binding makes a field take, or null if it makes it
     * take none.
     *
     * <p>ONE predicate, because three things ask the same question: the plan message
     * counts these bindings, the phase explanation describes them before the run, and
     * acquisition performs them. Derived separately, a run can disagree with its own
     * description — and the description is what the reader trusts, because it arrives
     * first.
     */
    public static String infoboxParameter(SourceBinding binding) {
        if (binding == null || binding.target() == null) return null;
        if (binding.target().scope() != BindingScope.FIELD_VALUE) return null;
        SourceBindingSlot slot = binding.target().slot();
        if (slot != SourceBindingSlot.PRIMARY_FIELD_VALUE
                && slot != SourceBindingSlot.FALLBACK_FIELD_VALUE) return null;
        SourceRecipe recipe = binding.recipe();
        if (recipe == null || !ID.equals(recipe.providerId())
                || !INFOBOX_PARAMETER.equals(recipe.operationId())) return null;
        String parameter = recipe.parameter("property");
        return parameter == null || parameter.isBlank() ? null : parameter;
    }

    /** The category interpretation declared at this field target, if any. */
    public record CategoryRule(String pattern, String policy) { }

    /** Where a category title carries the field's value. A pattern without exactly one
     *  is not a template, so it names no acquisition — the same thing the model
     *  validator refuses, said here so a run finds out before it fetches. */
    public static final String VALUE_PLACEHOLDER = "<value>";

    public static CategoryRule categoryRule(SourceBinding binding) {
        if (binding == null || binding.target() == null
                || binding.target().scope() != BindingScope.FIELD_VALUE
                || binding.target().slot() != SourceBindingSlot.CATEGORY_EVIDENCE) {
            return null;
        }
        SourceRecipe recipe = binding.recipe();
        if (recipe == null || !ID.equals(recipe.providerId())
                || !WikipediaCategoryDiscoveryOperation.ID.equals(recipe.operationId())) {
            return null;
        }
        String pattern = recipe.parameter(WikipediaCategoryDiscoveryOperation.PATTERN);
        // A pattern still being typed matches nothing, and admitting it cost a whole
        // pool's sitelinks and categories on the Enrich path, which acquires BEFORE it
        // compiles — so the model was refused only after the fetching was done.
        if (pattern == null
                || pattern.split(java.util.regex.Pattern.quote(VALUE_PLACEHOLDER), -1)
                        .length - 1 != 1) {
            return null;
        }
        // The policy default is stated here as a STRING rather than reached for as
        // CategoryCandidatePolicy.REVIEW: this package describes sources for any model
        // and must not depend on one. The descriptor declares the same default.
        String policy = recipe.parameter(WikipediaCategoryDiscoveryOperation.POLICY);
        if (policy == null || policy.isBlank()) policy = "REVIEW";
        return new CategoryRule(pattern, policy);
    }

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
     * <p>It describes a document — what the source said, and when — which a configured
     * recipe then interprets into field values. Keeping the two apart is what lets a
     * category mean whatever the field's declared rule says it means, rather than
     * whatever the reader of the acquisition code assumed.
     *
     * <p>Bound at a class, because retrieval is per entity and needs the correspondence
     * Wikidata's sitelink supplies. Nothing here can be bound to a field: a document is
     * not a field value, and {@link SourceValueKind#DOCUMENT} says so. Until a document
     * family is bound to the shared runner this remains a RETAIN declaration, rather
     * than pretending a dead query method makes it executable.
     */
    private record DocumentEvidence(String id, String displayName, String help)
            implements DatasourceOperation {

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

    }
}
