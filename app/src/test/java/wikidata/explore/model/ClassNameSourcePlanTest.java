package wikidata.explore.model;

import datasource.Datasources;
import org.junit.jupiter.api.Test;
import wikidata.api.FactDemand;
import wikidata.explore.generation.GenerationFactDemandPlan;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassNameSourcePlanTest {

    @Test void onlySourceClassesDeclareWikidataLabelsAndAliases() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.classKind(ClassKind.OWNED);
        model.rootClass(person);
        model.addClass(name);

        var plan = ModelSourceExecutionPlan.compile(model, Datasources.standard());

        assertEquals(Set.of("Person"), ClassNameSourcePlan.labels(plan));
        assertEquals(Set.of("Person"), ClassNameSourcePlan.aliases(plan));
        var demands = GenerationFactDemandPlan.compile(model, plan).all();
        assertTrue(demands.stream().anyMatch(d -> d.targetClass().equals("Person")
                && d.metadata().contains(FactDemand.EntityMetadata.ALIASES)));
        assertFalse(demands.stream().anyMatch(d -> d.targetClass().equals("Name")
                && !d.metadata().isEmpty()),
                "an owned projection derives its name and must not acquire entity aliases");
    }

    @Test void anExplicitPlanMayDeclineAliasesWithoutDecliningTheLabel() {
        var label = new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.classNames(
                        "Person", datasource.api.SourceBindingSlot.CLASS_LABEL),
                new datasource.api.SourceRecipe(
                        datasource.wikidata.WikidataDatasourceProvider.ID,
                        datasource.wikidata.WikidataDatasourceProvider.LABEL,
                        java.util.Map.of()));
        var plan = datasource.api.SourceExecutionPlan.compile(
                java.util.List.of(label), Datasources.standard());

        assertEquals(Set.of("Person"), ClassNameSourcePlan.labels(plan));
        assertTrue(ClassNameSourcePlan.aliases(plan).isEmpty());
    }
}
