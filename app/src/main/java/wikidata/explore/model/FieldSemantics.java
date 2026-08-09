package wikidata.explore.model;

/**
 * Effective field semantics shared by compilation and explanatory UI.
 *
 * <p>This class deliberately lives with the editable model: anything which
 * explains a field must make the same semantic decisions as the query compiler.
 * In particular, scalar literal properties are always outgoing RDF triples,
 * irrespective of the direction retained by an older/default mapping.</p>
 */
public final class FieldSemantics {

    private FieldSemantics() { }

    public static RuleDirection effectiveDirection(GeneratedFieldModel field) {
        if (field == null) {
            return RuleDirection.ITEM_TO_ROOT;
        }
        return effectiveDirection(
                field.type(), field.cardinality(), field.mapping().direction());
    }

    public static RuleDirection effectiveDirection(
            FieldType type,
            FieldCardinality cardinality,
            RuleDirection configured) {

        FieldType effectiveType = type == null ? FieldType.AUTO : type;
        FieldCardinality effectiveCardinality = cardinality == null
                ? FieldCardinality.AUTO : cardinality;
        RuleDirection effectiveConfigured = configured == null
                ? RuleDirection.ITEM_TO_ROOT : configured;

        boolean scalarLiteral = effectiveCardinality != FieldCardinality.COLLECTION
                && (effectiveType == FieldType.STRING
                || effectiveType == FieldType.NUMBER
                || effectiveType == FieldType.DATE);

        return scalarLiteral ? RuleDirection.ROOT_TO_ITEM : effectiveConfigured;
    }
}
