package wikidata.explore.generation;

import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Distinct entities that a generated run gave the same display name.
 *
 * <p>They matter because a quiz answer is a name: two entities called "Mercury" make a
 * question with two right answers, so a run reports them and the reader disambiguates or
 * excludes a type. What counts as a collision is the whole content of this class, and it was
 * private to a Swing frame — so the one rule that keeps the report usable had never been run.
 *
 * <p>That rule: only REAL entities count. A reified statement atom is keyed
 * {@code Q123-UUID} or {@code Q123__Q456} and is named on purpose by a field it carries — a
 * Nomination shows its nominee — so a person with forty nominations would otherwise be
 * reported as a forty-way collision with themselves, every run, drowning the real ones.
 */
public final class NameCollisions {

    /** One name and the distinct entities holding it, in the order the run produced them. */
    public record Collision(String name, List<String> qids) {
        public Collision {
            name = name == null ? "" : name;
            qids = List.copyOf(qids);
        }

        public int size() {
            return qids.size();
        }
    }

    private NameCollisions() { }

    /** Biggest collision first, so a report truncated to N rows keeps the worst of them. */
    public static List<Collision> detect(Collection<WikidataDynamicObject> objects) {
        Map<String, LinkedHashSet<String>> byName = new LinkedHashMap<>();
        for (WikidataDynamicObject object : objects == null ? List.<WikidataDynamicObject>of()
                : objects) {
            if (object == null) continue;
            String name = object.getDisplayName();
            String qid = object.qid();
            if (name == null || name.isBlank() || qid == null || qid.isBlank()) continue;
            // A pure Q-id and nothing else: see the class note on reified statement atoms.
            if (!WikidataIds.isQid(qid)) continue;
            byName.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(qid);
        }
        List<Collision> collisions = new ArrayList<>();
        byName.forEach((name, qids) -> {
            if (qids.size() > 1) collisions.add(new Collision(name, List.copyOf(qids)));
        });
        collisions.sort(Comparator.comparingInt(Collision::size).reversed());
        return List.copyOf(collisions);
    }

    /** How many entities are involved altogether — the count a reader is told about. */
    public static int entityCount(List<Collision> collisions) {
        return collisions == null ? 0
                : collisions.stream().mapToInt(Collision::size).sum();
    }
}
