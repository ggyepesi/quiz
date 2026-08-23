package datasource.api;

import datasource.Datasources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract checks for the offerings the shipped providers actually declare. A scope is
 * an extension point, not an inventory target: a future provider may legitimately be the
 * first to occupy one, and Wikipedia may itself become an identity source for a class.
 */
class EveryScopeHasAnOfferingTest {

    @Test void everyDeclaredOfferingHasAUsableContract() {
        for (DatasourceProvider provider : Datasources.standard().providers()) {
            assertTrue(provider.id() != null && !provider.id().isBlank());
            for (DatasourceOperation operation : provider.operations()) {
                assertTrue(operation.id() != null && !operation.id().isBlank());
                assertTrue(operation.scope() != null, provider.id() + "." + operation.id());
                assertTrue(operation.outputSchema() != null,
                        provider.id() + "." + operation.id());
                if (operation.outputSchema().kind() == SourceValueKind.ENTITY_REFERENCE) {
                    assertTrue(!operation.outputSchema().referenceNamespace().isBlank(),
                            provider.id() + "." + operation.id()
                                    + " loses the provider of its entity references");
                }
            }
        }
    }

    @Test void documentEvidenceIsNeverAFieldValue() {
        // A document is what a source SAID, and a configured recipe interprets it. The
        // schema says so, so a field binding editor declines it before offering it.
        for (DatasourceProvider provider : Datasources.standard().providers()) {
            for (DatasourceOperation operation : provider.operations()) {
                if (operation.scope() != BindingScope.DOCUMENT_EVIDENCE) continue;
                assertTrue(!operation.outputSchema().kind().bindableToField(),
                        provider.id() + "." + operation.id()
                                + " is evidence but advertises a field value");
                assertTrue(operation instanceof
                                datasource.api.acquisition.SourceAcquisitionOperation<?>,
                        provider.id() + "." + operation.id()
                                + " advertises evidence it cannot acquire");
                assertTrue(!operation.inputReferences().isEmpty(),
                        provider.id() + "." + operation.id()
                                + " says no source record it reads");
            }
        }
    }

    @Test void membershipYieldsEntitiesRatherThanText() {
        DatasourceOperation membership = Datasources.standard().require(
                "wikidata", "statement-membership", DatasourceOperation.class);

        assertEquals(SourceValueKind.ENTITY_REFERENCE, membership.outputSchema().kind());
        assertTrue(membership.outputSchema().collection(), "a population is many");
        assertEquals("wikidata", membership.outputSchema().referenceNamespace());
        assertTrue(membership instanceof
                        datasource.api.acquisition.SourceAcquisitionOperation<?>,
                "a population offering must be able to acquire its members");
        assertTrue(membership.parameters().stream().anyMatch(ParameterDescriptor::required),
                "a membership rule that names no property selects everything");
    }

    @Test void sourceIdentifiersAreTypedReferencesRatherThanTextBoxes() {
        DatasourceOperation membership = Datasources.standard().require(
                "wikidata", "statement-membership", DatasourceOperation.class);
        ParameterDescriptor property = membership.parameters().stream()
                .filter(parameter -> parameter.key().equals("property"))
                .findFirst().orElseThrow();
        ParameterDescriptor values = membership.parameters().stream()
                .filter(parameter -> parameter.key().equals("values"))
                .findFirst().orElseThrow();

        assertEquals(ParameterDescriptor.Kind.REFERENCE, property.kind());
        assertEquals(SourceReferenceSchema.Kind.PROPERTY,
                property.referenceSchema().kind());
        assertEquals(SourceReferenceSchema.Kind.ENTITY,
                values.referenceSchema().kind());
        assertTrue(values.referenceSchema().collection());
    }
}
