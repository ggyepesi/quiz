package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSchema;

import java.util.List;
import domain.DomainField;
import domain.DomainSchemas;

/**
 * A class produced by a PROJECT operation: a new type name, the fields it carries
 * (the projected columns), and its materialized instances (one per source member).
 * Added to the {@link WorkingDomain} so its fields feed back into the pool and
 * later operations can consume it — the composable transform graph.
 */
public record DerivedClass(String type, List<DomainField> fields,
                           List<? extends Viewable> instances,
                           FieldSchema fieldSchema) {

    public DerivedClass {
        fields = fields == null ? List.of() : List.copyOf(fields);
        instances = instances == null ? List.of() : List.copyOf(instances);
        fieldSchema = fieldSchema == null
                ? DomainSchemas.flatSchema(fields) : fieldSchema;
    }

    public DerivedClass(String type, List<DomainField> fields,
                        List<? extends Viewable> instances) {
        this(type, fields, instances, DomainSchemas.flatSchema(fields));
    }
}
