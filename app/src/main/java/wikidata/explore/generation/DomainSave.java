package wikidata.explore.generation;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleTreeConfig;
import wikidata.explore.rule.RuleTreeSerializer;
import wikidata.explore.transform.DescriptiveVocabularyBuild;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What saving a domain would write, and what it would cost — the questions the save dialogs
 * ask before anything is written.
 *
 * <p>Each answer is a rule with a reason, and all of them lived inside a Swing method between
 * the confirmations they feed: which model actually goes to disk (not the one on screen), when
 * a snapshot no longer matches the model beside it, and which classes a narrow run would
 * silently erase from a wider one. None could be run.
 */
public final class DomainSave {

    private DomainSave() { }

    /**
     * The model as it is PERSISTED. A descriptive vocabulary (NomineeType, WorkGenre) is
     * derived from the data rather than authored, so it is written as an empty shell — still
     * declared, so a field targeting it still resolves and the tree still shows it, but with
     * no values, because values saved today are wrong tomorrow. Load re-derives them from the
     * snapshot, which is why stripping them cannot lose anything.
     *
     * <p>The argument is not modified: an editor is still showing it.
     */
    public static GeneratedProjectModel persistedModel(GeneratedProjectModel model) {
        if (model == null) return null;
        GeneratedProjectModel copy = model.copy();
        for (String name : DescriptiveVocabularyBuild.targets(copy)) {
            if (copy.findSelection(name) instanceof VocabularySelection vocabulary) {
                vocabulary.valueQids(new ArrayList<>());
            }
        }
        return copy;
    }

    /**
     * A fingerprint of what a model would generate — the compiled rule tree, hashed. Two
     * models sharing it produce the same instances, which is the only thing a saved snapshot
     * has to agree with.
     *
     * <p>Best effort: a model that cannot be compiled has no signature, and an empty
     * signature means no drift claim rather than a claim of drift.
     */
    public static String signature(GeneratedProjectModel model) {
        try {
            RuleNode root = RuleTreeCompiler.compileProject(model);
            String json = new RuleTreeSerializer().mapper()
                    .writeValueAsString(RuleTreeConfig.of(root));
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception uncompilable) {
            return "";
        }
    }

    /**
     * Whether instances generated under {@code runSignature} would be saved stale beside
     * {@code modelToSave} — the model was edited after the run, so the snapshot no longer
     * matches the model written next to it. Unknown signatures make no claim.
     */
    public static boolean instancesWouldBeStale(
            String runSignature, GeneratedProjectModel modelToSave) {
        if (runSignature == null || runSignature.isBlank()) return false;
        String saving = signature(modelToSave);
        return !saving.isEmpty() && !runSignature.equals(saving);
    }

    /**
     * The classes actually carried by a pool, in the order they first appear.
     *
     * <p>Asked with {@code hasTypeStamp()}, because {@code typeName()} falls back to the
     * carrier's Java class name when an object has no stamp — so the obvious null-and-blank
     * guard can never be false, and an unstamped object would be reported as a class called
     * "WikidataDynamicObject", which the overwrite dialog would then offer to drop.
     */
    public static Set<String> stampedTypes(Collection<WikidataDynamicObject> objects) {
        Set<String> types = new LinkedHashSet<>();
        if (objects == null) return types;
        for (WikidataDynamicObject object : objects) {
            if (object != null && object.hasTypeStamp()) {
                types.add(object.typeName());
            }
        }
        return types;
    }

    /**
     * Classes present in {@code onDisk} that {@code run} does not produce, and would
     * therefore be erased by overwriting the snapshot with it. A single-class run must not
     * silently replace a multi-class domain — that is what "Generate domain" is for.
     */
    public static List<String> typesDropped(
            Collection<WikidataDynamicObject> run, Collection<WikidataDynamicObject> onDisk) {
        Set<String> dropped = new LinkedHashSet<>(stampedTypes(onDisk));
        dropped.removeAll(stampedTypes(run));
        return List.copyOf(dropped);
    }
}
