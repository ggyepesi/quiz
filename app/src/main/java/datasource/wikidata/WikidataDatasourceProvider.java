package datasource.wikidata;

import datasource.api.BindingScope;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;
import datasource.api.SourceReferenceSchema;
import datasource.api.SourceRecipe;
import datasource.api.acquisition.ClassPopulationOperation;
import datasource.api.acquisition.PopulationRequest;
import datasource.EntityRef;
import wikidata.WikidataIds;

import java.util.List;

/**
 * What Wikidata offers a class, as operations.
 *
 * <p>The second provider, and the one that tests the abstraction: Wikidata can be a
 * class's identity authority, supplying the identifier it is keyed by and the label its
 * instances display. It is not privileged by the contract; a Wikipedia-authoritative
 * class may instead choose that provider's durable page reference and title.
 *
 * <p>They can. Each is an offering with a scope saying where it may be bound and a
 * schema saying what it yields, so a class declaring its origin is choosing among these
 * rather than being told by the extraction layer which source it came from.
 *
 * <p>Only the first is not optional. An entity class without {@link #IDENTIFIER} has no
 * identity of its own; a class may perfectly well decline the label and compose one from
 * its fields, decline the aliases, or take the description and mark it minor.
 */
public final class WikidataDatasourceProvider implements DatasourceProvider {

    public static final String ID = "wikidata";

    /** The QID: what makes two instances the same instance. */
    public static final String IDENTIFIER = "identifier";
    /** The label, in a language preference — what an instance displays as its name. */
    public static final String LABEL = "label";
    /** "Also known as": the values that surface today as an undeclared alternateNames. */
    public static final String ALIASES = "aliases";
    /** The short gloss under a label. */
    public static final String DESCRIPTION = "description";
    /** A statement property read as the configured value of one model field. */
    public static final String PROPERTY_VALUE = "property-value";
    /** The article a Wikipedia operation needs to say anything about this entity. */
    public static final String SITELINK = "sitelink";

    /**
     * Membership by a statement: the instances whose {@code property} points at one of
     * {@code values}.
     *
     * <p>One offering rather than four, because it is one query. The model's membership
     * patterns — a type (P31), a type with its subclasses, a single-target relation, a
     * multi-target one — are configurations of this, differing in which property is
     * named and whether the subclass closure applies. Splitting them here would make the
     * catalogue describe the editor's vocabulary instead of the source's.
     */
    public static final String STATEMENT_MEMBERSHIP = "statement-membership";

    /** Membership by an explicit list of ids, for a population no statement selects. */
    public static final String SEED_LIST = "seed-list";

    private static final ParameterDescriptor LANGUAGES = new ParameterDescriptor(
            "languages", "Languages", ParameterDescriptor.Kind.TEXT, false,
            wikidata.WikidataLanguageDefaults.languages(),
            List.of(),
            "Language preference, most preferred first. mul is Wikidata's "
                    + "multilingual default and is many entities' only Latin-script name.");

