package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether the values in a declared reference field point at anything this domain holds.
 *
 * <p>A field declaring an entity class answers a PRESENCE question well — coverage says
 * how many are filled — and says nothing about what they were filled with. A reference to
 * an entity the domain never loaded is indistinguishable from a good one until something
 * reads through it, at which point the record that was silently wrong becomes visibly
 * wrong somewhere far from the cause.
 *
 * <p>What is checked here is deliberately the weaker of the two questions in #110:
 * does the reference resolve to a pooled object AT ALL. That needs no view about which
 * class the value ought to carry, so it cannot be confused by the two legitimate reasons
 * a stamp differs from its declaration — an owned component is stamped with its
 * production site ({@code Name@Person.structuredName}), and a class standing for a union
 * of kinds holds members stamped with the kind they actually are. The stricter question,
 * whether a resolved value carries the DECLARED class, needs both of those rules and is
 * not answered here.
 */
public final class ReferenceResolutionReport {

    /** One declared reference field and the values that reach nothing. */
    public record Unresolved(String className, String fieldName, String declaredClass,
                             int values, List<String> sampleIds) {}

    public record Report(List<Unresolved> unresolved, int checkedValues) {
        public int unresolvedValues() {
            return unresolved.stream().mapToInt(Unresolved::values).sum();
        }

        public boolean clean() {
            return unresolved.isEmpty();
        }
    }

    private static final int SAMPLES = 5;

    private ReferenceResolutionReport() {}

    public static Report check(GeneratedProjectModel model,
                               Collection<WikidataDynamicObject> pool, GenerationLog log) {
        if (model == null || pool == null || pool.isEmpty()) {
            return new Report(List.of(), 0);
        }

        // By id alone, deliberately: an owned part shares its owner's qid, and a union
        // member is stamped with its own kind, so asking "is this id here at all" is the
        // question that has one right answer.
        Set<String> present = new LinkedHashSet<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null && o.getIdentifier() != null) present.add(o.getIdentifier());
        }

        Map<String, Map<String, String>> declared = declaredReferenceFields(model);
        Map<String, List<String>> misses = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int checked = 0;

        for (WikidataDynamicObject o : pool) {
            if (o == null) continue;
            Map<String, String> fields = declared.get(o.typeName());
            if (fields == null) continue;
            for (Map.Entry<String, String> field : fields.entrySet()) {
                for (String id : referencedIds(o.get(field.getKey()))) {
                    checked++;
                    if (present.contains(id)) continue;
                    String key = o.typeName() + '\0' + field.getKey() + '\0'
                            + field.getValue();
                    counts.merge(key, 1, Integer::sum);
                    List<String> samples =
                            misses.computeIfAbsent(key, ignored -> new ArrayList<>());
                    if (samples.size() < SAMPLES && !samples.contains(id)) {
                        samples.add(id);
                    }
                }
            }
        }

        List<Unresolved> out = new ArrayList<>();
        counts.forEach((key, n) -> {
            String[] parts = key.split("\0", -1);
            out.add(new Unresolved(parts[0], parts[1], parts[2], n,
                    List.copyOf(misses.getOrDefault(key, List.of()))));
        });
        out.sort((a, b) -> Integer.compare(b.values(), a.values()));

        Report report = new Report(List.copyOf(out), checked);
        report(report, log);
        return report;
    }

    private static void report(Report report, GenerationLog log) {
        if (log == null || report.clean()) return;
        StringBuilder message = new StringBuilder();
        message.append(report.unresolvedValues()).append(" of ")
                .append(report.checkedValues())
                .append(" reference value(s) point at an entity this domain does not "
                        + "hold. The field is filled, so coverage reports it as present.\n");
        for (Unresolved u : report.unresolved()) {
            message.append("  ").append(u.className()).append('.').append(u.fieldName())
                    .append(" -> ").append(u.declaredClass())
                    .append(": ").append(u.values()).append(" unresolved (")
                    .append(String.join(", ", u.sampleIds())).append(")\n");
        }
        log.message(message.toString());
    }

    /** class name -> (field name -> declared entity class), for ENTITY fields only. */
    private static Map<String, Map<String, String>> declaredReferenceFields(
            GeneratedProjectModel model) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz == null) continue;
            for (GeneratedFieldModel field : clazz.fields()) {
                if (field == null || field.type() != datasource.schema.FieldType.ENTITY) {
                    continue;
                }
                String target = field.entityClassName();
                if (target == null || target.isBlank()) continue;
                out.computeIfAbsent(clazz.className(), ignored -> new LinkedHashMap<>())
                        .put(field.name(), target);
            }
        }
        return out;
    }

    /** The ids a field value refers to, however many and however nested in a list. */
    private static List<String> referencedIds(Object value) {
        List<String> out = new ArrayList<>();
        collectIds(value, out);
        return out;
    }

    private static void collectIds(Object value, List<String> out) {
        if (value instanceof WikidataDynamicObject o) {
            String id = o.getIdentifier();
            if (id != null && !id.isBlank()) out.add(id);
        } else if (value instanceof Collection<?> many) {
            for (Object item : many) collectIds(item, out);
        }
    }
}
