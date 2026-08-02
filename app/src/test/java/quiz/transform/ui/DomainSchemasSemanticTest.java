package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainSchemasSemanticTest {

    @Test void identityCurationFollowsProvenanceMetadataRatherThanFieldNames() {
        FieldRef provenance = FieldRef.described(
                "origin", FieldKind.REFERENCE, FieldKind.REFERENCE,
                "ExternalSource", true, false, "ExternalSource",
                false, false, false, false, "", true, false);
        FieldRef externalId = FieldRef.of(
                "externalId", FieldKind.TEXT, "String", false, false, false);
        FieldRef misleadingName = FieldRef.of(
                "source", FieldKind.TEXT, "String", false, false, false);
        DomainModel domain = domain(
                schema(provenance, misleadingName), schema(externalId));

        assertTrue(DomainSchemas.isProvenancePath(
                domain, "Record", "origin"));
        assertTrue(DomainSchemas.isProvenancePath(
                domain, "Record", "origin.externalId"));
        assertFalse(DomainSchemas.isProvenancePath(
                domain, "Record", "source"));
    }

    private static FieldSchema schema(FieldRef... fields) {
        List<FieldRef> values = List.of(fields);
        return () -> values;
    }

    private static DomainModel domain(FieldSchema record, FieldSchema source) {
        return new DomainModel() {
            @Override public List<String> types() { return List.of("Record"); }
            @Override public List<DomainField> fields(String type) { return List.of(); }
            @Override public FieldSchema fieldSchema(String type) {
                return "ExternalSource".equals(type) ? source : record;
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of();
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
    }
}
