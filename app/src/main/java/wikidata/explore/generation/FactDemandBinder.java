package wikidata.explore.generation;

import wikidata.WikidataIds;
import wikidata.api.WikidataFactStore;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Binds population-independent pipeline demands to QIDs at an acquisition boundary.
 * This is the single transition from "class X will need P" to exact retained
 * {@code <QID, property>} pairs. Binding is idempotent in {@link WikidataFactStore}.
 */
public final class FactDemandBinder {
    public record Binding(
            int entities, long claimPairs, long metadataPairs, int consumers) {
        public static final Binding EMPTY = new Binding(0, 0, 0, 0);
    }

    private FactDemandBinder() { }

    public static Binding bind(
            Collection<FactDemand> demands,
            Collection<String> populationQids,
            WikidataFactStore facts,
            String boundary) {
        if (demands == null || demands.isEmpty() || populationQids == null
                || populationQids.isEmpty() || facts == null) return Binding.EMPTY;

        LinkedHashSet<String> qids = new LinkedHashSet<>();
        populationQids.stream().filter(WikidataIds::isQid).forEach(qids::add);
        if (qids.isEmpty()) return Binding.EMPTY;

        LinkedHashSet<String> claimProperties = new LinkedHashSet<>();
        LinkedHashSet<FactDemand.EntityMetadata> metadata = new LinkedHashSet<>();
        int consumers = 0;
        for (FactDemand demand : demands) {
            if (demand == null) continue;
            if (!demand.propertyPids().isEmpty()) {
                facts.recordRetentionPlan(source(boundary, demand), qids,
                        demand.propertyPids());
                claimProperties.addAll(demand.propertyPids());
            }
            metadata.addAll(demand.metadata());
            if (!demand.propertyPids().isEmpty() || !demand.metadata().isEmpty()) {
                consumers++;
            }
        }
        return new Binding(qids.size(),
                (long) qids.size() * claimProperties.size(),
                (long) qids.size() * metadata.size(), consumers);
    }

    public static Binding bind(
            FactDemand demand, Collection<String> populationQids,
            WikidataFactStore facts, String boundary) {
        return bind(List.of(demand), populationQids, facts, boundary);
    }

    private static String source(String boundary, FactDemand demand) {
        String where = boundary == null || boundary.isBlank()
                ? "population binding" : boundary;
        return where + " → " + demand.consumer();
    }
}
