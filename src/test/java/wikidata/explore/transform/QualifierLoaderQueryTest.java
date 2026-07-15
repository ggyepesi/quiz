package wikidata.explore.transform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The qualifier-load query drives from the entity batch (VALUES ?e) and pins the
 * value to the explicit categories with a post-bind FILTER (not a second VALUES
 * driver), so the selective entity set drives the join — and it drops the broad
 * P31 type filter.
 */
class QualifierLoaderQueryTest {

    private static QualifierLoadConfig cfgWithCategories() {
        return new QualifierLoadConfig(
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "target", "Q19020",
                List.of(new QualifierLoadConfig.Qualifier("P585", "year",
                        QualifierLoadConfig.Kind.YEAR)),
                List.of("Q102427", "Q103360"));   // two Oscar categories
    }

    @Test void drivesFromEntityBatchAndFiltersTheValue() {
        // Entity-anchored (has explicit categories): batch is the pool qids.
        String q = QualifierLoader.buildQuery(
                cfgWithCategories(), List.of("Q1", "Q2"), false);

        assertTrue(q.contains("VALUES ?e { wd:Q1 wd:Q2 }"), q);
        // The category constraint is a post-bind FILTER, not a VALUES driver.
        assertTrue(q.contains("FILTER(?value IN (wd:Q102427, wd:Q103360))"), q);
        assertFalse(q.contains("VALUES ?value"), q);
        // Explicit categories replace the broad P31 type filter.
        assertFalse(q.contains("wdt:P31 wd:Q19020"), q);
        assertTrue(q.contains("?e p:P1411 ?st"), q);
        assertTrue(q.contains("?st ps:P1411 ?value"), q);
    }

    @Test void withoutCategoriesKeepsP31Filter() {
        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "target", "Q19020", List.of());   // no explicit categories

        String q = QualifierLoader.buildQuery(cfg, List.of("Q1"), false);
        assertTrue(q.contains("wdt:P31 wd:Q19020"), q);
        assertFalse(q.contains("VALUES ?value"), q);
    }
}
