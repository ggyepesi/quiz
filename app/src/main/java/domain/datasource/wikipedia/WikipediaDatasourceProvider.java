package domain.datasource.wikipedia;

import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.DatasourceProvider;

import domain.datasource.api.BindingScope;
import domain.datasource.api.ParameterDescriptor;
import domain.datasource.api.SourceValueKind;
import domain.datasource.api.SourceValueSchema;
import domain.datasource.api.SourceReferenceSchema;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceRecipe;
import domain.datasource.api.PreparedSourceOperation;
import domain.datasource.api.SourceInputRequirement;
import domain.datasource.api.ModelConfigurationReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** Links retained in one rendered infobox row, proposed as typed references. */
    public static final String INFOBOX_ROW_LINKS = "infobox-row-links";
    /** Links from one explicitly selected column of a retained article table. */
    public static final String ARTICLE_TABLE_COLUMN_LINKS = "article-table-column-links";
    public static final String FAMILY_ARTICLE_TABLE_COLUMN_LINKS =
            "wikipedia-article-table-column-links";
    /** Typed model instances proposed from the data rows of one retained article table. */
    public static final String ARTICLE_TABLE_ROW_INSTANCES = "article-table-row-instances";
    public static final String FAMILY_ARTICLE_TABLE_ROW_INSTANCES =
            "wikipedia-article-table-row-instances";
    public static final String TABLE_SECTION = "section";
    public static final String TABLE_NAME = "table";
    public static final String TABLE_COLUMN = "column";
    public static final String TABLE_ROW_TARGET_CLASS = "rowTargetClass";
    public static final String TABLE_ROW_DETAILS_FIELD = "rowDetailsField";
    public static final String TABLE_ROW_SOURCE_FIELD = "rowSourceField";
    public static final String TABLE_ROW_REFERENCES_FIELD = "rowReferencesField";
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
    public static final String PASSAGE_BLOCK_KIND = "blockKind";
    public static final String TEXT_PASSAGE_INSTANCES = "text-passage-instances";
    public static final String FAMILY_TEXT_PASSAGE_INSTANCES = "mediawiki-text-passage-instances";
    public static final String TEXT_SITE = "site";
    public static final String TEXT_PAGES = "pages";
    public static final String TEXT_DIVISION = "division";
    public static final String PASSAGE_TITLE_FIELD = "passageTitleField";
    public static final String PASSAGE_REFERENCES_FIELD = "passageReferencesField";
    public static final int MAX_ARTICLE_PASSAGES = 40;
    public static final int MAX_ARTICLE_PASSAGE_CHARACTERS = 20_000;

    private final List<DatasourceOperation> operations = List.of(
            new WikipediaCategoryPopulationOperation(),
            new WikipediaCategoryDiscoveryOperation(),
            new InfoboxParameter(),
            new InfoboxRowLinks(),
            new ArticleTableColumnLinks(),
            new ArticleTableRowInstances(),
            new ArticleIntroduction(),
            new ArticleIntroductionMentions(),
            new ArticlePassages(),
            new ArticlePassageInstances(),
            new TextPassageInstances(),
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
                    new InfoboxValueSpec(parameter, true), articleCorrespondence());
        }
    }

    private record InfoboxRowLinks() implements DatasourceOperation {
        @Override public String id() { return INFOBOX_ROW_LINKS; }
        @Override public String displayName() { return "Rendered infobox row links"; }
        @Override public String summary(SourceBinding binding) {
            String key = infoboxRowLinks(binding);
            return "Infobox row links" + (key == null ? " — incomplete" : " — " + key);
        }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(new ParameterDescriptor("property", "Rendered infobox row",
                    ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                    "The rendered infobox row whose retained article links are proposed "
                            + "as references."));
        }
        @Override public SourceValueSchema outputSchema() {
            return SourceValueSchema.collection(
                    SourceValueKind.ENTITY_REFERENCE, domain.datasource.EntityRef.WIKIDATA);
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            String parameter = infoboxRowLinks(binding);
            if (parameter == null) return new PreparedSourceOperation(
                    FAMILY_INFOBOX_FIELD, "Wikipedia infobox evidence",
                    PreparedSourceOperation.Execution.RETAIN,
                    "Incomplete rendered-infobox link recipe at "
                            + binding.target().className() + "."
                            + binding.target().fieldPath(),
                    java.util.Map.of(), null);
            return new PreparedSourceOperation(FAMILY_INFOBOX_FIELD,
                    "Wikipedia infobox evidence",
                    PreparedSourceOperation.Execution.ACQUIRE,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — review links retained in " + parameter,
                    java.util.Map.of(
                            "input", "Versioned rendered infobox row on each reachable entity",
                            "operation", "Retain its linked Wikipedia article targets",
                            "output", "Propose loaded target-class instances for review"),
                    new InfoboxValueSpec(parameter, false), articleCorrespondence());
        }
    }

    /** A structured table column is authored source structure, but its links still
     * nominate references for review; they never admit or mutate model instances. */
    private record ArticleTableColumnLinks() implements DatasourceOperation {
        @Override public String id() { return ARTICLE_TABLE_COLUMN_LINKS; }
        @Override public String displayName() { return "Article table-column links"; }
        @Override public String summary(SourceBinding binding) {
            ArticleTableColumnLinkSpec spec = articleTableColumnLinks(binding);
            return "Table-column links" + (spec == null ? " — incomplete"
                    : " — " + spec.description());
        }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(
                    new ParameterDescriptor(TABLE_SECTION, "Section path",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Exact heading path shown by Wikipedia Discovery, separated by >."),
                    new ParameterDescriptor(TABLE_NAME, "Table caption or name",
                            ParameterDescriptor.Kind.TEXT, false, "", List.of(),
                            "Optional exact table caption/name; blank uses every matching table in the section."),
                    new ParameterDescriptor(TABLE_COLUMN, "Column header",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Exact header of the column whose retained links are reviewed."));
        }
        @Override public SourceValueSchema outputSchema() {
            return SourceValueSchema.collection(
                    SourceValueKind.ENTITY_REFERENCE, domain.datasource.EntityRef.WIKIDATA);
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            ArticleTableColumnLinkSpec spec = new ArticleTableColumnLinkSpec(
                    binding.recipe().parameter(TABLE_SECTION),
                    binding.recipe().parameter(TABLE_NAME),
                    binding.recipe().parameter(TABLE_COLUMN));
            return new PreparedSourceOperation(FAMILY_ARTICLE_TABLE_COLUMN_LINKS,
                    "Wikipedia article table-column links",
                    PreparedSourceOperation.Execution.RETAIN,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — review typed links from " + spec.description(),
                    java.util.Map.of(
                            "input", "One retained versioned structured Wikipedia article per selected member",
                            "operation", "Select the configured section, table and column without reparsing flattened text",
                            "output", "Propose links that resolve to loaded instances of the field's target class"),
                    spec, articleCorrespondence());
        }
    }

    public record ArticleTableColumnLinkSpec(
            String sectionPath, String table, String column) {
        public ArticleTableColumnLinkSpec {
            sectionPath = sectionPath == null ? "" : sectionPath.trim();
            table = table == null ? "" : table.trim();
            column = column == null ? "" : column.trim();
        }
        public boolean configured() {
            return !sectionPath.isBlank() && !column.isBlank();
        }
        public String description() {
            return "section ‘" + sectionPath + "’"
                    + (table.isBlank() ? "" : ", table ‘" + table + "’")
                    + ", column ‘" + column + "’";
        }
    }

    /** One explicitly selected table row becomes one reviewable typed instance. */
    private record ArticleTableRowInstances() implements DatasourceOperation {
        @Override public String id() { return ARTICLE_TABLE_ROW_INSTANCES; }
        @Override public String displayName() { return "Article table-row instances"; }
        @Override public String summary(SourceBinding binding) {
            ArticleTableRowInstanceSpec spec = articleTableRowInstances(binding);
            return "Table rows" + (spec == null ? " — incomplete"
                    : " — " + spec.description());
        }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(
                    new ParameterDescriptor(TABLE_SECTION, "Section path",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Exact heading path shown by Wikipedia Discovery, separated by >."),
                    new ParameterDescriptor(TABLE_NAME, "Table caption or name",
                            ParameterDescriptor.Kind.TEXT, false, "", List.of(),
                            "Optional exact table caption/name."),
                    new ParameterDescriptor(TABLE_COLUMN, "Display column",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Exact header whose cell labels each proposed row instance."),
                    new ParameterDescriptor(TABLE_ROW_TARGET_CLASS, "Concrete row class",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "The field target class or one of its subclasses."),
                    new ParameterDescriptor(TABLE_ROW_DETAILS_FIELD, "Row details field",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Text-list field receiving every non-empty column as Header: value."),
                    new ParameterDescriptor(TABLE_ROW_SOURCE_FIELD, "Source instance field",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Reference-list field receiving the instance whose article owns the row."),
                    new ParameterDescriptor(TABLE_ROW_REFERENCES_FIELD,
                            "Resolved references field", ParameterDescriptor.Kind.TEXT,
                            false, "", List.of(),
                            "Optional reference-list field receiving loaded instances linked from the display cell."));
        }
        @Override public SourceValueSchema outputSchema() {
            return SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE, "model");
        }
        @Override public List<domain.datasource.api.ConfigurationElement>
                configurationElements(SourceBinding binding) {
            ArticleTableRowInstanceSpec spec = articleTableRowInstances(binding);
            if (spec == null) return DatasourceOperation.super.configurationElements(binding);
            List<domain.datasource.api.ConfigurationElement> location = new ArrayList<>();
            location.add(new domain.datasource.api.ConfigurationElement(
                    TABLE_SECTION, "Section: " + spec.sectionPath()));
            if (!spec.table().isBlank()) {
                location.add(new domain.datasource.api.ConfigurationElement(
                        TABLE_NAME, "Table: " + spec.table()));
            }
            location.add(new domain.datasource.api.ConfigurationElement(
                    TABLE_COLUMN, "Display column: " + spec.displayColumn()));
            List<domain.datasource.api.ConfigurationElement> mapping = new ArrayList<>();
            mapping.add(new domain.datasource.api.ConfigurationElement(
                    TABLE_ROW_TARGET_CLASS, "Row class: " + spec.targetClass()));
            mapping.add(new domain.datasource.api.ConfigurationElement(
                    TABLE_ROW_DETAILS_FIELD, "Row details: " + spec.detailsField()));
            mapping.add(new domain.datasource.api.ConfigurationElement(
                    TABLE_ROW_SOURCE_FIELD, "Source instance: " + spec.sourceField()));
            if (!spec.referencesField().isBlank()) {
                mapping.add(new domain.datasource.api.ConfigurationElement(
                        TABLE_ROW_REFERENCES_FIELD,
                        "Resolved links: " + spec.referencesField()));
            }
            return List.of(
                    new domain.datasource.api.ConfigurationElement(
                            "location", "Article table", location),
                    new domain.datasource.api.ConfigurationElement(
                            "mapping", "Field mapping", mapping));
        }
        @Override public List<ModelConfigurationReference> configurationReferences(
                SourceBinding binding) {
            ArticleTableRowInstanceSpec spec = new ArticleTableRowInstanceSpec(
                    binding.recipe().parameter(TABLE_SECTION),
                    binding.recipe().parameter(TABLE_NAME),
                    binding.recipe().parameter(TABLE_COLUMN),
                    binding.recipe().parameter(TABLE_ROW_TARGET_CLASS),
                    binding.recipe().parameter(TABLE_ROW_DETAILS_FIELD),
                    binding.recipe().parameter(TABLE_ROW_SOURCE_FIELD),
                    binding.recipe().parameter(TABLE_ROW_REFERENCES_FIELD));
            if (spec.targetClass().isBlank()) return List.of();
            List<ModelConfigurationReference> references = new ArrayList<>();
            references.add(ModelConfigurationReference.classParameterReference(
                    spec.targetClass(), "concrete row class",
                    TABLE_ROW_TARGET_CLASS, true));
            if (!spec.detailsField().isBlank()) {
                references.add(ModelConfigurationReference.fieldParameterReference(
                        spec.targetClass(), spec.detailsField(), "row details field",
                        TABLE_ROW_TARGET_CLASS, TABLE_ROW_DETAILS_FIELD, true));
            }
            if (!spec.sourceField().isBlank()) {
                references.add(ModelConfigurationReference.fieldParameterReference(
                        spec.targetClass(), spec.sourceField(), "source instance field",
                        TABLE_ROW_TARGET_CLASS, TABLE_ROW_SOURCE_FIELD, true));
            }
            if (!spec.referencesField().isBlank()) {
                references.add(ModelConfigurationReference.fieldParameterReference(
                        spec.targetClass(), spec.referencesField(),
                        "resolved references field", TABLE_ROW_TARGET_CLASS,
                        TABLE_ROW_REFERENCES_FIELD, false));
            }
            return List.copyOf(references);
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            ArticleTableRowInstanceSpec spec = new ArticleTableRowInstanceSpec(
                    binding.recipe().parameter(TABLE_SECTION),
                    binding.recipe().parameter(TABLE_NAME),
                    binding.recipe().parameter(TABLE_COLUMN),
                    binding.recipe().parameter(TABLE_ROW_TARGET_CLASS),
                    binding.recipe().parameter(TABLE_ROW_DETAILS_FIELD),
                    binding.recipe().parameter(TABLE_ROW_SOURCE_FIELD),
                    binding.recipe().parameter(TABLE_ROW_REFERENCES_FIELD));
            return new PreparedSourceOperation(FAMILY_ARTICLE_TABLE_ROW_INSTANCES,
                    "Wikipedia article table-row instances",
                    PreparedSourceOperation.Execution.RETAIN,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — review one " + spec.targetClass() + " per row from "
                            + spec.description(),
                    java.util.Map.of(
                            "input", "One retained versioned structured Wikipedia article per selected member",
                            "operation", "Select configured data rows without reparsing flattened text",
                            "output", "Create " + spec.targetClass() + " instances, fill "
                                    + spec.detailsField() + " and " + spec.sourceField()
                                    + (spec.referencesField().isBlank() ? ""
                                    : ", and resolve display-cell links into "
                                            + spec.referencesField())),
                    spec, articleCorrespondence());
        }
    }

    public record ArticleTableRowInstanceSpec(
            String sectionPath,
            String table,
            String displayColumn,
            String targetClass,
            String detailsField,
            String sourceField,
            String referencesField) {
        public ArticleTableRowInstanceSpec {
            sectionPath = clean(sectionPath);
            table = clean(table);
            displayColumn = clean(displayColumn);
            targetClass = clean(targetClass);
            detailsField = clean(detailsField);
            sourceField = clean(sourceField);
            referencesField = clean(referencesField);
        }
        public boolean configured() {
            return !sectionPath.isBlank() && !displayColumn.isBlank()
                    && !targetClass.isBlank() && !detailsField.isBlank()
                    && !sourceField.isBlank();
        }
        public String description() {
            return "section ‘" + sectionPath + "’"
                    + (table.isBlank() ? "" : ", table ‘" + table + "’")
                    + ", display column ‘" + displayColumn + "’";
        }
    }

    /** One infobox acquisition declaration: the key and whether its text fills a field. */
    public record InfoboxValueSpec(String key, boolean fillField) {
        public InfoboxValueSpec {
            key = key == null ? "" : key.trim();
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
            @Override public String project(domain.datasource.evidence.ArticleIntroduction value) {
                return value == null ? "" : value.text();
            }
        },
        LEAD_PARAGRAPH("lead paragraph") {
            @Override public String project(domain.datasource.evidence.ArticleIntroduction value) {
                return value == null ? "" : value.leadParagraph();
            }
        },
        LEAD_SENTENCE("lead sentence") {
            @Override public String project(domain.datasource.evidence.ArticleIntroduction value) {
                return value == null ? "" : value.leadSentence();
            }
        };

        private final String displayName;

        ArticleTextProjection(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
        public abstract String project(domain.datasource.evidence.ArticleIntroduction value);
    }

    /**
     * A curation recipe, not an automatic value acquisition. The retained introduction
     * is interpreted against instances already admitted to the field's target class;
     * matches are review candidates and therefore remain RETAIN work in generation.
     */
    private record ArticleIntroductionMentions() implements DatasourceOperation {
        @Override public String id() { return ARTICLE_INTRODUCTION_MENTIONS; }
        @Override public String displayName() { return "Article introduction mentions"; }
        @Override public String summary(SourceBinding binding) {
            return "Introduction mentions";
        }
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
        @Override public String summary(SourceBinding binding) {
            return "Article " + articlePassageSpec(binding).description();
        }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            List<String> choices = new ArrayList<>();
            choices.add("ALL");
            java.util.Arrays.stream(
                            domain.datasource.evidence.WikipediaArticle.Block.Kind.values())
                    .map(Enum::name).forEach(choices::add);
            return List.of(new ParameterDescriptor(PASSAGE_BLOCK_KIND,
                    "Article block kind", ParameterDescriptor.Kind.CHOICE, false,
                    "ALL", choices,
                    "Keep every bounded content passage, or only one structural kind "
                            + "such as CAPTION."));
        }
        @Override public SourceValueSchema outputSchema() {
            return new SourceValueSchema(SourceValueKind.MODEL_VALUE, false, "");
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            ArticlePassageSpec spec = articlePassageSpec(binding);
            return new PreparedSourceOperation(FAMILY_ARTICLE_PASSAGES,
                    "Wikipedia article passages",
                    PreparedSourceOperation.Execution.RETAIN,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — review bounded text from the full article",
                    java.util.Map.of(
                            "input", "One versioned full Wikipedia article per selected member",
                            "operation", "Keep at most " + MAX_ARTICLE_PASSAGES + " "
                                    + spec.description() + " and "
                                    + MAX_ARTICLE_PASSAGE_CHARACTERS + " characters",
                            "output", "Propose section-labelled text for explicit review"),
                    spec,
                    articleCorrespondence());
        }
    }

    public record ArticlePassageSpec(
            domain.datasource.evidence.WikipediaArticle.Block.Kind blockKind) {
        public boolean filtered() { return blockKind != null; }
        public String description() {
            return filtered() ? blockKind.name().toLowerCase(java.util.Locale.ROOT) + " blocks"
                    : "content passages";
        }
    }

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
        @Override public List<ModelConfigurationReference> configurationReferences(
                SourceBinding binding) {
            ArticlePassageInstanceSpec spec = new ArticlePassageInstanceSpec(
                    binding.recipe().parameter(PASSAGE_TEXT_FIELD),
                    binding.recipe().parameter(PASSAGE_SOURCE_FIELD));
            List<ModelConfigurationReference> references = new ArrayList<>();
            if (!spec.textField().isBlank()) {
                references.add(ModelConfigurationReference.bindingTargetField(
                        spec.textField(), "passage text field", true));
            }
            if (!spec.sourceField().isBlank()) {
                references.add(ModelConfigurationReference.bindingTargetField(
                        spec.sourceField(), "source instance field", true));
            }
            return List.copyOf(references);
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

    /**
     * One proposed target-class instance per passage of explicitly named pages. The pages
     * are authored source configuration (a work's texts are not reachable through its
     * Wikidata sitelink); the page's own divisions bound each passage; names in a passage
     * propose loaded instances for an optional reference field, for review only.
     */
    private record TextPassageInstances() implements DatasourceOperation {
        @Override public String id() { return TEXT_PASSAGE_INSTANCES; }
        @Override public String displayName() { return "Text passages as instances"; }
        @Override public String summary(SourceBinding binding) {
            TextPassageInstanceSpec spec = textPassageInstances(binding);
            return spec.configured() ? "Text passages — " + spec.description()
                    : "Text passages — incomplete";
        }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(
                    new ParameterDescriptor(TEXT_SITE, "Site", ParameterDescriptor.Kind.CHOICE,
                            false, "WIKISOURCE", List.of("WIKISOURCE", "WIKIPEDIA"),
                            "MediaWiki site that holds the pages"),
                    new ParameterDescriptor(TEXT_PAGES, "Pages", ParameterDescriptor.Kind.TEXT,
                            true, "", List.of(),
                            "Source instance QID = page title, separated by |; a QID may "
                                    + "name several pages"),
                    new ParameterDescriptor(TEXT_DIVISION, "Divided by",
                            ParameterDescriptor.Kind.CHOICE, false, "NUMBERED_CHAPTERS",
                            java.util.Arrays.stream(domain.datasource.evidence.TextPassages
                                    .Division.values()).map(Enum::name).toList(),
                            "Numbered chapters or section headings"),
                    new ParameterDescriptor(PASSAGE_TEXT_FIELD, "Passage text field",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Text field on the proposed instance"),
                    new ParameterDescriptor(PASSAGE_SOURCE_FIELD, "Source instance field",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "Reference field on the proposed instance that receives the source"),
                    new ParameterDescriptor(PASSAGE_TITLE_FIELD, "Title field",
                            ParameterDescriptor.Kind.TEXT, false, "", List.of(),
                            "Optional text field that receives page, chapter and first words"),
                    new ParameterDescriptor(PASSAGE_REFERENCES_FIELD, "Named instances field",
                            ParameterDescriptor.Kind.TEXT, false, "", List.of(),
                            "Optional reference list that receives loaded instances the "
                                    + "passage names uniquely, for review"));
        }
        @Override public SourceValueSchema outputSchema() {
            return SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE, "model");
        }
        @Override public List<ModelConfigurationReference> configurationReferences(
                SourceBinding binding) {
            TextPassageInstanceSpec spec = textPassageInstances(binding);
            List<ModelConfigurationReference> references = new ArrayList<>();
            for (var field : List.of(
                    java.util.Map.entry(spec.textField(), "passage text field"),
                    java.util.Map.entry(spec.sourceField(), "source instance field"),
                    java.util.Map.entry(spec.titleField(), "passage title field"),
                    java.util.Map.entry(spec.referencesField(), "named instances field"))) {
                if (!field.getKey().isBlank()) {
                    references.add(ModelConfigurationReference.bindingTargetField(
                            field.getKey(), field.getValue(),
                            !field.getValue().startsWith("passage title")
                                    && !field.getValue().startsWith("named")));
                }
            }
            return List.copyOf(references);
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            TextPassageInstanceSpec spec = textPassageInstances(binding);
            return new PreparedSourceOperation(FAMILY_TEXT_PASSAGE_INSTANCES,
                    "Text passage instances",
                    PreparedSourceOperation.Execution.RETAIN,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " — review one target-class instance per passage",
                    java.util.Map.of(
                            "input", spec.pageCount() + " authored "
                                    + spec.site().label() + " page(s)",
                            "operation", "Divide each page by "
                                    + spec.division().label().toLowerCase(java.util.Locale.ROOT)
                                    + "; propose one instance per passage",
                            "output", "Fill " + spec.textField() + " with the passage and "
                                    + spec.sourceField() + " with its source instance"
                                    + (spec.referencesField().isBlank() ? ""
                                            : "; propose uniquely named loaded instances for "
                                                    + spec.referencesField())),
                    spec, List.of());
        }
    }

    /** The authored pages per source QID, the division and the target-class fields. */
    public record TextPassageInstanceSpec(
            wikipedia.WikipediaArticleClient.Site site,
            java.util.Map<String, List<String>> pages,
            domain.datasource.evidence.TextPassages.Division division,
            String textField, String sourceField, String titleField, String referencesField) {
        public TextPassageInstanceSpec {
            site = site == null ? wikipedia.WikipediaArticleClient.Site.WIKISOURCE : site;
            pages = pages == null ? java.util.Map.of() : java.util.Map.copyOf(pages);
            division = division == null
                    ? domain.datasource.evidence.TextPassages.Division.NUMBERED_CHAPTERS : division;
            textField = textField == null ? "" : textField.trim();
            sourceField = sourceField == null ? "" : sourceField.trim();
            titleField = titleField == null ? "" : titleField.trim();
            referencesField = referencesField == null ? "" : referencesField.trim();
        }
        public boolean configured() {
            return !pages.isEmpty() && !textField.isBlank() && !sourceField.isBlank();
        }
        public List<String> pagesOf(String qid) {
            return pages.getOrDefault(qid == null ? "" : qid.trim().toUpperCase(java.util.Locale.ROOT),
                    List.of());
        }
        public int pageCount() { return pages.values().stream().mapToInt(List::size).sum(); }
        public String description() {
            return pageCount() + " " + site.label() + " page(s) by "
                    + division.label().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static TextPassageInstanceSpec textPassageInstances(SourceBinding binding) {
        if (binding == null || binding.recipe() == null
                || !ID.equals(binding.recipe().providerId())
                || !TEXT_PASSAGE_INSTANCES.equals(binding.recipe().operationId())) {
            return new TextPassageInstanceSpec(null, null, null, "", "", "", "");
        }
        var recipe = binding.recipe();
        wikipedia.WikipediaArticleClient.Site site;
        try {
            site = wikipedia.WikipediaArticleClient.Site.valueOf(
                    recipe.parameter(TEXT_SITE).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unset) {
            site = wikipedia.WikipediaArticleClient.Site.WIKISOURCE;
        }
        java.util.Map<String, List<String>> pages = new java.util.LinkedHashMap<>();
        for (String entry : recipe.parameter(TEXT_PAGES).split("\\|")) {
            int equals = entry.indexOf('=');
            if (equals < 0) continue;
            String qid = entry.substring(0, equals).trim().toUpperCase(java.util.Locale.ROOT);
            String title = entry.substring(equals + 1).trim();
            if (!wikidata.WikidataIds.isQid(qid) || title.isBlank()) continue;
            pages.computeIfAbsent(qid, key -> new ArrayList<>()).add(title);
        }
        java.util.Map<String, List<String>> immutable = new java.util.LinkedHashMap<>();
        pages.forEach((qid, titles) -> immutable.put(qid, List.copyOf(titles)));
        return new TextPassageInstanceSpec(site, immutable,
                domain.datasource.evidence.TextPassages.Division.parse(recipe.parameter(TEXT_DIVISION)),
                recipe.parameter(PASSAGE_TEXT_FIELD), recipe.parameter(PASSAGE_SOURCE_FIELD),
                recipe.parameter(PASSAGE_TITLE_FIELD), recipe.parameter(PASSAGE_REFERENCES_FIELD));
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
        return List.of(new SourceInputRequirement(domain.datasource.EntityRef.WIKIDATA,
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

    /** The rendered-row key whose retained links this candidate-evidence binding reads. */
    public static String infoboxRowLinks(SourceBinding binding) {
        if (!infoboxRowLinkOperation(binding)) return null;
        String parameter = binding.recipe().parameter("property");
        domain.datasource.evidence.InfoboxParameters.Key key =
                domain.datasource.evidence.InfoboxParameters.Key.parse(parameter);
        return key != null && key.rendered() ? parameter : null;
    }

    /** Whether this is the operation, including an incomplete recipe an editor must show. */
    public static boolean infoboxRowLinkOperation(SourceBinding binding) {
        if (binding == null || binding.target() == null
                || binding.target().scope() != BindingScope.FIELD_VALUE
                || binding.target().slot() != SourceBindingSlot.CANDIDATE_EVIDENCE) {
            return false;
        }
        SourceRecipe recipe = binding.recipe();
        return recipe != null && ID.equals(recipe.providerId())
                && INFOBOX_ROW_LINKS.equals(recipe.operationId());
    }

    public static ArticleTableColumnLinkSpec articleTableColumnLinks(
            SourceBinding binding) {
        if (!articleTableColumnLinkOperation(binding)) return null;
        SourceRecipe recipe = binding.recipe();
        ArticleTableColumnLinkSpec spec = new ArticleTableColumnLinkSpec(
                recipe.parameter(TABLE_SECTION), recipe.parameter(TABLE_NAME),
                recipe.parameter(TABLE_COLUMN));
        return spec.configured() ? spec : null;
    }

    public static ArticleTableRowInstanceSpec articleTableRowInstances(
            SourceBinding binding) {
        if (!articleTableRowInstanceOperation(binding)) return null;
        SourceRecipe recipe = binding.recipe();
        ArticleTableRowInstanceSpec spec = new ArticleTableRowInstanceSpec(
                recipe.parameter(TABLE_SECTION), recipe.parameter(TABLE_NAME),
                recipe.parameter(TABLE_COLUMN), recipe.parameter(TABLE_ROW_TARGET_CLASS),
                recipe.parameter(TABLE_ROW_DETAILS_FIELD),
                recipe.parameter(TABLE_ROW_SOURCE_FIELD),
                recipe.parameter(TABLE_ROW_REFERENCES_FIELD));
        return spec.configured() ? spec : null;
    }

    /** Whether this is the operation, including an incomplete recipe the editor shows. */
    public static boolean articleTableRowInstanceOperation(SourceBinding binding) {
        if (binding == null || binding.target() == null
                || binding.target().scope() != BindingScope.FIELD_VALUE
                || binding.target().slot() != SourceBindingSlot.CANDIDATE_EVIDENCE) {
            return false;
        }
        SourceRecipe recipe = binding.recipe();
        return recipe != null && ID.equals(recipe.providerId())
                && ARTICLE_TABLE_ROW_INSTANCES.equals(recipe.operationId());
    }

    /** Whether this is the operation, including an incomplete recipe the editor shows. */
    public static boolean articleTableColumnLinkOperation(SourceBinding binding) {
        if (binding == null || binding.target() == null
                || binding.target().scope() != BindingScope.FIELD_VALUE
                || binding.target().slot() != SourceBindingSlot.CANDIDATE_EVIDENCE) {
            return false;
        }
        SourceRecipe recipe = binding.recipe();
        return recipe != null && ID.equals(recipe.providerId())
                && ARTICLE_TABLE_COLUMN_LINKS.equals(recipe.operationId());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
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

    public static ArticlePassageSpec articlePassageSpec(SourceBinding binding) {
        if (!articlePassages(binding)) return new ArticlePassageSpec(null);
        String configured = binding.recipe().parameter(PASSAGE_BLOCK_KIND).trim();
        if (configured.isBlank() || configured.equalsIgnoreCase("ALL")) {
            return new ArticlePassageSpec(null);
        }
        try {
            return new ArticlePassageSpec(
                    domain.datasource.evidence.WikipediaArticle.Block.Kind.valueOf(
                            configured.toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Unknown Wikipedia article block kind: " + configured, invalid);
        }
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
    public record CategoryRule(
            String pattern, String policy, List<CategoryMapping> mappings) {
        public CategoryRule {
            pattern = pattern == null ? "" : pattern;
            policy = policy == null || policy.isBlank() ? "REVIEW" : policy;
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
        }

        public String description() {
            List<String> parts = new ArrayList<>();
            if (!pattern.isBlank()) parts.add("‘" + pattern + "’");
            if (!mappings.isEmpty()) parts.add(mappings.size() + " exact mapping(s)");
            return String.join(" plus ", parts);
        }
    }

    public record CategoryMapping(String category, String valueQid) { }

    /** One codec for the provider-neutral source recipe. ModelBuilder fields and
     * Transform curation both write through it, and {@link #categoryRule(SourceBinding)}
     * is the one inverse. */
    public static Map<String, String> categoryParameters(CategoryRule rule) {
        if (rule == null) return Map.of();
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(WikipediaCategoryDiscoveryOperation.PATTERN, rule.pattern());
        parameters.put(WikipediaCategoryDiscoveryOperation.POLICY, rule.policy());
        parameters.put(WikipediaCategoryDiscoveryOperation.MAPPING_COUNT,
                Integer.toString(rule.mappings().size()));
        for (int index = 0; index < rule.mappings().size(); index++) {
            CategoryMapping mapping = rule.mappings().get(index);
            parameters.put(WikipediaCategoryDiscoveryOperation.mappingCategory(index),
                    mapping.category());
            parameters.put(WikipediaCategoryDiscoveryOperation.mappingValueQid(index),
                    mapping.valueQid());
        }
        return parameters;
    }

    /** Where category membership carries the field's value. A rule needs either one
     * valid {@code <value>} template or at least one valid exact category-to-QID
     * mapping; otherwise it names no acquisition. */
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
        List<CategoryMapping> mappings = categoryMappings(recipe);
        // A pattern still being typed matches nothing, and admitting it cost a whole
        // pool's sitelinks and categories on the Enrich path, which acquires BEFORE it
        // compiles — so the model was refused only after the fetching was done.
        boolean patternConfigured = pattern != null && !pattern.isBlank()
                && pattern.split(java.util.regex.Pattern.quote(VALUE_PLACEHOLDER), -1)
                        .length - 1 == 1;
        if (!patternConfigured && mappings.isEmpty()) {
            return null;
        }
        // The policy default is stated here as a STRING rather than reached for as
        // CategoryCandidatePolicy.REVIEW: this package describes sources for any model
        // and must not depend on one. The descriptor declares the same default.
        String policy = recipe.parameter(WikipediaCategoryDiscoveryOperation.POLICY);
        if (policy == null || policy.isBlank()) policy = "REVIEW";
        return new CategoryRule(patternConfigured ? pattern : "", policy, mappings);
    }

    private static List<CategoryMapping> categoryMappings(SourceRecipe recipe) {
        int count;
        try {
            count = Integer.parseInt(recipe.parameter(
                    WikipediaCategoryDiscoveryOperation.MAPPING_COUNT));
        } catch (RuntimeException ignored) {
            count = 0;
        }
        List<CategoryMapping> mappings = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String category = recipe.parameter(
                    WikipediaCategoryDiscoveryOperation.mappingCategory(index));
            String qid = recipe.parameter(
                    WikipediaCategoryDiscoveryOperation.mappingValueQid(index));
            if (category != null && !category.isBlank()
                    && wikidata.WikidataIds.isQid(qid)) {
                mappings.add(new CategoryMapping(category.trim(), qid.trim().toUpperCase()));
            }
        }
        return mappings;
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
