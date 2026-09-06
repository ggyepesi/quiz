package wikidata.explore.model;

import canonical.CanonicalizationEngine;
import canonical.KeyedReduction;
import canonical.CanonicalizationPlan;
import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CanonicalizationPlans;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.transform.WikidataCandidates;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a key change does to the records that already exist.
 *
 * <p>A key decides which instances exist, and the only way to find out what a different
 * one produces is to run it. History is the case: 179 office holdings over 173
 * subject/object pairs — six people held the same office twice, and only the dates
 * separate those records. Removing the dates from the key is a legitimate choice and a
 * destructive one.
 *
 * <p>This used to be asserted through the identity editor's preview box, which ran the
 * same engine against sampled instances. The box is gone: only two of the four kinds
 * ever fed it, so on an aggregate or an owned class it could show nothing but an
 * invitation to sample, and sampling belongs to the Sample tab. The QUESTION it answered
 * is real, so it is kept here against the engine — and if the answer is wanted on screen
 * again, it belongs where the instances are, and this is what it would report.
 */
class KeyChangeCombinesRecordsTest {

    private static List<canonical.Candidate> holdings() throws Exception {
        List<WikidataDynamicObject> all = new WikidataDynamicObjectJsonStore().loadAll(
                new File("../data/wikidata/history/history.snapshot.json"));
        List<canonical.Candidate> holdings = new ArrayList<>();
        for (WikidataDynamicObject object : all) {
            if (object != null && "OfficeHolding".equals(object.typeKey())) {
                holdings.add(WikidataCandidates.of(object));
            }
        }
        return holdings;
    }

    private static GeneratedClassModel officeHolding() throws Exception {
        return new GeneratedProjectModelStore()
                .load(new File("../data/wikidata/history/history.model.json"))
                .findClass("OfficeHolding");
    }

    private static KeyedReduction.Result run(
            GeneratedClassModel clazz, List<canonical.Candidate> candidates) {
        CanonicalizationPlan plan = CanonicalizationPlans.of(clazz);
        assertTrue(plan.identified(), "the class must identify its instances");
        return CanonicalizationEngine.canonicalize(
                plan, candidates, WikidataCandidates.stableForm());
    }

    @Test void theConfiguredKeyStillTellsTheRecordsApart() throws Exception {
        var result = run(officeHolding(), holdings());

        assertEquals(0, result.reducedPartitions(),
                "the key that produced these instances combines none of them: "
                        + result.report());
    }

    /** Six people held the same office twice; only the dates separate those records. */
    @Test void droppingTheDatesCombinesTheRecordsThatOnlyTheyToldApart() throws Exception {
        GeneratedClassModel holding = officeHolding();
        holding.canonical().keyFields().removeIf(
                field -> field.equals("startDate") || field.equals("endDate"));

        var result = run(holding, holdings());

        assertTrue(result.reducedPartitions() > 0,
                "a coarser key merges records, and says how many: " + result.report());
    }

    /** Asking what a key would do must not be how a key gets changed. */
    @Test void askingChangesNothing() throws Exception {
        GeneratedClassModel holding = officeHolding();
        List<String> before = List.copyOf(holding.canonical().keyFields());

        run(holding, holdings());
        run(holding, holdings());

        assertEquals(before, holding.canonical().keyFields(),
                "the model is untouched by looking at it");
    }
}
