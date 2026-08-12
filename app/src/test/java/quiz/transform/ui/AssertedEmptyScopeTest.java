package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;
import quiz.curation.ScopeFilter;
import wikidata.explore.extract.FieldStatus;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "The source says unknown" is an answer, not a gap.
 *
 * <p>All six films in a 20,000-film run whose narrative location was empty carry a P840
 * statement of snaktype {@code somevalue}: Wikidata asserts the location exists and is
 * unknown — Seven is set in a deliberately unnamed city. Nothing will ever fetch it, so
 * counting them as missing put six items in the worklist that can never be cleared, and
 * every curation run over them correctly reported "not found".
 */
class AssertedEmptyScopeTest {

    private static WikidataDynamicObject film(String qid, String name, FieldStatus status) {
        WikidataDynamicObject f = new WikidataDynamicObject(qid, name);
        f.type("Movies");
        if (status != null) f.fieldStatus("locations", status);
        return f;
    }

    @Test void anAssertedUnknownIsNotCountedAsMissing() {
        WikidataDynamicObject seven = film("Q190908", "Seven", FieldStatus.ASSERTED_UNKNOWN);
        WikidataDynamicObject gap = film("Q1054", "12 Monkeys", null);
        List<WikidataDynamicObject> all = List.of(seven, gap);
        TestDomain domain = new TestDomain(all);

        assertEquals(List.of(gap), select(domain, all, ScopeFilter.MISSING),
                     "only the film nobody has looked up is a gap worth offering");
        assertEquals(List.of(seven), select(domain, all, ScopeFilter.ASSERTED_EMPTY));
    }

    /** "No value" is equally an answer: the source says this relation does not exist. */
    @Test void anAssertedNoneIsAlsoAnAnswer() {
        WikidataDynamicObject none = film("Q1", "Abstract", FieldStatus.ASSERTED_NONE);
        List<WikidataDynamicObject> all = List.of(none);
        TestDomain domain = new TestDomain(all);

        assertTrue(select(domain, all, ScopeFilter.MISSING).isEmpty());
        assertEquals(List.of(none), select(domain, all, ScopeFilter.ASSERTED_EMPTY));
    }

    /** The status is an answer the extraction obtained, so it belongs in the snapshot:
     *  a run that re-derives it is a run that keeps offering an uncurable gap. */
    @Test void theStatusSurvivesASaveAndLoad() throws Exception {
        WikidataDynamicObject seven = film("Q190908", "Seven", FieldStatus.ASSERTED_UNKNOWN);
        File file = Files.createTempFile("status", ".snapshot.json").toFile();
        file.deleteOnExit();

        new WikidataDynamicObjectJsonStore().save(List.of(seven), file);
        List<WikidataDynamicObject> loaded =
                new WikidataDynamicObjectJsonStore().load(file);

        assertEquals(1, loaded.size());
        assertEquals(FieldStatus.ASSERTED_UNKNOWN, loaded.get(0).fieldStatus("locations"));
    }

    /** An ordinary gap records nothing, so existing snapshots load unchanged and the
     *  common path carries no bookkeeping. */
    @Test void anOrdinaryGapCarriesNoStatus() {
        assertNull(film("Q1054", "12 Monkeys", null).fieldStatus("locations"));
    }

    private static List<?> select(
            TestDomain domain, List<WikidataDynamicObject> all, ScopeFilter filter) {
        return FieldCoverageColumns.select(
                domain, all, "Movies", FieldPath.parse("locations"), filter);
    }

    private record TestDomain(List<? extends Viewable> values) implements DomainModel {
        @Override public List<String> types() { return List.of("Movies"); }
        @Override public objectview.field.FieldSchema fieldSchema(String type) {
            return List::of;
        }
        @Override public Collection<? extends Viewable> instances() { return values; }
        @Override public Class<? extends Viewable> universe() {
            return WikidataDynamicObject.class;
        }
    }
}
