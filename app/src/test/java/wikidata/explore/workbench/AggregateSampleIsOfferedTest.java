package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.query.logical.SampleAggregateClassQuery;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Selecting an aggregate class offers the sample its production calls for.
 *
 * <p>The panel used to answer "Aggregate class sampling is not implemented yet", which
 * was true and was also the whole reason a modeller could not check that a key groups the
 * way they intended — the one thing an aggregate's editor is for.
 */
class AggregateSampleIsOfferedTest {

    private static Object sampleQueryFor(String className) throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(project);
        panel.edit(project.findClass(className));
        Method build = ModelSourceWorkbenchPanel.class
                .getDeclaredMethod("classSampleQueryForSelected");
        build.setAccessible(true);
        return build.invoke(panel);
    }

    @Test void anAggregateClassIsSampledByItsOwnRoute() throws Exception {
        assertInstanceOf(SampleAggregateClassQuery.class, sampleQueryFor("NobelPrize"),
                "reducing is a production route, and it has an adapter now");
    }

    /** Its bound is stated in keys, because that is what it samples. */
    @Test void theSampleIsCountedInKeys() throws Exception {
        SampleAggregateClassQuery query =
                (SampleAggregateClassQuery) sampleQueryFor("NobelPrize");

        assertTrue(query.parameters().containsKey("keys"),
                "a member count would describe the wrong thing: " + query.parameters());
        assertFalse(query.parameters().containsKey("limit"));
    }

    /** The source class it groups from keeps its own route, unchanged. */
    @Test void theSourceClassIsStillSampledAsAStatementClass() throws Exception {
        assertInstanceOf(wikidata.explore.query.logical.SampleStatementClassQuery.class,
                sampleQueryFor("LaureatesWithMotivation"));
    }
}
