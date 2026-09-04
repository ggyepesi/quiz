package wikidata.explore.compiled;

import canonical.CanonicalizationPlan;
import canonical.KeyComponent;
import canonical.MissingKeyPolicy;
import canonical.Reduction;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an authored class into the one plan every consumer reads.
 *
 * <p>This is where the defaults are applied, and the ONLY place: generation, sampling,
 * remap and enrich read the plan rather than each deciding what an unconfigured field
 * means. A default resolved in four places is four rules that agree until they do not.
 *
 * <p>It also translates the identity regimes that {@code ClassKind} currently decides by
 * itself. A Source class keys on its source identity and an Owned part on owner + site,
 * exactly as {@code Canonicalizer} already branches — but as a NAMED component the
 * modeller will be able to change, which is what makes an entity class with a content key
 * expressible at all.
 */
public final class CanonicalizationPlans {
    private CanonicalizationPlans() { }

    public static CanonicalizationPlan of(GeneratedClassModel clazz) {
        if (clazz == null) return CanonicalizationPlan.unidentified("");

        List<KeyComponent> key = keyOf(clazz);
        Map<String, Reduction> reductions = new LinkedHashMap<>();
        List<String> keyFieldPaths = key.stream()
                .filter(component -> component.kind() == KeyComponent.Kind.FIELD)
                .map(KeyComponent::fieldPath).toList();

        for (GeneratedFieldModel field : clazz.fields()) {
            if (field == null || field.name() == null || field.name().isBlank()) continue;
            // A key component is not reduced: its value formed the partition, so every
            // candidate in that partition already agrees on it.
            if (keyFieldPaths.contains(field.name())) continue;
            Reduction chosen = clazz.canonical().reductions().get(field.name());
            reductions.put(field.name(), chosen != null ? chosen
                    : Reduction.defaultFor(field.cardinality() == FieldCardinality.COLLECTION));
        }

        return new CanonicalizationPlan(clazz.className(), key,
                clazz.canonical().missingKeyPolicy(), reductions);
    }

    /**
     * The key as named components.
     *
     * <p>A structural regime is read from the class kind for now, because that is where
     * it lives — the point of naming it here is that a later editor can offer it as a
     * choice without any consumer learning a second way to ask.
     */
    private static List<KeyComponent> keyOf(GeneratedClassModel clazz) {
        return keyOf(clazz.classKind(), clazz.canonical());
    }

    /**
     * What identifies an instance — asked of the model, never worked out again here.
     *
     * <p>{@code Canonicalizer} builds an identifier from the same two facts and had this
     * reasoning too. It owns it now, because a model owns its authored key and the
     * compiler reads it; deriving it on both sides is the second discovery path this
     * refactor exists to remove.
     */
    public static List<KeyComponent> keyOf(
            ClassKind kind, wikidata.explore.model.CanonicalSpec spec) {
        return wikidata.explore.model.Canonicalizer.keyComponents(kind, spec);
    }

    /** What the modeller has not chosen, so an editor can show the difference. */
    public static Map<String, Reduction> defaultedFields(GeneratedClassModel clazz) {
        Map<String, Reduction> defaulted = new LinkedHashMap<>();
        if (clazz == null) return defaulted;
        for (var entry : of(clazz).reductionByField().entrySet()) {
            if (!clazz.canonical().reductions().containsKey(entry.getKey())) {
                defaulted.put(entry.getKey(), entry.getValue());
            }
        }
        return defaulted;
    }

    /** Named for the error a validator reports; a class with no key is not generatable. */
    public static boolean identified(GeneratedClassModel clazz) {
        return of(clazz).identified();
    }

    static MissingKeyPolicy missingKeyPolicy(GeneratedClassModel clazz) {
        return clazz == null ? MissingKeyPolicy.defaultPolicy()
                : clazz.canonical().missingKeyPolicy();
    }
}
