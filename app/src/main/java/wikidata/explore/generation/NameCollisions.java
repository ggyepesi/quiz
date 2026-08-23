package wikidata.explore.generation;

import wikidata.explore.model.ClassKind;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Instances of a declared class that share a display label, classified by what that
 * sharing means for the class's identity regime.
 *
 * <p>Different meaning different identifiers — a QID, or the assembled id a statement
 * class carries. That is the whole of it: information about the domain, with no further
 * automatic use. For source entities the label is the datasource's own, so a repetition
 * is genuine name ambiguity. For statement records and owned parts the label is DERIVED,
 * so a repetition describes the rule that derived it rather than the identity of what
 * carries it — a different question, reported separately rather than not at all.
 *
 * <p>It reports INSTANCES, so it asks what the configuration declares. A value reached
 * through a field is not an instance however it arrived: the given name behind
 * {@code Name.givenName} is a Wikidata item with a label, but in this domain it is the
 * value of a field, and two equal values are no more a collision than two languages with
 * five million speakers. Whether such values should become a class, a vocabulary, or stay
 * as plain fields is a decision for the transform workbench; reporting them here would
 * quietly argue for the first.
 *
 * <p>Results are partitioned BY CLASS, because the counts are of different orders and one
 * list buries the others. On the Oscars domain a Nomination's display label names its
 * nominee and nothing else, so 2499 groups of Nominations are mutually indistinguishable
 * — the largest of them 54 records reading "Jack Oakie". Collapsed into one list that
 * drowns the 124 works sharing a title; kept as its own row it is the more interesting
 * of the two, and it is really a fact about that class's display-name rule.
 */
public final class NameCollisions {

    /** What sharing a display label means for the class's identity regime. */
    public enum Meaning {
        /** Distinct datasource entities have the same label: genuine ambiguity. */
        ENTITY_AMBIGUITY,
        /** Statement records repeating a label their display-name rule takes from a
         *  participant. Arithmetic where that rule names ONE field — a nominee nominated
         *  54 times yields 54 identical labels — but the same bucket holds a multi-field
         *  rule that failed to distinguish, which is a modelling fault. Which rule ran is
         *  the difference, and this does not ask. */
        STATEMENT_REPETITION,
        /** Owner-derived parts repeat labels inherited from their owners. */
        OWNED_REPETITION
    }

    /** One label and the distinct instances carrying it, in the order the run produced
     *  them. Identified by identifier — a QID, or a statement class's assembled id. */
    public record Collision(String name, List<String> ids) {
        public Collision {
            name = name == null ? "" : name;
            ids = List.copyOf(ids);
        }

        public int size() {
            return ids.size();
        }
    }

    /** One class's collisions, biggest first. */
    public record ClassCollisions(
            String className, Meaning meaning, List<Collision> collisions) {
        public ClassCollisions {
            className = className == null ? "" : className;
            // Defaulting would file a derived-label repetition under genuine ambiguity,
            // which is the confusion this type exists to prevent.
            meaning = java.util.Objects.requireNonNull(meaning, "meaning");
            collisions = List.copyOf(collisions);
        }

        /** How many labels this class has that more than one of its instances carries. */
        public int size() {
            return collisions.size();
        }

        /** The most-shared label's instance count — how bad it gets in this class. */
        public int worst() {
            return collisions.stream().mapToInt(Collision::size).max().orElse(0);
        }
    }

    private NameCollisions() { }

    /** Per class, biggest collision first; classes with the most collisions first. */
    public static List<ClassCollisions> detect(
            Collection<WikidataDynamicObject> objects, GeneratedProjectModel model) {

        Map<String, Meaning> declared = new LinkedHashMap<>();
        if (model != null) {
            model.classes().forEach(clazz -> declared.put(
                    clazz.className(), meaning(clazz.classKind())));
            if (model.rootClass() != null) declared.put(
                    model.rootClass().className(), meaning(model.rootClass().classKind()));
        }
        return detect(objects, declared);
    }

    // Deliberately no overload taking bare class NAMES: what a shared label means comes
    // from the class's identity regime, so a caller holding only names cannot be told the
    // answer and would have to be given a default — and every default is wrong for two of
    // the three kinds.
    private static List<ClassCollisions> detect(
            Collection<WikidataDynamicObject> objects, Map<String, Meaning> declaredClasses) {

        Map<String, Meaning> declared = declaredClasses == null ? Map.of() : declaredClasses;
        Map<String, Map<String, LinkedHashSet<String>>> byClass = new LinkedHashMap<>();

        for (WikidataDynamicObject object : objects == null ? List.<WikidataDynamicObject>of()
                : objects) {
            if (object == null) continue;
            // Membership IS the type stamp — never typeName(), which falls back to the
            // carrier's Java class name and would report every value as an instance.
            if (!object.hasTypeStamp()) continue;
            String className = object.typeName();
            if (!declared.containsKey(className)) continue;
            String name = object.getDisplayName();
            String id = object.getIdentifier();
            if (name == null || name.isBlank() || id == null || id.isBlank()) continue;
            byClass.computeIfAbsent(className, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(name, ignored -> new LinkedHashSet<>())
                    .add(id);
        }

        List<ClassCollisions> out = new ArrayList<>();
        byClass.forEach((className, byName) -> {
            List<Collision> collisions = new ArrayList<>();
            byName.forEach((name, ids) -> {
                if (ids.size() > 1) collisions.add(new Collision(name, List.copyOf(ids)));
            });
            collisions.sort(Comparator.comparingInt(Collision::size).reversed());
            if (!collisions.isEmpty()) {
                out.add(new ClassCollisions(
                        className, declared.get(className), List.copyOf(collisions)));
            }
        });
        out.sort(Comparator.comparingInt(ClassCollisions::size).reversed());
        return List.copyOf(out);
    }

    /** Every collision across every class, biggest first — for a caller that wants one
     *  list rather than the partition. */
    public static List<Collision> flatten(List<ClassCollisions> byClass) {
        List<Collision> all = new ArrayList<>();
        for (ClassCollisions c : byClass == null ? List.<ClassCollisions>of() : byClass) {
            all.addAll(c.collisions());
        }
        all.sort(Comparator.comparingInt(Collision::size).reversed());
        return List.copyOf(all);
    }

    /** Classes carrying the requested meaning, preserving report order. */
    public static List<ClassCollisions> classes(
            List<ClassCollisions> byClass, Meaning meaning) {
        return byClass == null ? List.of() : byClass.stream()
                .filter(c -> c.meaning() == meaning).toList();
    }

    /** How many instances are involved altogether. */
    public static int instanceCount(List<ClassCollisions> byClass) {
        return byClass == null ? 0 : byClass.stream()
                .flatMap(c -> c.collisions().stream())
                .mapToInt(Collision::size).sum();
    }

    private static Meaning meaning(ClassKind kind) {
        return switch (kind) {
            case SOURCE -> Meaning.ENTITY_AMBIGUITY;
            case STATEMENT -> Meaning.STATEMENT_REPETITION;
            case OWNED -> Meaning.OWNED_REPETITION;
        };
    }
}
