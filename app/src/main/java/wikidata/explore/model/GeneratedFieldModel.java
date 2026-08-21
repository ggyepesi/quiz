package wikidata.explore.model;

import java.util.ArrayList;
import java.util.List;

public class GeneratedFieldModel {

    private String name;
    private FieldType type = FieldType.AUTO;
    private String entityClassName = "";
    /** ENTITY values whose class the model deliberately does not name — see
     *  {@link FieldDefinition#unclassedEntity()}. */
    private boolean unclassedEntity;
    private FieldCardinality cardinality = FieldCardinality.AUTO;
    private FieldRenderMode renderMode = FieldRenderMode.AUTO;

    // When true the field's property is a membership constraint: its triple is
    // emitted non-OPTIONAL, so only entities that actually have the property
    // are kept (e.g. require an IAU abbreviation P1813 to drop the 4 non-
    // standard constellations from the 92 P31=Q8928 hits down to the IAU 88).
    private boolean required = false;

    // Post-transform expectation for a reified/statement class's field (#96):
    // NONE (no check) / EXPECTED (keep + report the missing) / REQUIRED (drop the
    // missing). Distinct from `required` above, which is a SPARQL-time membership
    // constraint. null = NONE, @JsonInclude(NON_NULL) so existing models are
    // byte-identical until a field opts in.
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private FieldExpectation expectation = null;

    // Optional numeric filter on this (numeric) property — keep only entities
    // whose value satisfies the comparison, e.g. apparentMagnitude <= 3 to keep
    // bright/notable stars. Null operator = no filter.
    private wikidata.explore.filter.WikidataValueFilterOperator filterOperator;
    private Double filterValue;

    // For an entity/child-object field: whether this edge applies the referenced
    // class's membership (P31 = its instance QID) or drops it. Dropping lets a
    // relationship include bright NAMED stars typed as a subclass (variable /
    // double star), reached via P59 + magnitude + label, while the root Star
    // class keeps its Q523 membership. INHERIT = use the class's membership.
    private EdgeMembershipMode edgeMembership = EdgeMembershipMode.INHERIT;

    // For an entity/child-object field: sort the child entities by one of the
    // child class's (numeric) fields before applying the per-parent limit, so a
    // constellation keeps its BRIGHTEST stars, not 6 arbitrary in-range ones.
    // Empty = no sort. Ascending by default (e.g. lowest apparentMagnitude =
    // brightest first).
    private String sortFieldName = "";
    private boolean sortDescending = false;

    // Unit symbol for a quantity field (e.g. "K", "g/cm³"), resolved once for
    // the whole field — Wikidata's truthy value drops it. Blank = dimensionless.
    private String unit = "";

    private final FieldSourceMapping mapping = new FieldSourceMapping();
    // Optional fallback source, consulted per instance only when the primary yields no
    // usable value (a true Wikidata→DBpedia route for enrichment). Null (not an empty
    // mapping) when unset, and @JsonInclude(NON_NULL), so existing snapshots that predate
    // it stay byte-identical until a field opts in.
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private FieldSourceMapping fallbackMapping;
    /** Optional additive Wikipedia-category relation; null preserves old model JSON. */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private WikipediaCategoryRule wikipediaCategoryRule;
    private final List<GeneratedFieldModel> fields = new ArrayList<>();

    // For deserialization (GeneratedProjectModelStore).
    public GeneratedFieldModel() {
        this("field", FieldType.AUTO, FieldCardinality.AUTO);
    }

    public GeneratedFieldModel(
            String name,
            FieldType type,
            FieldCardinality cardinality) {

        this.name = name == null || name.isBlank() ? "field" : name.trim();
        this.type = type == null ? FieldType.AUTO : type;
        this.cardinality =
                cardinality == null ? FieldCardinality.AUTO : cardinality;
    }

    public static GeneratedFieldModel nameField() {
        GeneratedFieldModel f =
                new GeneratedFieldModel(
                        "name",
                        FieldType.STRING,
                        FieldCardinality.SINGLE);
        f.mapping().productionKind(FieldProductionKind.INLINE_VALUE);
        return f;
    }

    public String name() { return name; }

    public void name(String name) {
        this.name = name == null || name.isBlank() ? "field" : name.trim();
    }

    public FieldType type() { return type; }

    public void type(FieldType type) {
        this.type = type == null ? FieldType.AUTO : type;
    }

    public boolean unclassedEntity() { return unclassedEntity; }

    public void unclassedEntity(boolean value) {
        this.unclassedEntity = value;
        if (value) entityClassName = "";
    }

    public String entityClassName() { return entityClassName; }

    public void entityClassName(String entityClassName) {
        this.entityClassName =
                entityClassName == null ? "" : entityClassName.trim();
    }

    public FieldCardinality cardinality() { return cardinality; }

    public void cardinality(FieldCardinality cardinality) {
        this.cardinality =
                cardinality == null ? FieldCardinality.AUTO : cardinality;
    }

    public FieldRenderMode renderMode() {
        return renderMode;
    }

    public void renderMode(FieldRenderMode renderMode) {
        this.renderMode =
                renderMode == null ? FieldRenderMode.AUTO : renderMode;
    }

    public boolean renderAsReference() {
        return renderMode == FieldRenderMode.REFERENCE;
    }

    public boolean renderInline() {
        return renderMode == FieldRenderMode.INLINE;
    }

