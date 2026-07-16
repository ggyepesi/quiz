package objectview;

import quiz.Quizable;
import objectview.viewconfig.QuizablePanelConfig;

import java.lang.reflect.Field;
import java.util.*;

public final class QuizableRenderEstimator {

    public enum Mode {
        /**
         * Safe/current policy:
         * first encounter renders a panel,
         * later encounters render references.
         */
        SHARED_VISITED,

        /**
         * Dangerous/full unfolding policy:
         * only current ancestors stop cycles.
         * Same object may be rendered again through another path.
         */
        PATH_UNFOLDING
    }

    public record Estimate(
            Mode mode,
            int panels,
            int references,
            int leafValues,
            int collections,
            int maps,
            int maxDepth,
            Map<String, Integer> reachabilityCount
    ) {}

    private QuizableRenderEstimator() {}

    public static Estimate estimate(
            Quizable root,
            QuizablePanelConfig config,
            Mode mode
                                   ) {
        Counter c = new Counter();

        Set<Object> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());

        Set<Object> ancestors =
                Collections.newSetFromMap(new IdentityHashMap<>());

        visitValue(root, config, visited, ancestors, 0, mode, c);

        return new Estimate(
                mode,
                c.panels,
                c.references,
                c.leafValues,
                c.collections,
                c.maps,
                c.maxDepth,
                new LinkedHashMap<>(c.reachabilityCount));
    }

    private static void visitValue(
            Object value,
            QuizablePanelConfig config,
            Set<Object> visited,
            Set<Object> ancestors,
            int depth,
            Mode mode,
            Counter c
                                  ) {
        if (value == null) return;

        c.maxDepth = Math.max(c.maxDepth, depth);

        if (value instanceof Quizable q) {
            countReach(q, c);
            visitQuizable(q, config, visited, ancestors, depth, mode, c);
            return;
        }

        if (value instanceof Collection<?> collection) {
            c.collections++;

            for (Object item : collection) {
                visitValue(item, config, visited, ancestors,
                           depth + 1, mode, c);
            }

            return;
        }

        if (value instanceof Map<?, ?> map) {
            c.maps++;

            for (Object item : map.values()) {
                visitValue(item, config, visited, ancestors,
                           depth + 1, mode, c);
            }

            return;
        }

        c.leafValues++;
    }

    private static void visitQuizable(
            Quizable q,
            QuizablePanelConfig config,
            Set<Object> visited,
            Set<Object> ancestors,
            int depth,
            Mode mode,
            Counter c
                                     ) {
        if (q == null) return;

        if (ancestors.contains(q)) {
            c.references++;
            return;
        }

        if (mode == Mode.SHARED_VISITED && visited.contains(q)) {
            c.references++;
            return;
        }

        visited.add(q);
        ancestors.add(q);
        c.panels++;

        QuizablePanelConfig cfg =
                config == null
                        ? QuizablePanelConfig.all(q.getClass())
                        : config;

        for (Field field : cfg.visibleFieldsFor(q.getClass())) {
            if ("name".equals(field.getName())) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(q);
                visitValue(value, cfg, visited, ancestors,
                           depth + 1, mode, c);
            } catch (Exception ignored) {
            }
        }

        ancestors.remove(q);
    }

    private static void countReach(Quizable q, Counter c) {
        String key = q.getClass().getSimpleName() + "." + q.getIdentifier();
        c.reachabilityCount.merge(key, 1, Integer::sum);

        c.visits++;

        if (c.visits % c.reportEvery == 0) {
            long ms = (System.nanoTime() - c.startedNanos) / 1_000_000;

            System.out.println(
                    "Estimator progress: visits=" + c.visits
                            + " panels=" + c.panels
                            + " references=" + c.references
                            + " collections=" + c.collections
                            + " maxDepth=" + c.maxDepth
                            + " timeMs=" + ms
                            + " current=" + key);
        }
    }

    public static void print(Estimate e) {
        System.out.println("Render estimate: " + e.mode());
        System.out.println("  panels      = " + e.panels());
        System.out.println("  references  = " + e.references());
        System.out.println("  leafValues  = " + e.leafValues());
        System.out.println("  collections = " + e.collections());
        System.out.println("  maps        = " + e.maps());
        System.out.println("  maxDepth    = " + e.maxDepth());

        System.out.println("  most reached:");

        e.reachabilityCount().entrySet().stream()
         .sorted(Map.Entry.<String, Integer>comparingByValue()
                          .reversed())
         .limit(20)
         .forEach(en ->
                          System.out.println("    "
                                                     + en.getKey()
                                                     + " -> "
                                                     + en.getValue()));
    }

    private static final class Counter {
        int panels;
        int references;
        int leafValues;
        int collections;
        int maps;
        int maxDepth;
        long visits = 0;
        long reportEvery = 1000;
        long startedNanos = System.nanoTime();

        final Map<String, Integer> reachabilityCount =
                new LinkedHashMap<>();
    }
}