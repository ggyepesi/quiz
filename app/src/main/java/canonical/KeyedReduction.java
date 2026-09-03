package canonical;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Candidates in, instances out: partition by the configured key, reduce every other
 * field by its configured rule.
 *
 * <p>One engine, for every construct and every datasource. There were four — statement
 * dedup preferring the work-anchored copy, aggregate grouping, owned composition and the
 * class-kind branch in Canonicalizer — which is why "union the laureates while requiring
 * the category to agree" could not be said at all: each path had its own fixed idea of
 * what combining means.
 *
 * <p>What it does NOT do is decide anything. The plan says what the key is and what each
 * field's rule is; this applies them and reports what happened. Nothing here prefers a
 * survivor, and nothing infers a rule for a field the plan did not mention.
 */
public final class KeyedReduction {
    private KeyedReduction() { }

    /** One instance, and what it was made from. */
    public record Instance(String className, String key,
                           Map<String, Object> values,
                           int candidateCount) { }

    /** Two candidates in one partition disagreed on a field that admits one value. */
    public record Conflict(String className, String key, String fieldPath,
                           Object kept, Object rejected) { }

    /** A candidate that could not be keyed, and what was done about it. */
    public record Unkeyed(String className, KeyComponent missing,
                          MissingKeyPolicy applied) { }

    public record Result(List<Instance> instances,
                         List<Conflict> conflicts,
                         List<Unkeyed> unkeyed,
                         int candidateCount) {

        /** How many partitions combined more than one candidate — the grain's effect,
         *  which the finished instances cannot show. */
        public long reducedPartitions() {
            return instances.stream().filter(i -> i.candidateCount() > 1).count();
        }

        /**
         * What the configured grain did, in the words a modeller can act on.
         *
         * <p>Reported before the instances are materialized and stated as counts, never
         * as errors: collapsing can be exactly what was meant — a shared prize IS one
         * award — while the same line on a class that meant one record per statement is
         * the prompt to add a component to the key. Neither is knowable from the
         * finished snapshot, which is why it has to be said here.
         */
        public String report() {
            if (instances.isEmpty() && candidateCount == 0) return "";
            StringBuilder text = new StringBuilder();
            String className = instances.isEmpty() ? "" : instances.get(0).className();
            text.append(className).append(": ").append(candidateCount)
                    .append(" candidate(s) became ").append(instances.size())
                    .append(" instance(s)");
            if (reducedPartitions() > 0) {
                text.append("; ").append(reducedPartitions())
                        .append(" combined more than one");
            }
            text.append(".\n");
            if (!unkeyed.isEmpty()) {
                text.append("    ").append(unkeyed.size())
                        .append(" could not be keyed (")
                        .append(unkeyed.get(0).applied()).append("): missing ")
                        .append(unkeyed.get(0).missing()).append("\n");
            }
            int shown = 0;
            for (Conflict conflict : conflicts) {
                if (shown++ == 0) {
                    text.append("    ").append(conflicts.size())
                            .append(" conflict(s) — candidates disagreed on a field that "
                                    + "admits one value:\n");
                }
                if (shown > 5) {
                    text.append("    +").append(conflicts.size() - 5).append(" more\n");
                    break;
                }
                text.append("        ").append(conflict.fieldPath()).append(": ")
                        .append(conflict.kept()).append(" vs ")
                        .append(conflict.rejected()).append("\n");
            }
            return text.toString();
        }
    }

    public static Result reduce(CanonicalizationPlan plan,
                                Collection<? extends Candidate> candidates,
                                StableForm stable) {
        if (plan == null || !plan.identified()) {
            throw new IllegalArgumentException(
                    "Cannot reduce without a key: nothing chooses one for a class");
        }
        List<Conflict> conflicts = new ArrayList<>();
        List<Unkeyed> unkeyed = new ArrayList<>();
        Map<String, List<Candidate>> partitions = new LinkedHashMap<>();
        int seen = 0;

        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            if (candidate == null) continue;
            seen++;
            KeyComponent missing = firstMissing(plan, candidate, stable);
            if (missing != null) {
                MissingKeyPolicy policy = plan.missingKeyPolicy();
                if (policy == MissingKeyPolicy.FAIL) {
                    throw new IllegalStateException("A " + plan.className()
                            + " candidate has no " + missing + ", and this class says "
                            + "that must not happen");
                }
                unkeyed.add(new Unkeyed(plan.className(), missing, policy));
                if (policy == MissingKeyPolicy.REJECT_CANDIDATE) continue;
            }
            partitions.computeIfAbsent(keyOf(plan, candidate, stable),
                    ignored -> new ArrayList<>()).add(candidate);
        }

