package workbench;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldDefinition;
import wikidata.explore.model.FieldRenderMode;

import javax.swing.JComboBox;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Cardinality is authored, not guessed from an unrepresentative sample. */
class FieldCardinalityChoiceTest {

    @Test void countOffersOnlySingleValueOrList() {
        FieldDefinitionPanel panel = new FieldDefinitionPanel();
        JComboBox<FieldCardinality> choices = panel.cardinalityBox();

        assertEquals(List.of(FieldCardinality.SINGLE, FieldCardinality.COLLECTION),
                IntStream.range(0, choices.getItemCount())
                        .mapToObj(choices::getItemAt)
                        .toList());
    }

    @Test void theStoredModelHasNoAutoCardinalityState() {
        assertArrayEquals(new FieldCardinality[] {
                        FieldCardinality.SINGLE, FieldCardinality.COLLECTION},
                FieldCardinality.values());
    }

    @Test void legacyAutoJsonMapsToTheOnlyFormerEffectiveValue() {
        FieldDefinitionPanel panel = new FieldDefinitionPanel();

        panel.edit(new FieldDefinition("date", FieldType.DATE, "",
                FieldCardinality.fromJson("AUTO"), FieldRenderMode.INLINE));

        assertEquals(FieldCardinality.SINGLE,
                panel.cardinalityBox().getSelectedItem());
    }

    @Test void aFieldCreatedOutsideThePanelIsBornSingle() {
        assertEquals(FieldCardinality.SINGLE,
                new wikidata.explore.model.GeneratedFieldModel().cardinality());
    }
}
