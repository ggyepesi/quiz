package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureDiscoveryBridgesTest {

    @Test void theModelOffersItsStatementBridgeWithoutKnowingHistoryFieldNames() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        model.rootClass(position);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        model.addClass(person);
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        entity(holding, "office", "Position");
        entity(holding, "incumbent", "Person");
        model.addClass(holding);

        var bridges = StructureDiscoveryBridges.of(model, "Position");

        assertEquals(1, bridges.size());
        assertEquals("OfficeHolding.office — shared by OfficeHolding.incumbent",
                bridges.getFirst().toString());
    }

    @Test void historyOffersPositionThroughOfficeHoldingSource() throws Exception {
        GeneratedProjectModel history = new GeneratedProjectModelStore().load(
                new java.io.File("../data/wikidata/history/history.model.json"));

        assertEquals(true, StructureDiscoveryBridges.of(history, "Position").stream()
                .anyMatch(bridge -> bridge.rowType().equals("OfficeHolding")
                        && bridge.memberField().equals("position")
                        && bridge.sharedField().equals("source")),
                "the shipped model, not a History-specific branch, supplies the workflow");
    }

    private static void entity(GeneratedClassModel owner, String name, String target) {
        GeneratedFieldModel field = owner.addField(
                name, FieldType.ENTITY, FieldCardinality.SINGLE);
        field.entityClassName(target);
    }
}
