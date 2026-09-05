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
 * @param partTypes types whose instances are PARTS of another object — one made per
 *                  owning instance, carrying that owner's identifier. They are reached
 *                  through their owner and not listed beside the classes that have an
 *                  existence of their own. Named by the producer, which is what has the
 *                  model to ask; a producer that means to show a part (a sample OF one)
 *                  leaves it out of this list.
 */
public record ObjectQueryResult(
        List<Viewable> objects,
        Class<?> primaryClass,
        String generatedSource,
        List<String> typeOrder,
        List<String> partTypes) {

    public ObjectQueryResult {
        typeOrder = typeOrder == null ? List.of() : List.copyOf(typeOrder);
        partTypes = partTypes == null ? List.of() : List.copyOf(partTypes);
    }

    /** A result whose types have no stated order and no declared parts. */
    public ObjectQueryResult(
            List<Viewable> objects, Class<?> primaryClass, String generatedSource) {
        this(objects, primaryClass, generatedSource, List.of(), List.of());
    }

    public ObjectQueryResult(List<Viewable> objects, Class<?> primaryClass,
            String generatedSource, List<String> typeOrder) {
        this(objects, primaryClass, generatedSource, typeOrder, List.of());
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
     * The types worth a section of their own — everything but the parts.
     *
     * <p>The grouping stays the whole truth; this is the view of it a reader is offered.
     * A part is still reached, still counted, and still rendered inside the owner whose
     * field holds it — what it does not get is a heading beside the classes it belongs
     * to. Dropping it from the walk instead would lose whatever IT reaches.
     */
    public Map<String, List<Viewable>> byTypeWithoutParts() {
        Map<String, List<Viewable>> byType = byType();
        partTypes.forEach(byType::remove);
        return byType;
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
        Map<String, List<Viewable>> byType = new LinkedHashMap<>();
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
                enqueue(fields.read(field.name()), queue);
            }
        }
        return byType;
    }

    private static void enqueue(Object value, Deque<Viewable> queue) {
        if (value instanceof Viewable viewable) {
            queue.add(viewable);
        } else if (value instanceof Iterable<?> values) {
            for (Object each : values) enqueue(each, queue);
        } else if (value instanceof Map<?, ?> values) {
            for (Object each : values.values()) enqueue(each, queue);
        }
    }
}