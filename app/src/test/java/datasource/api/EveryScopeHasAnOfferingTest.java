package datasource.api;

import datasource.Datasources;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A binding scope with no offering is a hypothesis. Each names a place in a domain model
 * where a source capability may attach, and until something attaches there the name is a
 * guess about what a source might one day provide.
 *
 * <p>They are all filled now, by the two sources this application ships — which is the
 * evidence that the scopes describe how a domain is actually built rather than how it
 * was imagined it might be.
 */
class EveryScopeHasAnOfferingTest {

    private static Map<BindingScope, List<String>> offeringsByScope() {
        Map<BindingScope, List<String>> byScope = new EnumMap<>(BindingScope.class);
        for (DatasourceProvider provider : Datasources.standard().providers()) {
            for (DatasourceOperation operation : provider.operations()) {
                byScope.computeIfAbsent(operation.scope(), s -> new java.util.ArrayList<>())
                        .add(provider.id() + "." + operation.id());
            }
        }
        return byScope;
    }

    @Test void everyScopeIsClaimedBySomething() {
        Map<BindingScope, List<String>> byScope = offeringsByScope();

        for (BindingScope scope : BindingScope.values()) {
            assertTrue(byScope.containsKey(scope),
                    scope + " names a place a source capability may attach and nothing "
                            + "attaches there — a hypothesis, not a design. Offerings: "
                            + byScope);
        }
    }

    @Test void identityAndPopulationComeFromTheIdentifyingSource() {
        // Wikipedia says things ABOUT an entity; it cannot say which entities exist or
        // which of them are the same one.
        Map<BindingScope, List<String>> byScope = offeringsByScope();

        assertEquals(List.of("wikidata.identifier"),
                byScope.get(BindingScope.CLASS_IDENTITY));
        assertTrue(byScope.get(BindingScope.CLASS_POPULATION).stream()
                        .allMatch(id -> id.startsWith("wikidata.")),
                byScope.get(BindingScope.CLASS_POPULATION).toString());
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
            }
        }
    }

    @Test void membershipYieldsEntitiesRatherThanText() {
        DatasourceOperation membership = Datasources.standard().require(
                "wikidata", "statement-membership", DatasourceOperation.class);

        assertEquals(SourceValueKind.ENTITY_REFERENCE, membership.outputSchema().kind());
        assertTrue(membership.outputSchema().collection(), "a population is many");
        assertTrue(membership.parameters().stream().anyMatch(ParameterDescriptor::required),
                "a membership rule that names no property selects everything");
    }
}
