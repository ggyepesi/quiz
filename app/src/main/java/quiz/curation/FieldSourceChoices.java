package quiz.curation;

import domain.DomainModel;
import wikidata.explore.model.FieldSourceMapping;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where each field of the open dataset is read from, and where that answer came from.
 *
 * <p>A field has two independent source slots — the primary Wikidata property, and an
 * additional source read after extraction — and each is answered by looking in several places
 * in a particular order. Both orders had been wrong once. The PRIMARY prefers the model's
 * declaration and falls back to provenance already recorded on curated values, because asking
 * a reader to re-enter {@code locations -> P840} when {@code movies.model.json} already says
 * it is asking for known configuration. The ADDITIONAL prefers the DATASET's own choice and
 * hears the model only where this dataset has said nothing, because an override that the model
 * can veto is not an override.
 *
 * <p>None of this is about Swing, and all of it lived in a 2,000-line panel, so the two
 * precedence bugs were both found by reading rather than by a test.
 */
public final class FieldSourceChoices {

    private record Key(String type, String field) { }

    private final DomainModel domain;
    private final Map<Key, FieldSourceMapping> primary = new HashMap<>();
    private final Map<Key, FieldSourceMapping> chosenAdditional = new HashMap<>();

    public FieldSourceChoices(DomainModel domain) {
        this.domain = Objects.requireNonNull(domain, "Sources are chosen for some domain");
    }

    // ---- the primary (Wikidata) source ---------------------------------------------

    /** Seeded on first ask: the model's declaration, else provenance already recorded. */
    public FieldSourceMapping primary(String type, String field) {
        if (type == null || field == null) return null;
        Key key = new Key(type, field);
        if (!primary.containsKey(key)) {
            FieldSourceMapping seeded = declaredPrimary(type, field);
            if (seeded == null) {
                ManualCuration curation = curation();
                seeded = curation == null ? null
                        : reusableSource(curation.corrections(), type, field);
            }
            if (seeded != null) primary.put(key, seeded);
        }
        return primary.get(key);
    }

    /** What the reader picked, which outranks anything that would have been seeded. */
    public void choosePrimary(String type, String field, FieldSourceMapping source) {
        if (type == null || field == null || source == null) return;
        primary.put(new Key(type, field), source);
    }

    private FieldSourceMapping declaredPrimary(String type, String field) {
        FieldRulePromoter modelBacked = domain.capability(FieldRulePromoter.class);
        if (modelBacked == null) return null;
        FieldSourceMapping declared = modelBacked.declaredSource(type, field);
        return declared == null || declared.propertyPid() == null
                || declared.propertyPid().isBlank() ? null : declared;
    }

    // ---- the additional source -------------------------------------------------------

    /** This dataset's own choice, in memory or recorded in its sidecar. */
    public FieldSourceMapping datasetOverride(String type, String field) {
        if (type == null || field == null) return null;
        Key key = new Key(type, field);
        FieldSourceMapping chosen = chosenAdditional.get(key);
        if (chosen != null) return chosen;
        ManualCuration curation = curation();
        FieldSourceRecipe recipe = curation == null ? null
                : curation.sourceRecipe(type, field, FieldSourceRecipe.ADDITIONAL_SOURCE);
        FieldSourceMapping recorded = FieldSourceRecipeCodec.mapping(recipe);
        if (recorded != null) chosenAdditional.put(key, recorded);
        return recorded;
    }

    public FieldSourceMapping additional(String type, String field) {
        return additionalSource(datasetOverride(type, field), domain, type, field);
    }

    /**
     * A dataset's own choice SHADOWS the model's declaration — the same order the category
     * recipe takes, and the reason the choice is clearable. The model is heard only where this
     * dataset has said nothing, and its answer is never remembered as a dataset choice: that
     * is what makes clearing mean something.
     */
    public static FieldSourceMapping additionalSource(FieldSourceMapping datasetOverride,
            DomainModel domain, String type, String field) {
        if (datasetOverride != null) return datasetOverride;
        FieldRulePromoter modelBacked = domain == null
                ? null : domain.capability(FieldRulePromoter.class);
        return modelBacked == null ? null : modelBacked.declaredFallbackSource(type, field);
    }

    /** Records the choice both in memory and in the sidecar, so it survives a reload. */
    public void recordAdditional(String type, String field, FieldSourceMapping source) {
        if (type == null || field == null || source == null
                || source.propertyPid() == null || source.propertyPid().isBlank()) {
            return;
        }
        chosenAdditional.put(new Key(type, field), source);
        ManualCuration curation = curation();
        if (curation != null) {
            curation.putSourceRecipe(FieldSourceRecipeCodec.scoped(type, field, source));
        }
    }

    /** Forgets this dataset's choice, so the model can be heard on the field again. */
    public void clearAdditional(String type, String field) {
        if (type == null || field == null) return;
        chosenAdditional.remove(new Key(type, field));
        ManualCuration curation = curation();
        if (curation != null) {
            curation.removeSourceRecipe(type, field, FieldSourceRecipe.ADDITIONAL_SOURCE);
        }
    }

    // ---- provenance already recorded --------------------------------------------------

    /**
     * The primary source to reuse for {@code type.field}, rebuilt from the provenance already
     * recorded on that field's filled values — the most-used property when several appear — or
     * null when nothing was ever sourced for it.
     */
    public static FieldSourceMapping reusableSource(
            List<Correction> corrections, String type, String field) {
        Map<String, ValueSource> byPid = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Correction correction : corrections == null ? List.<Correction>of() : corrections) {
            if (!type.equals(correction.type()) || !field.equals(correction.field())) continue;
            ValueSource source = correction.source();
            // Primary (Wikidata) provenance only; an additional source is a separate slot.
            if (source == null || source.propertyId() == null || source.propertyId().isBlank()
                    || !"Wikidata".equalsIgnoreCase(source.kind())) {
                continue;
            }
            byPid.putIfAbsent(source.propertyId(), source);
            counts.merge(source.propertyId(), 1, Integer::sum);
        }
        if (byPid.isEmpty()) return null;
        ValueSource source = byPid.get(counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElseThrow());
        FieldSourceMapping mapping = new FieldSourceMapping();
        mapping.sourceType(wikidata.explore.model.FieldSourceType.SPARQL);
        mapping.propertyPid(source.propertyId());
        mapping.propertyLabel(source.propertyLabel() == null ? "" : source.propertyLabel());
        mapping.direction(directionOf(source.direction()));
        mapping.productionKind(wikidata.explore.model.FieldProductionKind.AUTO);
        return mapping;
    }

    private static wikidata.explore.model.RuleDirection directionOf(String direction) {
        try {
            return direction == null ? wikidata.explore.model.RuleDirection.ROOT_TO_ITEM
                    : wikidata.explore.model.RuleDirection.valueOf(direction);
        } catch (IllegalArgumentException unknown) {
            return wikidata.explore.model.RuleDirection.ROOT_TO_ITEM;
        }
    }

    private ManualCuration curation() {
        Curatable curatable = domain.capability(Curatable.class);
        return curatable == null ? null : curatable.curation();
    }
}
