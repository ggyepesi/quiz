package quiz.enrichment;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.RuleDirection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikimediaFieldEnrichmentProviderTest {

    @Test
    void consumesModelBuilderFieldSourceAndRejectsUnsupportedIncomingRule() {
        FieldSourceMapping source = new FieldSourceMapping();
        source.propertyPid("P1082");
        source.propertyLabel("population");
        source.direction(RuleDirection.ROOT_TO_ITEM);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "Q1039", "Tanzania"),
                "population", false, List.of());

        assertTrue(new WikimediaFieldEnrichmentProvider(source).supports(request));
        source.direction(RuleDirection.ITEM_TO_ROOT);
        assertFalse(new WikimediaFieldEnrichmentProvider(source).supports(request));
    }

    @Test
    void readsPopulationQuantityAsANumber() throws Exception {
        String entity = """
                {"entities": {"Q1039": {
                  "claims": {"P1082": [{"rank": "normal",
                    "mainsnak": {"datatype": "quantity",
                      "datavalue": {"value": {"amount": "+59308690", "unit": "1"}}}}]}
                }}}
                """;
        WikimediaFieldEnrichmentProvider provider =
                new WikimediaFieldEnrichmentProvider("P1082", uri -> entity);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "Q1039", "Tanzania"),
                "population", false, List.of());

        EnrichmentProposal result =
                provider.discover(request).execute(new QueryContext(null, null));

        assertEquals(1, result.fields().size());
        EnrichmentProposal.FieldCandidate field = result.fields().get(0);
        assertEquals("population", field.field());
        assertEquals(59308690L, field.proposedValue());
        assertEquals(EnrichmentProposal.ReviewAction.FILL_IF_EMPTY, field.suggestedAction());
        // an identity is emitted so the decision applies as a Wikidata-origin Correction
        assertEquals(1, result.identities().size());
        assertEquals("Wikidata", result.identities().get(0).source().kind());
    }

    @Test
    void proposesReplaceWhenCurationScopeContainsAnExistingValue() throws Exception {
        String entity = """
                {"entities":{"Q1":{"claims":{"P1082":[{"rank":"normal",
                  "mainsnak":{"datavalue":{"value":{"amount":"+20","unit":"1"}}}}]}}}}
                """;
        WikimediaFieldEnrichmentProvider provider =
                new WikimediaFieldEnrichmentProvider("P1082", uri -> entity);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "Q1", "One"),
                "population", false, List.of(), null, 10L);

        EnrichmentProposal.FieldCandidate field = provider.discover(request)
                .execute(new QueryContext(null, null)).fields().get(0);

        assertEquals(10L, field.currentValue());
        assertEquals(EnrichmentProposal.ReviewAction.REPLACE, field.suggestedAction());
    }

    @Test
    void prefersPreferredRankAndSkipsDeprecated() throws Exception {
        String entity = """
                {"entities": {"Q1039": {
                  "claims": {"P1082": [
                    {"rank": "deprecated", "mainsnak": {"datavalue": {"value": {"amount": "+1"}}}},
                    {"rank": "normal", "mainsnak": {"datavalue": {"value": {"amount": "+100"}}}},
                    {"rank": "preferred", "mainsnak": {"datavalue": {"value": {"amount": "+200"}}}}
                  ]}
                }}}
                """;
        WikimediaFieldEnrichmentProvider provider =
                new WikimediaFieldEnrichmentProvider("P1082", uri -> entity);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "Q1039", "X"),
                "population", false, List.of());

        EnrichmentProposal result =
                provider.discover(request).execute(new QueryContext(null, null));

        assertEquals(200L, result.fields().get(0).proposedValue());
    }

    @Test
    void choosesLatestPointInTimeBeforeClaimOrder() throws Exception {
        String entity = """
                {"entities": {"Q1039": {
                  "claims": {"P1082": [
                    {"rank": "normal", "mainsnak": {"datavalue":
                      {"value": {"amount": "+100", "unit": "1"}}},
                     "qualifiers": {"P585": [{"datatype": "time", "datavalue":
                       {"value": {"time": "+2010-01-01T00:00:00Z", "precision": 11}}}]}},
                    {"rank": "normal", "mainsnak": {"datavalue":
                      {"value": {"amount": "+300", "unit": "1"}}},
                     "qualifiers": {"P585": [{"datatype": "time", "datavalue":
                       {"value": {"time": "+2024-01-01T00:00:00Z", "precision": 11}}}]}},
                    {"rank": "preferred", "mainsnak": {"datavalue":
                      {"value": {"amount": "+200", "unit": "1"}}},
                     "qualifiers": {"P585": [{"datatype": "time", "datavalue":
                       {"value": {"time": "+2020-01-01T00:00:00Z", "precision": 11}}}]}}
                  ]}
                }}}
                """;
        WikimediaFieldEnrichmentProvider provider =
                new WikimediaFieldEnrichmentProvider("P1082", uri -> entity);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "Q1039", "X"),
                "population", false, List.of());

        EnrichmentProposal result =
                provider.discover(request).execute(new QueryContext(null, null));

        assertEquals(300L, result.fields().get(0).proposedValue());
    }

    @Test
    void unsupportedWithoutQidOrChosenProperty() {
        WikimediaFieldEnrichmentProvider provider =
                new WikimediaFieldEnrichmentProvider("P1082", uri -> "{}");
        assertFalse(provider.supports(new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", null, "X"),
                "population", false, List.of())));            // no QID
        assertTrue(provider.supports(new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "Q1039", "X"),
                "anyFieldName", false, List.of())));          // explicit P1082
        assertTrue(provider.supports(new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "Q1039", "X"),
                "population", false, List.of())));            // names are irrelevant
        WikimediaFieldEnrichmentProvider noChosenProperty =
                new WikimediaFieldEnrichmentProvider((String) null);
        assertFalse(noChosenProperty.supports(new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "Q1039", "X"),
                "population", false, List.of())));
    }

    @Test
    void readsAnEXPLICITLYchosenPropertyEvenWhenTheNameMapsToNothing() throws Exception {
        String entity = """
                {"entities": {"Q782": {"claims": {"P571": [{"rank": "normal",
                  "mainsnak": {"datavalue": {"value": {"time": "+1959-08-21T00:00:00Z"}}}}]}
                }}}
                """;
        // "admissionDate" maps to no default property; the property is CHOSEN (P571).
        WikimediaFieldEnrichmentProvider provider =
                new WikimediaFieldEnrichmentProvider("P571", uri -> entity);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("State", "hawaii", "Q782", "Hawaii"),
                "admissionDate", false, List.of());

        assertTrue(provider.supports(request));
        EnrichmentProposal result =
                provider.discover(request).execute(new QueryContext(null, null));

        assertEquals(1, result.fields().size());
        // formatted by shape: a non-Jan-1 time → the full ISO date
        assertEquals("1959-08-21", result.fields().get(0).proposedValue());
    }

    @Test
    void resolvesEntityClaimToLabelForTextCollection() throws Exception {
        String entity = """
                {"entities":{"Q30":{"claims":{"P36":[{"rank":"normal",
                  "mainsnak":{"datatype":"wikibase-item","datavalue":{"value":{"id":"Q61"}}}}]}}}}
                """;
        String label = """
                {"entities":{"Q61":{"labels":{"en":{"value":"Washington, D.C."}}}}}
                """;
        WikimediaFieldEnrichmentProvider provider =
                new WikimediaFieldEnrichmentProvider("P36",
                        uri -> uri.toString().contains("props=labels") ? label : entity);
        objectview.field.FieldRef capitals = objectview.field.FieldRef.described(
                "capitals", objectview.field.FieldKind.COLLECTION,
                objectview.field.FieldKind.TEXT, "Collection<String>",
                false, true, null, false, false,
                false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("State", "usa", "Q30", "United States"),
                "capitals", true, List.of(), capitals);

        EnrichmentProposal.FieldCandidate candidate = provider.discover(request)
                .execute(new QueryContext(null, null)).fields().get(0);

        assertEquals("Washington, D.C.", candidate.proposedValue());
        assertEquals(EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                candidate.suggestedAction());
    }

    @Test
    void rejectsGeoShapeStringForDeclaredImageCollectionAndKeepsPropertySource()
            throws Exception {
        String entity = """
                {"entities": {"Q133888": {"claims": {"P3896": [{"rank": "normal",
                  "mainsnak": {"datavalue": {"value":
                    "Data:Ashmore and Cartier Islands.map"}}}]}}}}
                """;
        WikimediaFieldEnrichmentProvider provider =
                new WikimediaFieldEnrichmentProvider("P3896", uri -> entity);
        objectview.field.FieldRef shapeVersions = objectview.field.FieldRef.described(
                "shapeVersions", objectview.field.FieldKind.COLLECTION,
                objectview.field.FieldKind.MEDIA, "Collection<ImagePane>",
                false, true, null, false, false,
                true, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(
                        "State", "Ashmore", "Q133888", "Ashmore"),
                "shapeVersions", true, List.of(), shapeVersions);

        EnrichmentProposal.FieldCandidate candidate = provider.discover(request)
                .execute(new QueryContext(null, null)).fields().get(0);

        assertFalse(candidate.compatible());
        assertEquals(EnrichmentProposal.ReviewAction.IGNORE,
                candidate.suggestedAction());
        assertEquals("P3896", candidate.source().propertyId());
        assertTrue(candidate.compatibilityError().contains("MEDIA"));
    }
}
