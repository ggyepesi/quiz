package wikidata.explore.compiled;

import wikidata.explore.filter.WikidataValueFilterOperator;
import wikidata.explore.model.*;
import java.util.List;

/** Immutable field definition used by runtime planners. */
public record CompiledField(
        String name,
        FieldType type,
        String configuredEntityClassName,
        String entityClassName,
        FieldCardinality cardinality,
        FieldRenderMode renderMode,
        boolean required,
        FieldExpectation expectation,
        WikidataValueFilterOperator filterOperator,
        Double filterValue,
        EdgeMembershipMode edgeMembership,
        String sortFieldName,
        boolean sortDescending,
        String unit,
        CompiledFieldSource source,
        List<CompiledField> nestedFields) {

    public CompiledField {
        name = clean(name);
        type = type == null ? FieldType.AUTO : type;
        configuredEntityClassName = clean(configuredEntityClassName);
        entityClassName = clean(entityClassName);
        cardinality = cardinality == null ? FieldCardinality.AUTO : cardinality;
        renderMode = renderMode == null ? FieldRenderMode.AUTO : renderMode;
        expectation = expectation == null ? FieldExpectation.NONE : expectation;
        edgeMembership = edgeMembership == null
                ? EdgeMembershipMode.INHERIT : edgeMembership;
        sortFieldName = clean(sortFieldName);
        unit = clean(unit);
        source = source == null ? CompiledFieldSource.from(null) : source;
        nestedFields = nestedFields == null ? List.of() : List.copyOf(nestedFields);
    }

    public boolean collection() {
        return cardinality == FieldCardinality.COLLECTION;
    }

    public boolean hasValueFilter() {
        return filterOperator != null && filterValue != null;
    }

    public static CompiledField from(
            GeneratedFieldModel field,
            String resolvedEntityClassName,
            List<CompiledField> nestedFields) {
        return new CompiledField(
                field.name(), field.type(), field.entityClassName(),
                resolvedEntityClassName, field.cardinality(), field.renderMode(),
                field.required(), field.expectation(), field.filterOperator(),
                field.filterValue(), field.edgeMembership(), field.sortFieldName(),
                field.sortDescending(), field.unit(),
                CompiledFieldSource.from(field.mapping()), nestedFields);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
