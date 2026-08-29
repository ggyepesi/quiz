package wikidata.explore.demo.closure;

import wikidata.WikidataIds;

import java.util.List;

/**
 * Defines a closure whose values are connected through shared population members:
 *
 * <pre>
 * sourceValue &lt;- populationProperty - member - nextWaveProperty -&gt; targetValue
 * </pre>
 *
 * <p>With both properties set to P39, positions are connected when one person held both.
 */
public record SharedPopulationClosureConfig(
        String startValueQid,
        String populationPropertyPid,
        String nextWavePropertyPid,
        int maxDepth,
        int batchSize,
        int maxValues,
        int maxMembershipsPerUnit,
        int maxConnectionsPerUnit,
        List<String> targetRootQids) {

    public static final int DEFAULT_MAX_MEMBERSHIPS_PER_UNIT = 2_000;
    public static final int DEFAULT_MAX_CONNECTIONS_PER_UNIT = 5_000;

    public static final List<String> DEFAULT_POSITION_ROOTS = List.of(
            "Q4164871", // position
            "Q116");    // monarch; includes subclasses such as emperor

    public SharedPopulationClosureConfig {
        requireQid(startValueQid, "startValueQid");
        requirePid(populationPropertyPid, "populationPropertyPid");
        requirePid(nextWavePropertyPid, "nextWavePropertyPid");
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth must be >= 0");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        if (maxValues < 1) throw new IllegalArgumentException("maxValues must be >= 1");
        if (maxMembershipsPerUnit < 1) {
            throw new IllegalArgumentException("maxMembershipsPerUnit must be >= 1");
        }
        if (maxConnectionsPerUnit < 1) {
            throw new IllegalArgumentException("maxConnectionsPerUnit must be >= 1");
        }
        targetRootQids = targetRootQids == null
                ? List.of() : List.copyOf(targetRootQids);
        targetRootQids.forEach(qid -> requireQid(qid, "targetRootQids"));
    }

    /** Retains the original API and applies the safe position-oriented roots. */
    public SharedPopulationClosureConfig(
            String startValueQid,
            String populationPropertyPid,
            String nextWavePropertyPid,
            int maxDepth,
            int batchSize,
            int maxValues) {
        this(startValueQid, populationPropertyPid, nextWavePropertyPid,
                maxDepth, batchSize, maxValues,
                DEFAULT_MAX_MEMBERSHIPS_PER_UNIT,
                DEFAULT_MAX_CONNECTIONS_PER_UNIT,
                DEFAULT_POSITION_ROOTS);
    }

    public SharedPopulationClosureConfig(
            String startValueQid,
            String populationPropertyPid,
            String nextWavePropertyPid,
            int maxDepth,
            int batchSize,
            int maxValues,
            List<String> targetRootQids) {
        this(startValueQid, populationPropertyPid, nextWavePropertyPid,
                maxDepth, batchSize, maxValues,
                DEFAULT_MAX_MEMBERSHIPS_PER_UNIT,
                DEFAULT_MAX_CONNECTIONS_PER_UNIT,
                targetRootQids);
    }

    public static SharedPopulationClosureConfig apostolicKingsOfHungary() {
        return new SharedPopulationClosureConfig(
                "Q6412254", // Apostolic King of Hungary
                "P39",      // position held: member -> starting position
                "P39",      // position held: member -> other position
                2,
                8,
                100,
                DEFAULT_MAX_MEMBERSHIPS_PER_UNIT,
                DEFAULT_MAX_CONNECTIONS_PER_UNIT,
                DEFAULT_POSITION_ROOTS);
    }

    private static void requireQid(String value, String name) {
        if (!WikidataIds.isQid(value)) {
            throw new IllegalArgumentException(name + " must be a QID: " + value);
        }
    }

    private static void requirePid(String value, String name) {
        if (!WikidataIds.isPid(value)) {
            throw new IllegalArgumentException(name + " must be a PID: " + value);
        }
    }
}
