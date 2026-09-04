package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.query.logical.SampleDerivedClassQuery;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class with no population of its own is sampled by one route, whichever kind it is.
 *
 * <p>The panel used to answer "not implemented yet" for both, which was true and was also
 * the whole reason a modeller could not check the one thing these editors are for —
 * whether a key groups as intended, whether a part belongs to the right owner.
 */
class DerivedClassSampleIsOfferedTest {

    private static Object sampleQueryFor(String className) throws Exception {
        return sampleQueryFor("nobelprizes", className);
    }

    private static Object sampleQueryFor(String domain, String className)
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/" + domain + "/" + domain + ".model.json"));
        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(project);
        panel.edit(project.findClass(className));
        Method build = ModelSourceWorkbenchPanel.class
                .getDeclaredMethod("classSampleQueryForSelected");
        build.setAccessible(true);
        return build.invoke(panel);
    }

    @Test void anAggregateClassIsSampledByItsProductionChain() throws Exception {
        assertInstanceOf(SampleDerivedClassQuery.class, sampleQueryFor("NobelPrize"),
                "reducing is a production route, and it has an adapter now");
    }

    /** The same route, because it is the same question: what is this produced from? */
    @Test void anOwnedClassIsSampledByTheSameRoute() throws Exception {
        assertInstanceOf(SampleDerivedClassQuery.class,
                sampleQueryFor("person", "Name"),
                "owned and aggregated differ in where the bound sits, not in the route");
    }

    /** And its bound is stated in owners, since nothing on its chain reduces. */
    @Test void anOwnedSampleIsCountedInOwners() throws Exception {
        SampleDerivedClassQuery query =
                (SampleDerivedClassQuery) sampleQueryFor("person", "Name");

        assertTrue(query.parameters().containsKey("owners"), query.parameters().toString());
        assertEquals("Person", query.parameters().get("population"));
    }

    /** Its bound is stated in keys, because a reduction is what it walks through. */
    @Test void theSampleIsCountedInWhatTheBoundIsOn() throws Exception {
        SampleDerivedClassQuery query =
                (SampleDerivedClassQuery) sampleQueryFor("NobelPrize");

        assertTrue(query.parameters().containsKey("keys"),
                "a member count would describe the wrong thing: " + query.parameters());
        assertFalse(query.parameters().containsKey("limit"));
        assertEquals("LaureatesWithMotivation", query.parameters().get("population"),
                "and it names the class the bound actually sits on");
    }

    /** The source class it groups from keeps its own route, unchanged. */
    @Test void theSourceClassIsStillSampledAsAStatementClass() throws Exception {
        assertInstanceOf(wikidata.explore.query.logical.SampleStatementClassQuery.class,
                sampleQueryFor("LaureatesWithMotivation"));
    }
}