        List<Instance> instances = new ArrayList<>();
        for (var partition : partitions.entrySet()) {
            instances.add(materialize(
                    plan, partition.getKey(), partition.getValue(), stable, conflicts));
        }
        return new Result(instances, conflicts, unkeyed, seen);
    }

    /** The first key component this candidate cannot supply, or null. */
    private static KeyComponent firstMissing(
            CanonicalizationPlan plan, Candidate candidate, StableForm stable) {
        for (KeyComponent component : plan.key()) {
            String value = component.structural()
                    ? candidate.structuralIdentity(component.kind())
                    : stable.of(candidate.value(component.fieldPath()));
            if (value == null || value.isBlank()) return component;
        }
        return null;
    }

    private static String keyOf(
            CanonicalizationPlan plan, Candidate candidate, StableForm stable) {
        StringBuilder key = new StringBuilder();
        for (KeyComponent component : plan.key()) {
            key.append('|').append(component.structural()
                    ? candidate.structuralIdentity(component.kind())
                    : stable.of(candidate.value(component.fieldPath())));
        }
        return key.toString();
    }

    private static Instance materialize(
            CanonicalizationPlan plan, String key, List<Candidate> partition,
            StableForm stable, List<Conflict> conflicts) {

        Map<String, Object> values = new LinkedHashMap<>();

        // A key component's value is the same across the partition by construction —
        // it is what formed it — so it is taken, never reduced.
        for (KeyComponent component : plan.key()) {
            if (component.kind() != KeyComponent.Kind.FIELD) continue;
            values.put(component.fieldPath(),
                    partition.get(0).value(component.fieldPath()));
        }

        for (var entry : plan.reductionByField().entrySet()) {
            String field = entry.getKey();
            List<Object> present = new ArrayList<>();
            for (Candidate candidate : partition) {
                Object value = candidate.value(field);
                if (value != null && !stable.of(value).isBlank()) present.add(value);
            }
            if (present.isEmpty()) continue;

            switch (entry.getValue()) {
                case UNION_DISTINCT -> values.put(field, union(present, stable));
                case REQUIRE_AGREEMENT, PREFER_NON_EMPTY -> {
                    Object kept = present.get(0);
                    String keptForm = stable.of(kept);
                    for (Object other : present.subList(1, present.size())) {
                        if (!keptForm.equals(stable.of(other))) {
                            conflicts.add(new Conflict(
                                    plan.className(), key, field, kept, other));
                        }
                    }
                    values.put(field, kept);
                }
                // An explicit ordering or evidence policy is a later construct. Until
                // one exists this cannot be reached: nothing offers it as a choice, and
                // refusing is better than quietly behaving like one of the others.
                case CHOOSE_BY_POLICY -> throw new IllegalStateException(
                        "No selection policy is configured for "
                                + plan.className() + "." + field);
            }
        }
        return new Instance(plan.className(), key, values, partition.size());
    }

    /**
     * Every distinct value, in stable-form order.
     *
     * <p>Sorted, not in encounter order. A snapshot is meant to be reproducible from its
     * model, and encounter order is the order rows arrived — which R18 records is not
     * reproducible, since WDQS can answer a partial result as a silent 200. Ordering by
     * the values' own stable form makes the result depend on the values and nothing else.
     */
    private static List<Object> union(List<Object> values, StableForm stable) {
        Map<String, Object> byForm = new TreeMap<>();
        for (Object value : values) {
            if (value instanceof Collection<?> many) {
                for (Object item : many) {
                    if (item != null) byForm.putIfAbsent(stable.of(item), item);
                }
            } else {
                byForm.putIfAbsent(stable.of(value), value);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(byForm.values()));
    }
}
