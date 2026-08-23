package wikidata.explore.generation;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Instances of a declared class that share a display label.
 *
 * <p>Different meaning different identifiers — a QID, or the assembled id a statement
 * class carries. That is the whole of it: information about the domain, with no further
 * automatic use. It is not a warning and it recommends nothing, because what to do about
 * two things called the same is a modelling decision with several right answers.
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
    public record ClassCollisions(String className, List<Collision> collisions) {
        public ClassCollisions {
            className = className == null ? "" : className;
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

        Set<String> declared = new LinkedHashSet<>();
        if (model != null) {
            model.classes().forEach(clazz -> declared.add(clazz.className()));
            if (model.rootClass() != null) declared.add(model.rootClass().className());
        }
        return detect(objects, declared);
    }

    /** As above, told directly which class names the configuration declares. */
    public static List<ClassCollisions> detect(
            Collection<WikidataDynamicObject> objects, Set<String> declaredClasses) {

        Set<String> declared = declaredClasses == null ? Set.of() : declaredClasses;
        Map<String, Map<String, LinkedHashSet<String>>> byClass = new LinkedHashMap<>();

        for (WikidataDynamicObject object : objects == null ? List.<WikidataDynamicObject>of()
                : objects) {
            if (object == null) continue;
            // Membership IS the type stamp — never typeName(), which falls back to the
            // carrier's Java class name and would report every value as an instance.
            if (!object.hasTypeStamp()) continue;
            String className = object.typeName();
            if (!declared.contains(className)) continue;
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
                out.add(new ClassCollisions(className, List.copyOf(collisions)));
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

    /** How many instances are involved altogether. */
    public static int instanceCount(List<ClassCollisions> byClass) {
        return byClass == null ? 0 : byClass.stream()
                .flatMap(c -> c.collisions().stream())
                .mapToInt(Collision::size).sum();
    }
}
