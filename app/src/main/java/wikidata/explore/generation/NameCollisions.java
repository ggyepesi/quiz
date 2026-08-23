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
 * <p>Two rules keep it usable, and both exclude things that cannot BE an answer.
 *
 * <p>Only REAL entities count. A reified statement atom is keyed {@code Q123-UUID} or
 * {@code Q123__Q456} and is named on purpose by a field it carries — a Nomination shows
 * its nominee — so a person with forty nominations would otherwise be reported as a
 * forty-way collision with themselves, every run, drowning the real ones.
 *
 * <p>And only MEMBERS are served. Membership is the type stamp, so an entity carrying a
 * QID and no stamp is a referent: reachable through a field, never offered as an answer.
 * The Oscars pool holds thousands of them, because {@code Person.structuredName} pulls in
 * the P735/P734 name entities behind it — and Wikidata keeps a separate item per name per
 * language, so "Lee" the family name and "Lee" the given name are different QIDs sharing
 * a label and collide by construction, for ever. They were 297 of 556 reported collisions
 * on the Oscars domain: permanent noise nobody can act on, in a warning whose whole value
 * is that it is worth reading.
 *
 * <p>Referents are still counted, separately. One sharing a name with a served entity can
 * still be confusing where the referent is rendered — so the question is which of the two
 * numbers a reader is looking at, not whether to hide one.
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

    /** Collisions among the entities a run SERVES — the ones that can be an answer, and
     *  the number a reader should act on. */
    public static List<Collision> detect(Collection<WikidataDynamicObject> objects) {
        return detect(objects, true);
    }

    /** Collisions among the referents a run merely REFERENCES: never offered as an
     *  answer, but still rendered wherever a field shows one. */
    public static List<Collision> detectReferenced(
            Collection<WikidataDynamicObject> objects) {
        return detect(objects, false);
    }

    /** Biggest collision first, so a report truncated to N rows keeps the worst of them. */
    private static List<Collision> detect(
            Collection<WikidataDynamicObject> objects, boolean served) {
        Map<String, LinkedHashSet<String>> byName = new LinkedHashMap<>();
        for (WikidataDynamicObject object : objects == null ? List.<WikidataDynamicObject>of()
                : objects) {
            if (object == null) continue;
            String name = object.getDisplayName();
            String qid = object.qid();
            if (name == null || name.isBlank() || qid == null || qid.isBlank()) continue;
            // A pure Q-id and nothing else: see the class note on reified statement atoms.
            if (!WikidataIds.isQid(qid)) continue;
            // Membership IS the type stamp — never typeName(), which falls back to the
            // carrier's Java class name and would call every referent a member.
            if (object.hasTypeStamp() != served) continue;
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
