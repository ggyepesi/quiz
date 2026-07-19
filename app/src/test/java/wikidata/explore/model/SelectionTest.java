package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Selection construct — a named non-product configuration (vocabulary /
 * population / facet) referenced but never served. Slice 1: it exists on the
 * project model, configures a VOCABULARY, and survives copy + store round-trip.
 */
class SelectionTest {

    private static VocabularySelection oscarCategories() {
        VocabularySelection s = new VocabularySelection("OscarCategories");
        s.valueTypeQid("Q19020");                       // Academy Award category
        s.valueQids(List.of("Q102427", "Q106301"));     // two explicit categories
        return s;
    }

    @Test void aVocabularyNeedsValuesToBeConfigured() {
        assertFalse(new VocabularySelection("Empty").isConfigured(),
                "a vocabulary with no values/type is not configured");
        assertTrue(oscarCategories().isConfigured());
    }

    @Test void nonQidValuesAreRejected() {
        VocabularySelection s = new VocabularySelection("V");
        s.valueQids(List.of("Q1", "not-a-qid", "P31", "Q2"));
        assertEquals(List.of("Q1", "Q2"), s.valueQids());
    }

    @Test void copyIsDeep() {
        VocabularySelection s = oscarCategories();
        VocabularySelection c = s.copy();
        c.valueQids(List.of("Q999"));
        c.valueTypeQid("Q1");
        assertEquals(List.of("Q102427", "Q106301"), s.valueQids(), "original untouched");
        assertEquals("Q19020", s.valueTypeQid());
    }

    @Test void projectCopyCarriesSelections() {
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.addSelection(oscarCategories());
        GeneratedProjectModel copy = p.copy();
        Selection found = copy.findSelection("oscarcategories");   // case-insensitive
        assertEquals(Selection.Kind.VOCABULARY, found.kind());
        assertEquals(List.of("Q102427", "Q106301"),
                ((VocabularySelection) found).valueQids());
    }

    @Test void aPopulationNeedsARelationToBeConfigured() {
        PopulationSelection pop = new PopulationSelection("OscarNominees");
        assertFalse(pop.isConfigured(), "no relation yet");
        pop.relationPid("P1411");
        pop.targetQids(List.of("Q102427"));
        assertTrue(pop.isConfigured());
    }

    @Test void bothKindsRoundTripThroughTheStore(@TempDir Path dir) throws Exception {
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass().className("Nomination");
        p.addSelection(oscarCategories());
        PopulationSelection pop = new PopulationSelection("OscarNominees");
        pop.relationPid("P1411");
        pop.targetQids(List.of("Q102427", "Q106301"));
        p.addSelection(pop);

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        File f = dir.resolve("m.model.json").toFile();
        store.save(p, f);
        GeneratedProjectModel loaded = store.load(f);

        VocabularySelection v =
                (VocabularySelection) loaded.findSelection("OscarCategories");
        assertEquals(Selection.Kind.VOCABULARY, v.kind());
        assertEquals("Q19020", v.valueTypeQid());
        assertEquals(List.of("Q102427", "Q106301"), v.valueQids());

        PopulationSelection pop2 =
                (PopulationSelection) loaded.findSelection("OscarNominees");
        assertEquals(Selection.Kind.POPULATION, pop2.kind());
        assertEquals("P1411", pop2.relationPid());
        assertEquals(List.of("Q102427", "Q106301"), pop2.targetQids());
    }
}
