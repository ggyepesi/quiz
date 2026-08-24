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

    /**
     * A category or infobox source is read from an ARTICLE, and a QID names one by its
     * sitelink — so the first acquisition must carry it. Aliases taught this the
     * expensive way: undemanded, the fact store rightly refused the cached document
     * and the whole 11,154-subject population was fetched a second time.
     */
    @Test void anArticleBackedSourceDemandsTheSitelinkThatNamesIt() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        var country = movie.addField("country", FieldType.STRING, FieldCardinality.SINGLE);
        // put(), not sourceBindings().add(): compiling a plan re-derives the field-value
        // slots from the legacy mapping, so a binding merely appended is erased before
        // the plan sees it. put() back-projects, which is what makes it survive.
        FieldSourceBindings.put(country, new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue("Movie", "country",
                        datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new datasource.api.SourceRecipe("wikipedia", "infobox-parameter",
                        java.util.Map.of("property", "Infobox film.country"))));
        GeneratedClassModel person = new GeneratedClassModel("Person");
        model.rootClass(movie);
        model.addClass(person);

        var plan = ModelSourceExecutionPlan.compile(model, Datasources.standard());
        var demands = GenerationFactDemandPlan.compile(model, plan).all();

        assertTrue(demands.stream().anyMatch(d -> d.targetClass().equals("Movie")
                && d.metadata().contains(FactDemand.EntityMetadata.SITELINKS)),
                "the class whose field reads an article: " + demands);
        assertFalse(demands.stream().anyMatch(d -> d.targetClass().equals("Person")
                && d.metadata().contains(FactDemand.EntityMetadata.SITELINKS)),
                "and no other, or every population pays for an article it never reads");
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
