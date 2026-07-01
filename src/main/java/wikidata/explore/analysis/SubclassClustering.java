package wikidata.explore.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cluster the membership targets of a multi-target class by the similarity of
 * their property profiles, surfacing the latent subclass structure.
 *
 * <p>Step 2 of subclass discovery (step 1 = the per-target property profile +
 * property→targets inverse index from {@code DiscoverPropertiesByTargetQuery}).
 * Each Oscar award category, profiled by the properties its instances carry,
 * lands near the other categories that share those properties — the
 * person-categories (date of birth, occupation, spouse…) cluster apart from the
 * film-categories (genre, director, award received…), so each cluster is a
 * candidate {@code extends} subclass (Person / Film) of the nominee root.
 *
 * <p>Pure computation over the already-fetched profiles — no SPARQL, no Swing —
 * so it is unit-testable. Agglomerative average-linkage over Jaccard similarity:
 * start one cluster per target, repeatedly merge the most-similar pair while
 * their similarity is at least the threshold. Average linkage (mean similarity
 * over the cross pairs) resists chaining better than single linkage and stays
 * cheap at the ~50-target scale we see.
 */
public final class SubclassClustering {

    private SubclassClustering() {}

    /** A property that characterises a cluster, with its within-cluster coverage. */
    public record SignatureProperty(String pid, String label, int members, int clusterSize) {
        public double coverage() {
            return clusterSize == 0 ? 0 : (double) members / clusterSize;
        }
    }

    /** A candidate subclass: the targets that cluster together + what they share. */
    public record Cluster(List<String> members, List<SignatureProperty> signature) {}

    /**
     * @param profiles    target → set of property pids its instances carry
     * @param labelByPid  pid → human label (for signature display); may be partial
     * @param threshold   minimum average Jaccard similarity to keep merging (0..1)
     * @param signatureMinCoverage  a property is part of a cluster's signature when
     *                              at least this fraction of members carry it (0..1)
     * @return clusters, largest first; singletons included (a target unlike any other)
     */
    public static List<Cluster> cluster(Map<String, Set<String>> profiles,
                                        Map<String, String> labelByPid,
                                        double threshold,
                                        double signatureMinCoverage) {
        // Preserve input order so equal-size clusters are stable/reproducible.
        List<String> targets = new ArrayList<>(profiles.keySet());
        List<List<String>> clusters = new ArrayList<>();
        for (String t : targets) {
            List<String> c = new ArrayList<>();
            c.add(t);
            clusters.add(c);
        }

        // Merge the most-similar pair until no pair reaches the threshold.
        while (clusters.size() > 1) {
            int bestI = -1, bestJ = -1;
            double bestSim = -1;
            for (int i = 0; i < clusters.size(); i++) {
                for (int j = i + 1; j < clusters.size(); j++) {
                    double sim = averageLinkage(clusters.get(i), clusters.get(j), profiles);
                    if (sim > bestSim) {
                        bestSim = sim;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }
            if (bestSim < threshold || bestI < 0) {
                break;
            }
            clusters.get(bestI).addAll(clusters.get(bestJ));
            clusters.remove(bestJ);
        }

        List<Cluster> result = new ArrayList<>();
        for (List<String> members : clusters) {
            result.add(new Cluster(members,
                    signature(members, profiles, labelByPid, signatureMinCoverage)));
        }
        result.sort(Comparator.comparingInt((Cluster c) -> c.members().size()).reversed());
        return result;
    }

    /** Mean Jaccard similarity over every cross pair of two clusters' targets. */
    private static double averageLinkage(List<String> a, List<String> b,
                                         Map<String, Set<String>> profiles) {
        double sum = 0;
        int pairs = 0;
        for (String x : a) {
            for (String y : b) {
                sum += jaccard(profiles.getOrDefault(x, Set.of()),
                        profiles.getOrDefault(y, Set.of()));
                pairs++;
            }
        }
        return pairs == 0 ? 0 : sum / pairs;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String x : a) {
            if (b.contains(x)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    /** Properties carried by at least {@code minCoverage} of the cluster, best first. */
    private static List<SignatureProperty> signature(List<String> members,
                                                     Map<String, Set<String>> profiles,
                                                     Map<String, String> labelByPid,
                                                     double minCoverage) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String m : members) {
            for (String pid : profiles.getOrDefault(m, Set.of())) {
                counts.merge(pid, 1, Integer::sum);
            }
        }
        int size = members.size();
        List<SignatureProperty> sig = new ArrayList<>();
        counts.forEach((pid, n) -> {
            if ((double) n / size >= minCoverage) {
                String label = labelByPid == null ? null : labelByPid.get(pid);
                sig.add(new SignatureProperty(pid, label == null ? pid : label, n, size));
            }
        });
        sig.sort(Comparator.comparingInt(SignatureProperty::members).reversed()
                .thenComparing(SignatureProperty::label));
        return sig;
    }

    /**
     * Convenience adapter from the flat {@code (target, property, pid)} rows the
     * by-target discovery emits to the {@code (profiles, labelByPid)} this needs.
     * Rows are {@code List<Object>} of {@code [targetLabel, propLabel, pid, count]}.
     */
    public static List<Cluster> clusterRows(List<List<Object>> rows,
                                            double threshold,
                                            double signatureMinCoverage) {
        Map<String, Set<String>> profiles = new LinkedHashMap<>();
        Map<String, String> labelByPid = new LinkedHashMap<>();
        for (List<Object> r : rows) {
            String target = cell(r, 0);
            String label = cell(r, 1);
            String pid = cell(r, 2);
            if (target.isBlank() || pid.isBlank()) {
                continue;
            }
            profiles.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(pid);
            labelByPid.putIfAbsent(pid, label);
        }
        return cluster(profiles, labelByPid, threshold, signatureMinCoverage);
    }

    private static String cell(List<Object> r, int i) {
        Object v = r == null || i >= r.size() ? null : r.get(i);
        return v == null ? "" : v.toString();
    }
}