    public boolean required() { return required; }

    public void required(boolean required) { this.required = required; }

    /** Post-transform expectation (never null; null storage means NONE). */
    public FieldExpectation expectation() {
        return expectation == null ? FieldExpectation.NONE : expectation;
    }

    public void expectation(FieldExpectation e) {
        this.expectation = (e == null || e == FieldExpectation.NONE) ? null : e;
    }

    public wikidata.explore.filter.WikidataValueFilterOperator filterOperator() {
        return filterOperator;
    }

    public void filterOperator(
            wikidata.explore.filter.WikidataValueFilterOperator op) {
        this.filterOperator = op;
    }

    public Double filterValue() { return filterValue; }

    public void filterValue(Double v) { this.filterValue = v; }

    public boolean hasValueFilter() {
        return filterOperator != null && filterValue != null;
    }

    public EdgeMembershipMode edgeMembership() {
        return edgeMembership == null ? EdgeMembershipMode.INHERIT : edgeMembership;
    }

    public void edgeMembership(EdgeMembershipMode mode) {
        this.edgeMembership = mode == null ? EdgeMembershipMode.INHERIT : mode;
    }

    public String sortFieldName() {
        return sortFieldName == null ? "" : sortFieldName;
    }

    public void sortFieldName(String name) {
        this.sortFieldName = name == null ? "" : name.trim();
    }

    public boolean sortDescending() { return sortDescending; }

    public void sortDescending(boolean desc) { this.sortDescending = desc; }

    public boolean hasSort() {
        return sortFieldName != null && !sortFieldName.isBlank();
    }

    public String unit() {
        return unit == null ? "" : unit;
    }

    public void unit(String unit) {
        this.unit = unit == null ? "" : unit.trim();
    }

    public GeneratedFieldModel copy() {
        GeneratedFieldModel c =
                new GeneratedFieldModel(name, type, cardinality);

        c.entityClassName = entityClassName;
        c.unclassedEntity = unclassedEntity;
        c.renderMode = renderMode;
        c.required = required;
        c.expectation = expectation;
        c.filterOperator = filterOperator;
        c.filterValue = filterValue;
        c.edgeMembership = edgeMembership;
        c.sortFieldName = sortFieldName;
        c.sortDescending = sortDescending;
        c.unit = unit;
        c.mapping.copyFrom(mapping);
        if (fallbackMapping != null) {
            c.fallbackMapping = new FieldSourceMapping();
            c.fallbackMapping.copyFrom(fallbackMapping);
        }
        if (wikipediaCategoryRule != null) {
            c.wikipediaCategoryRule = wikipediaCategoryRule.copy();
        }

        for (GeneratedFieldModel f : fields) {
            if (f != null) {
                c.fields.add(f.copy());
            }
        }

        return c;
    }

    public FieldSourceMapping mapping() { return mapping; }

    /** The fallback source, or null when none is configured. */
    public FieldSourceMapping fallbackMapping() { return fallbackMapping; }

    public void fallbackMapping(FieldSourceMapping fallback) {
        this.fallbackMapping = fallback;
    }

    /** The fallback mapping, creating an empty one to configure if absent. */
    public FieldSourceMapping ensureFallbackMapping() {
        if (fallbackMapping == null) {
            fallbackMapping = new FieldSourceMapping();
        }
        return fallbackMapping;
    }

    public WikipediaCategoryRule wikipediaCategoryRule() { return wikipediaCategoryRule; }

    public void wikipediaCategoryRule(WikipediaCategoryRule value) {
        wikipediaCategoryRule = value;
    }

    public WikipediaCategoryRule ensureWikipediaCategoryRule() {
        if (wikipediaCategoryRule == null) wikipediaCategoryRule = new WikipediaCategoryRule();
        return wikipediaCategoryRule;
    }

    public List<GeneratedFieldModel> fields() { return fields; }

    public FieldDefinition definition() {
        return new FieldDefinition(name, type, entityClassName, cardinality, renderMode,
                unclassedEntity);
    }

    public void definition(FieldDefinition definition) {
        if (definition == null) return;
        name(definition.name());
        type(definition.type());
        unclassedEntity(definition.unclassedEntity());
        entityClassName(definition.entityClassName());
        cardinality(definition.cardinality());
        renderMode(definition.renderMode());
    }

    public boolean collection() {
        return cardinality == FieldCardinality.COLLECTION;
    }

    public boolean isNameField() {
        return "name".equalsIgnoreCase(name);
    }

    public String displayType() {
        String base =
                switch (type) {
                    case STRING -> "String";
                    case IMAGE -> "Image";
                    case ENTITY -> entityClassName == null || entityClassName.isBlank()
                            ? "Object"
                            : entityClassName;
                    case NUMBER -> "Number";
                    case DATE -> "Date";
                    case TEXT -> "Text";
                    case BOOLEAN -> "Boolean";
                    case AUTO -> entityClassName == null || entityClassName.isBlank()
                            ? "Auto"
                            : entityClassName;
                };

        return collection() ? "List<" + base + ">" : base;
    }

    @Override
    public String toString() {
        String s = displayType() + " " + name;

        if (renderMode == FieldRenderMode.REFERENCE) {
            s += " [reference]";
        } else if (renderMode == FieldRenderMode.INLINE) {
            s += " [inline]";
        }

        if (required) {
            s += " [required]";
        }

        return s;
    }
}
