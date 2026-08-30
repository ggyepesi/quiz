package datasource.wikidata;

import datasource.api.BindingScope;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;
import datasource.api.SourceValueKind;
import datasource.api.SourceRecipe;
import datasource.api.acquisition.ClassPopulationOperation;
import datasource.api.acquisition.PopulationSelection;
import org.junit.jupiter.api.Test;
import datasource.schema.FieldType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second provider, and the one that tests whether the abstraction is a catalogue or
 * only a plugin list.
 *
 * <p>Wikidata may be chosen as a class's identity authority: it supplies the identifier
 * that class is keyed by and the label its instances display. The provider contract does
 * not make that choice globally; it only makes the offering available to a class.
 */
class WikidataOfferingsTest {

    private static final DatasourceProvider WIKIDATA = new WikidataDatasourceProvider();

    private static DatasourceOperation offering(String id) {
        return WIKIDATA.operation(id).orElseThrow();
    }

    @Test void identityAndLabelAreOfferingsLikeAnyOther() {
        // The claim the whole design rests on. Before this, which source identified a
        // class was implicit in the extraction layer and could not be stated at all.
        assertEquals(BindingScope.CLASS_IDENTITY,
                offering(WikidataDatasourceProvider.IDENTIFIER).scope());
        assertEquals(BindingScope.CLASS_NAMES,
                offering(WikidataDatasourceProvider.LABEL).scope());
    }

    @Test void aliasesAreAnOfferingAClassMayDecline() {
        // A binding now makes code generation add the minor alternateNames field;
        // declining the offering omits both its acquisition and that field.
        DatasourceOperation aliases = offering(WikidataDatasourceProvider.ALIASES);

        assertEquals(BindingScope.CLASS_NAMES, aliases.scope());
        assertTrue(aliases.outputSchema().collection(),
                "several values, which is why they became a list field");
    }

    @Test void anOfferingSaysWhatModelTypeItBecomes() {
        // One vocabulary rather than a conversion written by hand at each place a
        // discovered value turns into a field.
        assertEquals(FieldType.ENTITY,
                offering(WikidataDatasourceProvider.IDENTIFIER)
                        .outputSchema().kind().fieldType());
        assertEquals(FieldType.STRING,
                offering(WikidataDatasourceProvider.DESCRIPTION)
                        .outputSchema().kind().fieldType());
    }

    @Test void somethingThatIsNotAFieldValueSaysSo() {
        // A retrieved document is evidence, not a field — a binding editor needs to know
        // that before it offers the binding, not after someone tries it.
        assertNull(SourceValueKind.DOCUMENT.fieldType());
        assertTrue(!SourceValueKind.DOCUMENT.bindableToField());
        assertTrue(SourceValueKind.LANGUAGE_TEXT.bindableToField());
    }

    @Test void theLabelCarriesItsLanguagePreferenceAsAParameter() {
        // en,mul is not a default anyone should have to know: mul is Wikidata's
        // multilingual label and is many entities' only Latin-script name.
        assertEquals("en,mul", offering(WikidataDatasourceProvider.LABEL).parameters()
                .stream().filter(p -> p.key().equals("languages"))
                .findFirst().orElseThrow().defaultValue());
    }

    @Test void everyOfferingSaysWhereItMayBeBound() {
        List<? extends DatasourceOperation> offerings = WIKIDATA.operations();

        assertTrue(!offerings.isEmpty());
        offerings.forEach(offering -> {
            assertTrue(offering.scope() != null, offering.id() + " has no binding scope");
            assertTrue(offering.outputSchema() != null,
                    offering.id() + " advertises no output shape");
        });
    }

    @Test void populationOfferingsExposeTheLogicalSelectionGenerationConsumes() {
        ClassPopulationOperation membership = (ClassPopulationOperation)
                offering(WikidataDatasourceProvider.STATEMENT_MEMBERSHIP);
        PopulationSelection relation = membership.selection(new SourceRecipe(
                "wikidata", "statement-membership",
                Map.of("property", "P31", "values", "Q11424,Q202866")));
        ClassPopulationOperation seeds = (ClassPopulationOperation)
                offering(WikidataDatasourceProvider.SEED_LIST);
        PopulationSelection explicit = seeds.selection(new SourceRecipe(
                "wikidata", "seed-list", Map.of("ids", "Q42,Q1")));

        assertEquals(PopulationSelection.Kind.RELATION, relation.kind());
        assertEquals("P31", relation.relationId());
        assertEquals(List.of("Q11424", "Q202866"), relation.values().stream()
                .map(datasource.EntityRef::id).toList());
        assertEquals(PopulationSelection.Kind.EXPLICIT, explicit.kind());
        assertEquals(List.of("Q42", "Q1"), explicit.values().stream()
                .map(datasource.EntityRef::id).toList());
    }
}
