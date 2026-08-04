package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.model.Selection;
import wikidata.explore.model.VocabularySelection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a DESCRIPTIVE vocabulary from the values actually present on the SERVED pool
 * — the distinct type tags a referenced class carries, derived from what survives, not
 * from a transient superset that drifts. A descriptive vocabulary is the target of a
 * REFERENCED class's entity property-field (e.g. {@code Nominee.type} → NomineeType,
 * {@code ForWork.genre} → WorkGenre); its members ARE the distinct field values.
 *
 * <p>Run AFTER the served pool is finalized (post reify / stamp / referent-load AND
 * post prune / field-expectations), so the vocab matches exactly the members you can
 * search: a type whose only bearer was pruned (a dropped nomination, an orphan, a
 * country nominee filtered out) must NOT linger in the vocabulary. This is the
 * value-filter-is-the-membership-set lesson applied to descriptive vocabularies.
 *
 * <p>Scope is deliberately the referenced-class field targets only, so an AUTHORED
 * constraint vocabulary (e.g. OscarCategories, the target of the reified
 * {@code Nomination.category}) is never re-derived from — and shrunk to — the served
 * subset.
 */
public final class DescriptiveVocabularyBuild {

    private DescriptiveVocabularyBuild() {}

    /** The vocabulary names that are DESCRIPTIVE — the target of a REFERENCED class's
     *  entity field (NomineeType, WorkGenre) — as opposed to an AUTHORED constraint
     *  vocabulary. These are derived from the served pool, never persisted as authored
     *  values: callers strip them on save and re-derive them on load. */
    public static Set<String> targets(GeneratedProjectModel model) {
        Set<String> names = new LinkedHashSet<>();
        if (model == null) {
            return names;
        }
        for (GeneratedClassModel c : model.classes()) {
            if (c == null
                    || MembershipPattern.of(c, model) != MembershipPattern.REFERENCED) {
                continue;
            }
            for (GeneratedFieldModel f : c.fields()) {
                if (f == null || f.type() != FieldType.ENTITY) {
                    continue;
                }
                String target = f.entityClassName();
                if (target != null && !target.isBlank()
                        && model.findClass(target) == null) {
                    names.add(target);
                }
            }
        }
        return names;
    }

    /** @return the number of vocabularies built/refreshed. */
    public static int apply(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> servedPool,
            GenerationLog log) {

        if (model == null || servedPool == null) {
            return 0;
        }
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;

        // className -> [ (fieldName, vocabName) ] for ENTITY fields of a REFERENCED
        // class whose declared target names a vocabulary (i.e. NOT a modeled class).
        Map<String, List<String[]>> feeds = new LinkedHashMap<>();
        for (GeneratedClassModel c : model.classes()) {
            if (c == null
                    || MembershipPattern.of(c, model) != MembershipPattern.REFERENCED) {
                continue;
            }
            for (GeneratedFieldModel f : c.fields()) {
                if (f == null || f.type() != FieldType.ENTITY) {
                    continue;
                }
                String target = f.entityClassName();
                if (target == null || target.isBlank()
                        || model.findClass(target) != null) {
                    continue;   // target is a real class, not a vocabulary
                }
                feeds.computeIfAbsent(c.className(), k -> new ArrayList<>())
                        .add(new String[] {f.name(), target});
            }
        }
        if (feeds.isEmpty()) {
            return 0;
        }

        // Distinct value QIDs per vocabulary, over the SERVED instances only.
        Map<String, LinkedHashSet<String>> valuesByVocab = new LinkedHashMap<>();
        for (String vocabName : distinctTargets(feeds)) {
            valuesByVocab.put(vocabName, new LinkedHashSet<>());
        }
        for (WikidataDynamicObject o : servedPool) {
            if (o == null) {
                continue;
            }
            List<String[]> fields = feeds.get(o.typeName());
            if (fields == null) {
                continue;
            }
            for (String[] fieldToVocab : fields) {
                collectQids(o.get(fieldToVocab[0]), valuesByVocab.get(fieldToVocab[1]));
            }
        }

        int built = 0;
        for (Map.Entry<String, LinkedHashSet<String>> e : valuesByVocab.entrySet()) {
            String vocabName = e.getKey();
            Selection existing = model.findSelection(vocabName);
            VocabularySelection vocab;
            if (existing instanceof VocabularySelection v) {
                vocab = v;
            } else if (existing == null) {
                vocab = new VocabularySelection(vocabName);
                model.addSelection(vocab);
            } else {
                continue;   // a non-vocabulary selection already owns the name
            }
            vocab.valueQids(new ArrayList<>(e.getValue()));
            sink.message("Built vocabulary '" + vocabName + "' from the served pool -> "
                    + e.getValue().size() + " distinct value(s)\n");
            built++;
        }
        return built;
    }

    private static LinkedHashSet<String> distinctTargets(Map<String, List<String[]>> feeds) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (List<String[]> fields : feeds.values()) {
            for (String[] fieldToVocab : fields) {
                names.add(fieldToVocab[1]);
            }
        }
        return names;
    }

    private static void collectQids(Object value, LinkedHashSet<String> out) {
        if (value instanceof WikidataDynamicObject w) {
            if (w.qid() != null && WikidataIds.isQid(w.qid())) {
                out.add(w.qid());
            }
        } else if (value instanceof Collection<?> c) {
            for (Object item : c) {
                collectQids(item, out);
            }
        }
    }
}
