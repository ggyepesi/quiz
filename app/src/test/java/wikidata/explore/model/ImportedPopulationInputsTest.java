package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportedPopulationInputsTest {

    @Test void onlyAPopulationReferencedByALocalConstructCrossesTheImportBoundary() {
        GeneratedProjectModel history = new GeneratedProjectModel();
        history.name("History");
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource();
        source.propertyPid("P39");
        source.objectBound(EntityBound.vocabulary("PositionsWithHolders"));
        holding.statementSource(source);
        history.rootClass(holding);

        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.importedFrom("Historical Positions");
        history.addClass(position);

        PopulationSelection all = population(
                "AllPositions", "Position", "Q1", "Q2", "Q3");
        all.importedFrom("Historical Positions");
        history.addSelection(all);
        PopulationSelection selected = population(
                "PositionsWithHolders", "Position", "Q2", "Q4");
        selected.importedFrom("Historical Positions");
        history.addSelection(selected);

        ImportedPopulationInputs inputs = ImportedPopulationInputs.of(history);

        assertEquals(List.of("Q2", "Q4"), inputs.qidsFor("Position"));
        assertEquals(List.of("PositionsWithHolders"),
                inputs.selectionNamesFor("Position"));
    }

    @Test void anImportedPopulationBesideAClassIsNotImplicitlyConsumed() {
        GeneratedProjectModel history = new GeneratedProjectModel();
        history.name("History");
        history.rootClass(new GeneratedClassModel("Person"));
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.importedFrom("Historical Positions");
        history.addClass(position);
        PopulationSelection population = population(
                "PositionsWithHolders", "Position", "Q2", "Q4");
        population.importedFrom("Historical Positions");
        history.addSelection(population);

        assertTrue(ImportedPopulationInputs.of(history).qidsFor("Position").isEmpty());
    }

    @Test void severalReferencedPopulationsOfOneClassContributeTheirUnion() {
        GeneratedProjectModel history = new GeneratedProjectModel();
        history.name("History");
        GeneratedClassModel firstConsumer = new GeneratedClassModel("FirstHolding");
        firstConsumer.membership(EntityBound.vocabulary("FirstPositions"));
        history.rootClass(firstConsumer);
        GeneratedClassModel secondConsumer = new GeneratedClassModel("SecondHolding");
        secondConsumer.membership(EntityBound.vocabulary("SecondPositions"));
        history.addClass(secondConsumer);
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.importedFrom("Historical Positions");
        history.addClass(position);
        PopulationSelection first = population(
                "FirstPositions", "Position", "Q1", "Q2");
        first.importedFrom("Historical Positions");
        history.addSelection(first);
        PopulationSelection second = population(
                "SecondPositions", "Position", "Q2", "Q3");
        second.importedFrom("Historical Positions");
        history.addSelection(second);

        assertEquals(List.of("Q1", "Q2", "Q3"),
                ImportedPopulationInputs.of(history).qidsFor("Position"));
    }

    private static PopulationSelection population(
            String name, String className, String... qids) {
        PopulationSelection population = new PopulationSelection(name);
        population.className(className);
        population.instanceQids(List.of(qids));
        return population;
    }
}
