package canonical;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How one class turns candidates into instances, resolved once.
 *
 * <p>Compilation validates the authored declaration and produces this; generation,
 * sampling, remap and enrich read it. None of them re-reads the editable model or
 * chooses a default of its own — that is the whole point of there being a plan, and it
 * is the same split the rest of the pipeline already uses.
 *
 * <p>Provider-neutral by construction: a plan mentions field paths, named structural
 * components and reducers, and nothing about where candidates came from. A datasource
 * APPLIES the key this describes; it does not own it.
 */
public record CanonicalizationPlan(
        String className,
        List<KeyComponent> key,
        MissingKeyPolicy missingKeyPolicy,
        Map<String, Reduction> reductionByField) {

    public CanonicalizationPlan {
        className = className == null ? "" : className.trim();
        key = List.copyOf(key == null ? List.of() : key);
        missingKeyPolicy = missingKeyPolicy == null
                ? MissingKeyPolicy.defaultPolicy() : missingKeyPolicy;
        reductionByField = Map.copyOf(reductionByField == null ? Map.of() : reductionByField);

        // A key component appears once. Twice is not a tighter key — it is the same
        // partition computed twice, and it makes the key's own order ambiguous.
        if (key.size() != key.stream().distinct().count()) {
            throw new IllegalArgumentException(
                    "A key component appears twice in " + className + ": " + key);
        }
        // A key component is not reduced: its value made the partition, so every
        // candidate in that partition already agrees on it. A reducer for one would be
        // a rule that can never fire, which is worse than none — it reads as a decision.
        for (KeyComponent component : key) {
            if (component.kind() == KeyComponent.Kind.FIELD
                    && reductionByField.containsKey(component.fieldPath())) {
                throw new IllegalArgumentException(
                        "Key component '" + component.fieldPath() + "' of " + className
                                + " cannot also be reduced: its value formed the partition");
            }
        }
    }

    /** Whether the modeller has said what identifies an instance of this class. */
    public boolean identified() {
        return !key.isEmpty();
    }

    /**
     * Whether every instance stands for exactly one thing the datasource produced.
     *
     * <p>True only for a key that is source occurrence alone: nothing is ever combined,
     * so no reducer can run. Worth asking because it is the case a reader most often
     * means by "no deduplication", and because it used to be spelled as an empty key.
     */
    public boolean onePerOccurrence() {
        return key.size() == 1
                && key.get(0).kind() == KeyComponent.Kind.SOURCE_OCCURRENCE;
    }

    public Reduction reductionFor(String fieldPath) {
        return reductionByField.get(fieldPath);
    }

    /** A plan for a class whose key nobody has chosen — never generatable. */
    public static CanonicalizationPlan unidentified(String className) {
        return new CanonicalizationPlan(className, List.of(),
                MissingKeyPolicy.defaultPolicy(), new LinkedHashMap<>());
    }
}
