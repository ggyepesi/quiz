package wikidata.explore.compiled;

import datasource.schema.FieldType;

import wikidata.explore.filter.WikidataValueFilterOperator;
import wikidata.explore.model.*;

import java.util.List;

/**
 * Immutable field definition used by runtime planners.
 *
 * <p>Configured names are retained for diagnostics. Resolved names use the
 * actual case-preserving declaration found by the compiler.</p>
 */
public record CompiledField(
        String name,
        FieldType type,
        String configuredEntityClassName,
        String entityDeclarationId,
        String entityClassName,
        FieldCardinality cardinality,
        FieldRenderMode renderMode,
        boolean required,
        FieldExpectation expectation,
        WikidataValueFilterOperator filterOperator,
        Double filterValue,
        EdgeMembershipMode edgeMembership,
        String configuredSortFieldName,
        String sortFieldName,
        boolean sortDescending,
        String unit,
        CompiledFieldSource source,
        List<CompiledField> nestedFields) {

    public CompiledField {
        name = clean(name);
        type = type == null ? FieldType.AUTO : type;
        configuredEntityClassName = clean(configuredEntityClassName);
        entityDeclarationId = clean(entityDeclarationId);
        entityClassName = clean(entityClassName);
        cardinality = cardinality == null
                ? FieldCardinality.AUTO
                : cardinality;
        renderMode = renderMode == null
                ? FieldRenderMode.AUTO
                : renderMode;
        expectation = expectation == null
                ? FieldExpectation.NONE
                : expectation;
        edgeMembership = edgeMembership == null
                ? EdgeMembershipMode.INHERIT
                : edgeMembership;
        configuredSortFieldName = clean(configuredSortFieldName);
        sortFieldName = clean(sortFieldName);
        unit = clean(unit);
        source = source == null ? CompiledFieldSource.from(null) : source;
        nestedFields = nestedFields == null
                ? List.of()
                : List.copyOf(nestedFields);
    }

    public boolean collection() {
        return cardinality == FieldCardinality.COLLECTION;
    }

    public boolean entityReference() {
        return type == FieldType.ENTITY;
    }

    public boolean hasValueFilter() {
        return filterOperator != null && filterValue != null;
    }

    public boolean hasSort() {
        return !sortFieldName.isBlank();
    }

    public static CompiledField from(
            GeneratedFieldModel field,
            String resolvedEntityClassName,
            String resolvedSortFieldName,
            List<CompiledField> nestedFields) {

        return new CompiledField(
                field.name(),
                field.type(),
                field.entityClassName(),
                field.entityDeclarationId(),
                resolvedEntityClassName,
                field.cardinality(),
                field.renderMode(),
                field.required(),
                field.expectation(),
                field.filterOperator(),
                field.filterValue(),
                field.edgeMembership(),
                field.sortFieldName(),
                resolvedSortFieldName,
                field.sortDescending(),
                field.unit(),
                CompiledFieldSource.from(field.mapping()),
                nestedFields);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
