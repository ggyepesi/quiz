package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.transform.ModelAggregates;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An aggregate's sample bounds the KEYS, never the members.
 *
 * <p>Bounding the members is what every other class sample does, and for an aggregate it
 * produces groups that are wrong rather than groups that are small: the first N source
 * records reduce to prizes holding one laureate each, shown as the prizes themselves. The
 * first test here is that defect, reproduced against the real Nobel recipe, so the reason
 * for two passes cannot quietly stop applying.
 */
class AggregateSampleIsCompleteGroupsTest {

    private static GeneratedProjectModel nobel() throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
    }

    /** Why the bound cannot sit on the members. */
    @Test void boundingTheMembersWouldShowIncompleteGroups() throws Exception {
        CompiledProjectModel compiled = ProjectModelCompiler.compile(nobel());

        List<WikidataDynamicObject> everything = sourceRecords();
        List<WikidataDynamicObject> whole = new ArrayList<>(everything);
        ModelAggregates.apply(compiled, whole, null);
        assertEquals(List.of(3, 2), memberCounts(whole),
                "read whole, the two prizes hold every laureate that won them");

        List<WikidataDynamicObject> firstTwo = new ArrayList<>(everything.subList(0, 2));
        ModelAggregates.apply(compiled, firstTwo, null);
        assertEquals(List.of(2), memberCounts(firstTwo),
                "bounded to two records, one prize is shown holding two of its three "
                        + "laureates — a wrong prize, not a small sample");
    }

    /** The chosen keys are the first groups the probe pass found, and what they name. */
    @Test void theProbePassChoosesKeysAndWhatBoundsThem() throws Exception {
        CompiledProjectModel compiled = ProjectModelCompiler.compile(nobel());
        List<WikidataDynamicObject> pool = new ArrayList<>(sourceRecords());
        ModelAggregates.apply(compiled, pool, null);

        SampleDerivedClassQuery.Keys keys =
                SampleDerivedClassQuery.keysOf(pool, "NobelPrize", "category", 1);

        assertEquals(1, keys.ids().size(), "one key was asked for");
        assertEquals(List.of("Q35637"), keys.objectQids(),
                "and it names the category that will bound the second read");
    }

    /**
     * The key component that reaches the acquisition is the one reading the object.
     *
     * <p>Nobel keys on (category, year). Category is the P166 statement's OBJECT, so it
     * becomes an explicit object bound and the second read fetches a third of the corpus
     * instead of all of it. Year is a qualifier, which has no end to bind, and is reached
     * by reducing what comes back.
     */
    @Test void theObjectSideOfTheKeyIsWhatNarrowsTheRead() throws Exception {
        GeneratedProjectModel project = nobel();
        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);
        wikidata.explore.model.ProductionChain chain =
                wikidata.explore.model.ProductionChain.of(
                        project.findClass("NobelPrize"), project);

        assertEquals("LaureatesWithMotivation", chain.population().className(),
                "one link: the prizes are grouped from the award statements");
        assertEquals("category",
                SampleDerivedClassQuery.objectKeyField(compiled, chain));
    }

    /** Only the chosen groups survive the second pass, in the order they were chosen. */
    @Test void thesecondPassKeepsExactlyTheChosenGroups() throws Exception {
        CompiledProjectModel compiled = ProjectModelCompiler.compile(nobel());
        List<WikidataDynamicObject> pool = new ArrayList<>(sourceRecords());
        ModelAggregates.apply(compiled, pool, null);
        List<String> all = SampleDerivedClassQuery
                .keysOf(pool, "NobelPrize", "category", 9).ids();
        assertEquals(2, all.size(), "the fixture makes two prizes");

        List<WikidataDynamicObject> kept = SampleDerivedClassQuery.keep(
                pool, "NobelPrize", List.of(all.get(1), all.get(0)));

        assertEquals(List.of(all.get(1), all.get(0)),
                kept.stream().map(WikidataDynamicObject::getIdentifier).toList(),
                "chosen order, and nothing that was not chosen");
    }

    /** A group with no members at all is not what a missing key produces. */
    @Test void aRecordMissingAKeyComponentJoinsNoGroup() throws Exception {
        CompiledProjectModel compiled = ProjectModelCompiler.compile(nobel());
        List<WikidataDynamicObject> pool = new ArrayList<>(sourceRecords());
        pool.add(record("noYear", "Q35637", null, "Nobody"));
        ModelAggregates.apply(compiled, pool, null);

        assertEquals(List.of(3, 2), memberCounts(pool),
                "Nobel excludes it, so no third prize appears and no group grows");
    }

    /**
     * Five award statements: three physics 1921 laureates, two chemistry 1911.
     *
     * <p>Ordered so that a bound of two cuts the first prize's third laureate, which is
     * exactly the shape a member-bounded sample would show.
     */
    private static List<WikidataDynamicObject> sourceRecords() {
        return new ArrayList<>(List.of(
                record("s1", "Q35637", "1921", "Einstein"),
                record("s2", "Q35637", "1921", "Bohr"),
                record("s3", "Q35637", "1921", "Planck"),
                record("s4", "Q44585", "1911", "Curie"),
                record("s5", "Q44585", "1911", "Rutherford")));
    }

    private static WikidataDynamicObject record(
            String id, String categoryQid, String year, String laureate) {
        WikidataDynamicObject statement = new WikidataDynamicObject(id, laureate);
        statement.type("LaureatesWithMotivation");
        WikidataDynamicObject category = new WikidataDynamicObject(categoryQid, categoryQid);
        statement.put("category", category);
        if (year != null) statement.put("year", year);
        statement.put("laureates", laureate);
        return statement;
    }

    private static List<Integer> memberCounts(List<WikidataDynamicObject> pool) {
        List<Integer> counts = new ArrayList<>();
        for (WikidataDynamicObject object : pool) {
            if (object.directClassNames().contains("NobelPrize")) {
                Object members = object.get("laureatesWithMotivation");
                counts.add(members instanceof List<?> list ? list.size() : 0);
            }
        }
        return counts;
    }
}
