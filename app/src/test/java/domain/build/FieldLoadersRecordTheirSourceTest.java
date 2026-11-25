package domain.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load field values reviews what Enrich's own loaders write, and the review needs to show
 * where each value came from. Every loader reports that source on the staged copy
 * ({@code AcquiredFieldSource} or {@code recordFieldSource}); a value written without one
 * is refused at run time as a reported failure. This guard makes a new family decide which
 * loader writes its values before it can ship, and checks that loader reports sources.
 */
class FieldLoadersRecordTheirSourceTest {

    /** Family (its implementation's simple name) → the loader that writes its field values,
     *  or "" when the family writes no field values at all. */
    private static final Map<String, String> WRITERS = Map.of(
            "WikidataRelationRoutes", "domain/build/WikidataRelationRouteFieldAcquisition.java",
            "WikidataComputed", "domain/build/WikidataComputedFieldAcquisition.java",
            "Dbpedia", "domain/runtime/DBpediaEnrichment.java",
            "Categories", "",   // retains category memberships as evidence; writes no field
            "Infobox", "domain/build/WikipediaInfoboxAcquisition.java",
            "ArticleIntroductions", "domain/build/WikipediaArticleIntroductionAcquisition.java",
            "Pageviews", "domain/build/PageviewsFieldAcquisition.java");

    @Test void everyRegisteredFamilyNamesTheLoaderThatWritesItsValues() {
        Set<String> registered = StandardExternalSourceFamilies.create().families().stream()
                .map(family -> family.getClass().getSimpleName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(WRITERS.keySet()), registered,
                "a new source family must say here which loader writes its values");
    }

    @Test void everyValueWritingLoaderRecordsTheSourceOfWhatItWrites() throws Exception {
        var loaders = new ArrayList<String>(WRITERS.values());
        loaders.add("domain/compile/transform/ReferentFieldLoad.java");   // Wikidata claims
        for (String loader : loaders) {
            if (loader.isEmpty()) continue;
            String source = Files.readString(Path.of("src/main/java", loader));
            assertTrue(source.contains("AcquiredFieldSource.") || source.contains("recordFieldSource("),
                    loader + " writes field values but records no source for review");
        }
    }
}
