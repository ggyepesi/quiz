package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The curated office vocabulary drives the ordinary qualified-relation workflow. */
class HistoricalPositionsOfficeHoldingTest {

    @Test
    void historicalPositionsOwnsOfficeHoldingsBoundedByItsPositions() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(new File(
                "../data/wikidata/historicalpositions/historicalpositions.model.json"));

        GeneratedClassModel holding = model.findClass("OfficeHolding");
        GeneratedClassModel holder = model.findClass("PositionHolder");
        GeneratedClassModel position = model.findClass("Position");

        assertEquals(ClassKind.STATEMENT, holding.classKind());
        assertEquals("P39", holding.statementSource().propertyPid());
        assertEquals(position.membership(), holding.statementSource().objectBound(),
                "the statement object uses the same population as Position");
        assertEquals(EntityBound.unbounded(), holding.statementSource().subjectBound(),
                "holders are discovered from incoming P39 statements");

        assertEquals(List.of("source", "position", "startDate", "endDate",
                        "predecessor", "successor"),
                holding.fields().stream().map(GeneratedFieldModel::name).toList());
        assertEquals(List.of("source", "position", "startDate", "endDate"),
                holding.canonical().keyFields());
        assertTrue(holding.fields().stream()
                .filter(field -> field.type() == datasource.schema.FieldType.ENTITY)
                .filter(field -> !field.name().equals("position"))
                .allMatch(field -> field.entityClassName().equals(holder.className())),
                "people outside Position stay Wikidata-backed PositionHolder references");

        GeneratedFieldModel sitelinks = position.fields().stream()
                .filter(field -> field.name().equals("sitelinkCount")).findFirst().orElseThrow();
        GeneratedFieldModel holders = position.fields().stream()
                .filter(field -> field.name().equals("holderCount")).findFirst().orElseThrow();
        assertEquals(datasource.schema.FieldType.NUMBER, sitelinks.type());
        assertEquals(datasource.schema.FieldType.NUMBER, holders.type());
        assertEquals(FieldSourceType.WIKIDATA_SITELINK_COUNT,
                sitelinks.mapping().sourceType());
        assertEquals(FieldSourceType.WIKIDATA_INCOMING_COUNT,
                holders.mapping().sourceType());
        var sources = ModelSourceExecutionPlan.synchronizeAndCompile(
                model, datasource.Datasources.standard());
        assertEquals(2, sources.familyCount(
                datasource.wikidata.WikidataDatasourceProvider.FAMILY_COMPUTED_FIELD));

        var validation = GeneratedProjectModelValidator.validate(model);
        assertTrue(validation.valid(), validation.errors().toString());
    }
}
