package wikidata.explore.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SubclassClusteringTest {

    @Test public void splitsPersonAndFilmCategories() {
        // Three person-categories share person props; two film-categories share
        // film props; the two families overlap almost not at all.
        Map<String, Set<String>> profiles = Map.of(
                "Best Actor",    Set.of("P569", "P106", "P26", "P19"),
                "Best Actress",  Set.of("P569", "P106", "P26", "P21"),
                "Best Director", Set.of("P569", "P106", "P19"),
                "Best Picture",  Set.of("P57", "P162", "P136", "P577"),
                "Best Animated", Set.of("P57", "P136", "P577"));

        List<SubclassClustering.Cluster> clusters =
                SubclassClustering.cluster(profiles, Map.of(), 0.3, 0.5);

        assertEquals(2, clusters.size(), "two latent subclasses");
        // Largest first: the 3 person-categories.
        assertEquals(3, clusters.get(0).members().size());
        assertTrue(clusters.get(0).members().containsAll(
                List.of("Best Actor", "Best Actress", "Best Director")));
        assertEquals(2, clusters.get(1).members().size());
        assertTrue(clusters.get(1).members().containsAll(
                List.of("Best Picture", "Best Animated")));
    }

    @Test public void signatureReportsSharedProperties() {
        Map<String, Set<String>> profiles = Map.of(
                "Best Actor",   Set.of("P569", "P106", "P26"),
                "Best Actress", Set.of("P569", "P106", "P21"));
        Map<String, String> labels = Map.of(
                "P569", "date of birth", "P106", "occupation");

        List<SubclassClustering.Cluster> clusters =
                SubclassClustering.cluster(profiles, labels, 0.3, 1.0);

        assertEquals(1, clusters.size());
        // minCoverage 1.0 → only the two props BOTH categories carry.
        List<String> sigPids = clusters.get(0).signature().stream()
                .map(SubclassClustering.SignatureProperty::pid).toList();
        assertEquals(List.of("P569", "P106"), sigPids);
        assertEquals("date of birth", clusters.get(0).signature().get(0).label());
    }

    @Test public void unrelatedTargetStaysSingleton() {
        Map<String, Set<String>> profiles = Map.of(
                "Best Actor",   Set.of("P569", "P106"),
                "Best Actress", Set.of("P569", "P106"),
                "Best Song",    Set.of("P86", "P175"));

        List<SubclassClustering.Cluster> clusters =
                SubclassClustering.cluster(profiles, Map.of(), 0.3, 0.5);

        assertEquals(2, clusters.size());
        assertEquals(2, clusters.get(0).members().size());
        assertEquals(List.of("Best Song"), clusters.get(1).members());
    }

    @Test public void jaccardBasics() {
        assertEquals(1.0, SubclassClustering.jaccard(Set.of("a", "b"), Set.of("a", "b")), 1e-9);
        assertEquals(0.0, SubclassClustering.jaccard(Set.of("a"), Set.of("b")), 1e-9);
        assertEquals(1.0 / 3, SubclassClustering.jaccard(Set.of("a", "b"), Set.of("b", "c")), 1e-9);
    }
}
