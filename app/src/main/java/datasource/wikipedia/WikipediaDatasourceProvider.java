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
    /** The infobox on that article, as versioned structured values rather than prose. */
    public static final String INFOBOX = "infobox";
    /** One configured source parameter or rendered row interpreted as a model field value. */
    public static final String INFOBOX_PARAMETER = "infobox-parameter";
    public static final String FAMILY_INFOBOX_FIELD = "wikipedia-infobox-field";
    /** The article's lead text, without later sections. */
    public static final String ARTICLE_INTRODUCTION = "article-introduction";
    public static final String ARTICLE_TEXT_PROJECTION = "projection";
    public static final String FAMILY_ARTICLE_INTRODUCTION_FIELD =
            "wikipedia-article-introduction-field";
    /** Exact mentions in a retained introduction proposed for review as references. */
    public static final String ARTICLE_INTRODUCTION_MENTIONS =
            "article-introduction-mentions";
    public static final String FAMILY_ARTICLE_INTRODUCTION_MENTIONS =
            "wikipedia-article-introduction-mentions";
    /** Bounded, section-labelled full-article prose proposed for review as text. */
    public static final String ARTICLE_PASSAGES = "article-passages";
    public static final String FAMILY_ARTICLE_PASSAGES = "wikipedia-article-passages";
    /** Reviewable target-class instances, one per bounded article passage. */
    public static final String ARTICLE_PASSAGE_INSTANCES = "article-passage-instances";
    public static final String FAMILY_ARTICLE_PASSAGE_INSTANCES =
            "wikipedia-article-passage-instances";
    public static final String PASSAGE_TEXT_FIELD = "passageTextField";
    public static final String PASSAGE_SOURCE_FIELD = "passageSourceField";
    public static final int MAX_ARTICLE_PASSAGES = 40;
    public static final int MAX_ARTICLE_PASSAGE_CHARACTERS = 20_000;

    private final List<DatasourceOperation> operations = List.of(
            new WikipediaCategoryDiscoveryOperation(),
            new InfoboxParameter(),
            new ArticleIntroduction(),
            new ArticleIntroductionMentions(),
            new ArticlePassages(),
            new ArticlePassageInstances(),
            document(ARTICLE, "Article",
                    "Retrieve the article an entity corresponds to, with the digest that "
                            + "says which revision was read."),
            document(INFOBOX, "Infobox values",
                    "The template parameters and rendered label/value rows — structured "
                            + "evidence versioned independently of surrounding prose."));

    private record InfoboxParameter() implements DatasourceOperation {
        @Override public String id() { return INFOBOX_PARAMETER; }
        @Override public String displayName() { return "Infobox value"; }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(new ParameterDescriptor("property",
                    "Template.parameter or Rendered.label",
                    ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                    "The source parameter or rendered label/value row supplying this field."));
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

    private record ArticleIntroduction() implements DatasourceOperation {
        @Override public String id() { return ARTICLE_INTRODUCTION; }
        @Override public String displayName() { return "Article introduction"; }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(new ParameterDescriptor(ARTICLE_TEXT_PROJECTION,
                    "Article text", ParameterDescriptor.Kind.CHOICE, false,
                    ArticleTextProjection.INTRODUCTION.name(),
                    java.util.Arrays.stream(ArticleTextProjection.values())
                            .map(Enum::name).toList(),
                    "Use the complete introduction, its first paragraph, or its first "
                            + "sentence. The target field assigns the semantic meaning."));
        }
        @Override public SourceValueSchema outputSchema() {
            return new SourceValueSchema(SourceValueKind.MODEL_VALUE, false, "");
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            ArticleTextProjection projection = articleTextProjection(binding);
            return new PreparedSourceOperation(FAMILY_ARTICLE_INTRODUCTION_FIELD,
                    "Wikipedia article text",
                    PreparedSourceOperation.Execution.ACQUIRE,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — English Wikipedia " + projection.displayName(),
                    java.util.Map.of(
                            "input", "Wikipedia article linked from the entity ID",
                            "operation", "Read the " + projection.displayName(),
                            "output", "Fill the configured text field"),
                    projection, articleCorrespondence());
        }
    }

    public enum ArticleTextProjection {
        INTRODUCTION("complete article introduction") {
            @Override public String project(datasource.evidence.ArticleIntroduction value) {
                return value == null ? "" : value.text();
            }
        },
        LEAD_PARAGRAPH("lead paragraph") {
            @Override public String project(datasource.evidence.ArticleIntroduction value) {
                return value == null ? "" : value.leadParagraph();
            }
        },
        LEAD_SENTENCE("lead sentence") {
            @Override public String project(datasource.evidence.ArticleIntroduction value) {
                return value == null ? "" : value.leadSentence();
            }
        };

        private final String displayName;

        ArticleTextProjection(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
        public abstract String project(datasource.evidence.ArticleIntroduction value);
    }

    /**
     * A curation recipe, not an automatic value acquisition. The retained introduction
     * is interpreted against instances already admitted to the field's target class;
     * matches are review candidates and therefore remain RETAIN work in generation.
     */
    private record ArticleIntroductionMentions() implements DatasourceOperation {
        @Override public String id() { return ARTICLE_INTRODUCTION_MENTIONS; }
        @Override public String displayName() { return "Article introduction mentions"; }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() { return List.of(); }
        @Override public SourceValueSchema outputSchema() {
            return SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE, "wikidata");
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            return new PreparedSourceOperation(FAMILY_ARTICLE_INTRODUCTION_MENTIONS,
                    "Wikipedia article introduction mentions",
                    PreparedSourceOperation.Execution.RETAIN,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — review typed instances mentioned in the retained "
                            + "Wikipedia introduction",
                    java.util.Map.of(
                            "input", "Retained versioned article introduction and loaded "
                                    + "instances of the field's target class",
                            "operation", "Find exact display-label mentions",
                            "output", "Propose matching references for explicit review"),
                    ArticleIntroductionMentionSpec.INSTANCE,
                    articleCorrespondence());
        }
    }

    /** Marker configuration: the review operation has no user-entered parameter. */
    public enum ArticleIntroductionMentionSpec { INSTANCE }

    /** Explicit curation over one bounded full article. It retains prose as prose: no
     * entity or event is inferred merely because the article names it. */
    private record ArticlePassages() implements DatasourceOperation {
        @Override public String id() { return ARTICLE_PASSAGES; }
        @Override public String displayName() { return "Bounded article passages"; }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() { return List.of(); }
        @Override public SourceValueSchema outputSchema() {
            return new SourceValueSchema(SourceValueKind.MODEL_VALUE, false, "");
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            return new PreparedSourceOperation(FAMILY_ARTICLE_PASSAGES,
                    "Wikipedia article passages",
                    PreparedSourceOperation.Execution.RETAIN,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — review bounded text from the full article",
                    java.util.Map.of(
                            "input", "One versioned full Wikipedia article per selected member",
                            "operation", "Keep at most " + MAX_ARTICLE_PASSAGES
                                    + " content passages and "
                                    + MAX_ARTICLE_PASSAGE_CHARACTERS + " characters",
                            "output", "Propose section-labelled text for explicit review"),
                    ArticlePassageSpec.INSTANCE,
                    articleCorrespondence());
        }
    }

    public enum ArticlePassageSpec { INSTANCE }

    /**
     * The field receives references to reviewed instances of its declared target class.
     * The target class says where passage text and the source instance are stored; no
     * class or field name is inferred by the provider.
     */
    private record ArticlePassageInstances() implements DatasourceOperation {
        @Override public String id() { return ARTICLE_PASSAGE_INSTANCES; }
        @Override public String displayName() { return "Article passage instances"; }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(
                    new ParameterDescriptor(PASSAGE_TEXT_FIELD, "Passage text field",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Text field on the proposed target-class instance"),
                    new ParameterDescriptor(PASSAGE_SOURCE_FIELD, "Source instance field",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Reference field on the proposed instance that receives the source"));
        }
        @Override public SourceValueSchema outputSchema() {
            return SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE, "model");
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            ArticlePassageInstanceSpec spec = new ArticlePassageInstanceSpec(
                    binding.recipe().parameter(PASSAGE_TEXT_FIELD),
                    binding.recipe().parameter(PASSAGE_SOURCE_FIELD));
            return new PreparedSourceOperation(FAMILY_ARTICLE_PASSAGE_INSTANCES,
                    "Wikipedia article passage instances",
                    PreparedSourceOperation.Execution.RETAIN,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — review one target-class instance per bounded passage",
                    java.util.Map.of(
                            "input", "One versioned full Wikipedia article per selected member",
                            "operation", "Propose one instance per bounded content passage",
                            "output", "Fill " + spec.textField() + " with the passage and "
                                    + spec.sourceField() + " with the selected source instance"),
                    spec, articleCorrespondence());
        }
    }

    public record ArticlePassageInstanceSpec(String textField, String sourceField) {
        public ArticlePassageInstanceSpec {
            textField = textField == null ? "" : textField.trim();
            sourceField = sourceField == null ? "" : sourceField.trim();
        }
        public boolean configured() {
            return !textField.isBlank() && !sourceField.isBlank();
        }
    }

    private static List<SourceInputRequirement> articleCorrespondence() {
        return List.of(new SourceInputRequirement(datasource.EntityRef.WIKIDATA,
                SourceInputRequirement.Kind.ARTICLE_CORRESPONDENCE));
    }

    /**
     * The infobox value this binding makes a field take, or null if it makes it
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

    public static boolean articleIntroduction(SourceBinding binding) {
        if (binding == null || binding.target() == null
                || binding.target().scope() != BindingScope.FIELD_VALUE) return false;
        SourceBindingSlot slot = binding.target().slot();
        if (slot != SourceBindingSlot.PRIMARY_FIELD_VALUE
                && slot != SourceBindingSlot.FALLBACK_FIELD_VALUE) return false;
        SourceRecipe recipe = binding.recipe();
        return recipe != null && ID.equals(recipe.providerId())
                && ARTICLE_INTRODUCTION.equals(recipe.operationId());
    }

    /** The configured projection of the one retained introduction, or null when this
     * binding is not the article-text operation. Blank legacy recipes mean the complete
     * introduction. */
    public static ArticleTextProjection articleTextProjection(SourceBinding binding) {
        if (!articleIntroduction(binding)) return null;
        String configured = binding.recipe().parameter(ARTICLE_TEXT_PROJECTION);
        if (configured == null || configured.isBlank()) {
            return ArticleTextProjection.INTRODUCTION;
        }
        try {
            return ArticleTextProjection.valueOf(configured.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Unknown Wikipedia article-text projection: " + configured, invalid);
        }
    }

    public static boolean articleIntroductionMentions(SourceBinding binding) {
        if (binding == null || binding.target() == null
                || binding.target().scope() != BindingScope.FIELD_VALUE
                || binding.target().slot() != SourceBindingSlot.CANDIDATE_EVIDENCE) {
            return false;
        }
        SourceRecipe recipe = binding.recipe();
        return recipe != null && ID.equals(recipe.providerId())
                && ARTICLE_INTRODUCTION_MENTIONS.equals(recipe.operationId());
    }

    public static boolean articlePassages(SourceBinding binding) {
        if (binding == null || binding.target() == null
                || binding.target().scope() != BindingScope.FIELD_VALUE
                || binding.target().slot() != SourceBindingSlot.CANDIDATE_EVIDENCE) {
            return false;
        }
        SourceRecipe recipe = binding.recipe();
        return recipe != null && ID.equals(recipe.providerId())
                && ARTICLE_PASSAGES.equals(recipe.operationId());
    }

    public static ArticlePassageInstanceSpec articlePassageInstances(SourceBinding binding) {
        if (!articlePassageInstanceOperation(binding)) return null;
        SourceRecipe recipe = binding.recipe();
        ArticlePassageInstanceSpec spec = new ArticlePassageInstanceSpec(
                recipe.parameter(PASSAGE_TEXT_FIELD),
                recipe.parameter(PASSAGE_SOURCE_FIELD));
        return spec.configured() ? spec : null;
    }

    /** Whether this is the passage-instance operation, including an incomplete recipe
     * that the editor must still be able to display and repair. */
    public static boolean articlePassageInstanceOperation(SourceBinding binding) {
        if (binding == null || binding.target() == null
                || binding.target().scope() != BindingScope.FIELD_VALUE
                || binding.target().slot() != SourceBindingSlot.CANDIDATE_EVIDENCE) {
            return false;
        }
        SourceRecipe recipe = binding.recipe();
        return recipe != null && ID.equals(recipe.providerId())
                && ARTICLE_PASSAGE_INSTANCES.equals(recipe.operationId());
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
