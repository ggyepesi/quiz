package wikidata.explore.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Which observed evidence values a kind rule set actually claims.
 *
 * <p>A descriptive vocabulary is the observed set of values the current pool carries.
 * Reading
 * the two together answers the question the rules are really making: what will still have
 * no kind after this runs. Otherwise that number only appears as "M remain of unknown kind"
 * once a regeneration has already been spent, and a new value appearing in the data slips
 * into the unknown bucket without anyone being told.</p>
 *
 * <p>A value may be claimed by several kinds; classification assigns every rule that
 * matches, so this reports every claim rather than picking one.</p>
 */
public final class EntityKindCoverage {

    private EntityKindCoverage() { }

    /** One vocabulary value and the kinds claiming it (empty = nothing claims it). */
    public record Member(String qid, String label, List<String> kinds) {
        public Member {
            label = label == null || label.isBlank() ? qid : label;
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
        }
        public boolean mapped() {
            return !kinds.isEmpty();
        }
    }

    /** Every populated vocabulary produced by a field mapped from {@code evidencePid}. */
    public static List<String> vocabularies(
            GeneratedProjectModel model, String evidencePid) {
        List<String> names = new ArrayList<>();
        if (model == null || !wikidata.WikidataIds.isPid(evidencePid)) {
            return names;
        }
        for (Selection selection : model.selections()) {
            if (selection instanceof VocabularySelection vocabulary
                    && !vocabulary.valueQids().isEmpty()
                    && producedBy(model, vocabulary.name(), evidencePid)) {
                names.add(vocabulary.name());
            }
        }
        return names;
    }

    /**
     * The vocabulary's values with their claims, in vocabulary order.
     *
     * @param rules  the rules being edited, which may differ from the model's saved set
     * @param labels qid → display label; a missing label falls back to the qid
     */
    public static List<Member> members(
            GeneratedProjectModel model, String vocabulary,
            String evidencePid, List<EntityKindRule> rules,
            Function<String, String> labels) {
        List<Member> out = new ArrayList<>();
        if (model == null || vocabulary == null) {
            return out;
        }
        Selection selection = model.findSelection(vocabulary);
        if (!(selection instanceof VocabularySelection values)) {
            return out;
        }
        for (String qid : values.valueQids()) {
            Set<String> kinds = new LinkedHashSet<>();
            for (EntityKindRule rule : rules == null ? List.<EntityKindRule>of() : rules) {
                if (rule != null && evidencePid.equals(rule.propertyPid())
                        && rule.evidenceQids().contains(qid)
                        && !rule.className().isBlank()) {
                    kinds.add(rule.className());
                }
            }
            out.add(new Member(qid, labels == null ? null : labels.apply(qid),
                    new ArrayList<>(kinds)));
        }
        return out;
    }

    private static boolean producedBy(
            GeneratedProjectModel model, String vocabulary, String evidencePid) {
        for (GeneratedClassModel owner : model.classes()) {
            if (owner == null) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field != null && vocabulary.equals(field.entityClassName())
                        && evidencePid.equals(field.mapping().propertyPid())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static long unmapped(List<Member> members) {
        return members == null ? 0
                : members.stream().filter(member -> !member.mapped()).count();
    }
}
