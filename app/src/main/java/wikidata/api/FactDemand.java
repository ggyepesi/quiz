package wikidata.api;

import wikidata.WikidataIds;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A downstream pipeline consumer's prospective need for Wikidata claims.
 *
 * <p>The population is deliberately absent: planning can name the class before its
 * QIDs exist. Extraction binds the demand to the members it discovers and retains
 * the requested claim slice; the consumer still records actual demand when it runs.
 */
public record FactDemand(
        String consumer,
        String targetClass,
        Set<String> propertyPids,
        Set<EntityMetadata> metadata,
        String reason) {

    public enum EntityMetadata { LABEL, ALIASES, SITELINKS }

    public static Set<EntityMetadata> allMetadata() {
        return Collections.unmodifiableSet(java.util.EnumSet.allOf(EntityMetadata.class));
    }

    public FactDemand {
        consumer = consumer == null || consumer.isBlank() ? "unspecified" : consumer;
        targetClass = targetClass == null ? "" : targetClass;
        reason = reason == null ? "" : reason;
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (propertyPids != null) {
            for (String pid : propertyPids) {
                if (WikidataIds.isPid(pid)) clean.add(pid);
            }
        }
        propertyPids = Collections.unmodifiableSet(clean);
        metadata = metadata == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(metadata));
    }

    public static FactDemand of(
            String consumer, String targetClass, Collection<String> propertyPids,
            String reason) {
        return new FactDemand(consumer, targetClass,
                propertyPids == null ? Set.of() : new LinkedHashSet<>(propertyPids),
                Set.of(), reason);
    }

    public static FactDemand metadata(
            String consumer, String targetClass, Collection<EntityMetadata> metadata,
            String reason) {
        return new FactDemand(consumer, targetClass, Set.of(),
                metadata == null ? Set.of() : new LinkedHashSet<>(metadata), reason);
    }
}
