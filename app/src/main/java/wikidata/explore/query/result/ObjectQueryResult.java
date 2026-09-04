package wikidata.explore.query.result;

import objectview.Viewable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @param typeOrder how the result's types relate, when its producer knows — sections are
 *                  shown in this order, and anything not named follows in the order it
 *                  was discovered. A sample of a derived class puts its production chain
 *                  here: without it the sections come out in traversal order, which
 *                  reads as no order at all.
 */
public record ObjectQueryResult(
        List<Viewable> objects,
        Class<?> primaryClass,
        String generatedSource,
        List<String> typeOrder) {

    public ObjectQueryResult {
        typeOrder = typeOrder == null ? List.of() : List.copyOf(typeOrder);
    }

    /** A result whose types have no stated order. */
    public ObjectQueryResult(
            List<Viewable> objects, Class<?> primaryClass, String generatedSource) {
        this(objects, primaryClass, generatedSource, List.of());
    }

    public int size() {
        return objects == null ? 0 : objects.size();
    }

    /** How many of these are instances of {@code typeName}. */
    public int countOf(String typeName) {
        if (typeName == null || typeName.isBlank()) return size();
        return byType().getOrDefault(typeName, List.of()).size();
    }

    /**
     * One object that points at another, and the field it points through.
     *
     * @param owner the object holding the reference
     * @param field the field it is held in — "grouped by NobelPrize" says less than
     *              "NobelPrize.laureatesWithMotivation", and the reader is usually
     *              asking which of several edges brought them here
     */
    public record Referrer(Viewable owner, String field) { }

    /**
     * What points AT each object — the reference edges read backwards.
     *
     * <p>A card shows what it points to; nothing showed what points to it. For a class
     * with no population of its own that is the more useful direction: the prize lists
     * the records it grouped, but a record could not say which prize took it, and that
     * is the connection a modeller checking a key actually wants to follow.
     *
     * <p>Derived, never stored. A back-reference field would put it in the model and
     * then in everything served, which is production carrying something only a view
     * wants; the edges are already in the objects and this reads them.
     *
     * <p>Keyed by identity: two objects that compare equal are still two places in the
     * graph, and a card is shown for one of them.
     */
    public Map<Viewable, List<Referrer>> referrers() {
        return walk().referrers();
    }

    /**
     * Everything this result holds, grouped by type — the roots and what they reach.
     *
     * <p>ONE rule, because the viewer's section headings and the sample's own count are
     * the same question. They were two: the sections counted what a reference walk
     * reached, the count counted roots, and a sample of an aggregate then said eight
     * beside a section saying sixteen. Whether an object is a root or was reached
     * through a field is a property of how the producer assembled the list, not of how
     * many instances of a type the reader is looking at.
     *
     * <p>Insertion order is the walk's order; a caller with something better to say puts
     * it in {@link #typeOrder()}.
     */
    public Map<String, List<Viewable>> byType() {
        return walk().byType();
    }

    /** What one traversal of this result found. */
    private record Contents(Map<String, List<Viewable>> byType,
                            Map<Viewable, List<Referrer>> referrers) { }

    /**
     * The one traversal.
     *
     * <p>Both questions this result answers about its contents — which types are in it,
     * and what points at what — are read off the same edges, so they are read once. Two
     * walks would be two chances to disagree about what the result contains, which is
     * the shape of the defect that had the sections and the count reporting different
     * numbers for the same objects.
     */
    private Contents walk() {
        Map<String, List<Viewable>> byType = new LinkedHashMap<>();
        Map<Viewable, List<Referrer>> referrers = new IdentityHashMap<>();
        Set<Viewable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Viewable> queue = new ArrayDeque<>(objects == null ? List.of() : objects);
        while (!queue.isEmpty()) {
            Viewable value = queue.poll();
            if (value == null || !seen.add(value)) continue;
            // A dynamic carrier is the untyped shape an object has before it is
            // materialized; it is never something the reader counts or sees.
            if ("WikidataDynamicObject".equals(value.getClass().getSimpleName())) continue;
            String type = value.typeName();
            if (type != null && !type.isBlank() && !"WikidataDynamicObject".equals(type)) {
                byType.computeIfAbsent(type, key -> new ArrayList<>()).add(value);
            }
            objectview.field.FieldSet fields = objectview.field.FieldSet.of(value);
            for (objectview.field.FieldRef field : fields.fields()) {
                for (Viewable referenced : referencedBy(fields.read(field.name()))) {
                    // Recorded even when already seen: the edge is a fact about this
                    // pair, and a record grouped by one prize and mentioned by another
                    // must show both.
                    referrers.computeIfAbsent(referenced, key -> new ArrayList<>())
                            .add(new Referrer(value, field.name()));
                    queue.add(referenced);
                }
            }
        }
        return new Contents(byType, referrers);
    }

    /** Every Viewable a field's value holds, whatever container it is in. */
    private static List<Viewable> referencedBy(Object value) {
        List<Viewable> found = new ArrayList<>();
        collect(value, found);
        return found;
    }

    private static void collect(Object value, List<Viewable> found) {
        if (value instanceof Viewable viewable) {
            found.add(viewable);
        } else if (value instanceof Iterable<?> values) {
            for (Object each : values) collect(each, found);
        } else if (value instanceof Map<?, ?> values) {
            for (Object each : values.values()) collect(each, found);
        }
    }
}