    private final List<DatasourceOperation> operations = List.of(
            offering(IDENTIFIER, "Wikidata id (QID)", BindingScope.CLASS_IDENTITY,
                    new SourceValueSchema(SourceValueKind.ENTITY_REFERENCE, false, ID),
                    List.of()),
            offering(LABEL, "Label", BindingScope.CLASS_NAMES,
                    new SourceValueSchema(SourceValueKind.LANGUAGE_TEXT, false, ""),
                    List.of(LANGUAGES)),
            offering(ALIASES, "Also known as", BindingScope.CLASS_NAMES,
                    SourceValueSchema.collection(SourceValueKind.LANGUAGE_TEXT),
                    List.of(LANGUAGES)),
            offering(DESCRIPTION, "Description", BindingScope.FIELD_VALUE,
                    new SourceValueSchema(SourceValueKind.LANGUAGE_TEXT, false, ""),
                    List.of(LANGUAGES)),
            offering(PROPERTY_VALUE, "Statement property", BindingScope.FIELD_VALUE,
                    new SourceValueSchema(SourceValueKind.MODEL_VALUE, true, ""),
                    List.of(
                            ParameterDescriptor.reference("property", "Property", true, "",
                                    "The Wikidata property supplying this field.",
                                    new SourceReferenceSchema(ID,
                                            SourceReferenceSchema.Kind.PROPERTY, false)),
                            new ParameterDescriptor("valueLanguage", "Value language",
                                    ParameterDescriptor.Kind.TEXT, false, "", List.of(),
                                    "Optional language code for values constrained by "
                                            + "a P407 qualifier. Blank preserves all."))),
            new StatementMembershipOffering(
                    BindingScope.CLASS_POPULATION,
                    SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE, ID),
                    List.of(
                            ParameterDescriptor.reference("property", "Property", true,
                                    "P31",
                                    "The statement that makes an entity a member. P31 "
                                            + "for a type; any property for a relation.",
                                    new SourceReferenceSchema(ID,
                                            SourceReferenceSchema.Kind.PROPERTY, false)),
                            ParameterDescriptor.reference("values", "Values", true, "",
                                    "The QIDs the property must point at. Several means "
                                            + "membership by any of them.",
                                    new SourceReferenceSchema(ID,
                                            SourceReferenceSchema.Kind.ENTITY, true)),
                            new ParameterDescriptor("includeSubclasses",
                                    "Include subclasses", ParameterDescriptor.Kind.BOOLEAN,
                                    false, "false", List.of(),
                                    "Follow P279 down from each value. Meaningful for a "
                                            + "type; not for a relation."))),
            new SeedListOffering(BindingScope.CLASS_POPULATION,
                    SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE, ID),
                    List.of(ParameterDescriptor.reference("ids", "Ids", true, "",
                            "The QIDs that are members, named one by one.",
                            new SourceReferenceSchema(ID,
                                    SourceReferenceSchema.Kind.ENTITY, true)))),
            offering(SITELINK, "Wikipedia article", BindingScope.SOURCE_CORRESPONDENCE,
                    new SourceValueSchema(SourceValueKind.URL, false, ""),
                    List.of(new ParameterDescriptor(
                            "wiki", "Wiki", ParameterDescriptor.Kind.TEXT, false, "enwiki",
                            List.of(), "Which Wikipedia the article is taken from."))));

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Wikidata"; }
    @Override public List<? extends DatasourceOperation> operations() { return operations; }

    private static DatasourceOperation offering(
            String id, String displayName, BindingScope scope,
            SourceValueSchema schema, List<ParameterDescriptor> parameters) {
        return new EntityOffering(id, displayName, scope, schema, parameters);
    }

    /**
     * An offering that needs no request of its own: it rides the entity read a
     * generation already performs, so binding it changes what is KEPT, not what is
     * fetched. That is why it is a plain declaration and not a query.
     */
    private record EntityOffering(
            String id, String displayName, BindingScope scope,
            SourceValueSchema outputSchema, List<ParameterDescriptor> parameters)
            implements DatasourceOperation {

        EntityOffering {
            parameters = List.copyOf(parameters == null ? List.of() : parameters);
        }
    }

    private record StatementMembershipOffering(
            BindingScope scope, SourceValueSchema outputSchema,
            List<ParameterDescriptor> parameters)
            implements ClassPopulationOperation {

        StatementMembershipOffering {
            parameters = List.copyOf(parameters);
        }

        @Override public String id() { return STATEMENT_MEMBERSHIP; }
        @Override public String displayName() { return "Members by statement"; }

        @Override public PopulationRequest selection(SourceRecipe recipe) {
            SourceRecipe safe = java.util.Objects.requireNonNull(recipe, "recipe");
            String property = safe.parameter("property").trim().toUpperCase();
            List<String> values = qids(safe.parameter("values"));
            boolean subclasses = Boolean.parseBoolean(safe.parameter("includeSubclasses"));
            validateMembership(property, values, subclasses);
            return PopulationRequest.relation(EntityRef.WIKIDATA, property,
                    values.stream().map(EntityRef::wikidata).toList(), subclasses);
        }

    }

    private record SeedListOffering(
            BindingScope scope, SourceValueSchema outputSchema,
            List<ParameterDescriptor> parameters)
            implements ClassPopulationOperation {

        SeedListOffering { parameters = List.copyOf(parameters); }
        @Override public String id() { return SEED_LIST; }
        @Override public String displayName() { return "Members by explicit list"; }

        @Override public PopulationRequest selection(SourceRecipe recipe) {
            SourceRecipe safe = java.util.Objects.requireNonNull(recipe, "recipe");
            return PopulationRequest.explicit(EntityRef.WIKIDATA,
                    qids(safe.parameter("ids")).stream()
                    .map(EntityRef::wikidata).toList());
        }

    }

    private static List<String> qids(String text) {
        if (text == null || text.isBlank()) return List.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String token : text.split("[,\\s|]+")) {
            String qid = token.trim().toUpperCase();
            if (qid.isBlank()) continue;
            if (!WikidataIds.isQid(qid)) {
                throw new IllegalArgumentException("Invalid Wikidata entity QID: " + token);
            }
            result.add(qid);
        }
        return List.copyOf(result);
    }

    private static void validateMembership(
            String property, List<String> values, boolean subclasses) {
        if (!WikidataIds.isPid(property)) {
            throw new IllegalArgumentException("Invalid Wikidata property: " + property);
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("At least one Wikidata value QID is required");
        }
        if (subclasses && !"P31".equals(property)) {
            throw new IllegalArgumentException(
                    "Subclass expansion is only valid for P31 membership");
        }
    }

}
