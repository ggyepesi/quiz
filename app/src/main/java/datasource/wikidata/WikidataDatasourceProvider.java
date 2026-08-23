package datasource.wikidata;

import datasource.api.BindingScope;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;

import java.util.List;

/**
 * What Wikidata offers a class, as operations.
 *
 * <p>The second provider, and the one that tests the abstraction: Wikipedia contributes
 * evidence ABOUT entities, while Wikidata is what an entity IS here — it supplies the
 * identifier a class is keyed by and the label its instances display. If those cannot be
 * expressed as operations with a binding scope, then "which source gives this class its
 * identity" has no home and the catalogue is only a plugin list.
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
            "languages", "Languages", ParameterDescriptor.Kind.TEXT, false, "en,mul",
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
            offering(STATEMENT_MEMBERSHIP, "Members by statement",
                    BindingScope.CLASS_POPULATION,
                    SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE),
                    List.of(
                            new ParameterDescriptor("property", "Property",
                                    ParameterDescriptor.Kind.TEXT, true, "P31", List.of(),
                                    "The statement that makes an entity a member. P31 "
                                            + "for a type; any property for a relation."),
                            new ParameterDescriptor("values", "Values",
                                    ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                                    "The QIDs the property must point at. Several means "
                                            + "membership by any of them."),
                            new ParameterDescriptor("includeSubclasses",
                                    "Include subclasses", ParameterDescriptor.Kind.BOOLEAN,
                                    false, "false", List.of(),
                                    "Follow P279 down from each value. Meaningful for a "
                                            + "type; not for a relation."))),
            offering(SEED_LIST, "Members by explicit list", BindingScope.CLASS_POPULATION,
                    SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE),
                    List.of(new ParameterDescriptor("ids", "Ids",
                            ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                            "The QIDs that are members, named one by one."))),
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
}
