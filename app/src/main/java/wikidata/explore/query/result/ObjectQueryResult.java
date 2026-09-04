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