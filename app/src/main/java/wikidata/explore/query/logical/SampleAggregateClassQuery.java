package wikidata.explore.query.logical;

import wikidata.explore.compiled.CompiledAggregateSource;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.result.ClassSampleResult;
import wikidata.explore.transform.ModelAggregates;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.WikidataIds;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A sample of an aggregate class: complete groups over a sampled set of KEYS.
 *
 * <p>Every other class can be sampled by taking the first N instances, because an
 * instance is produced from one record. An aggregate is produced by REDUCING its source
 * class, so the first N source records give groups missing most of their members — 25
 * sampled laureates grouped by (category, year) would show Nobel prizes with one laureate
 * each, presented as the prizes themselves. A sample that is merely small is fine; one
 * that is WRONG about what it shows is not, and this is the sample used to check that a
 * key groups the way the modeller intended.
 *
 * <p>So the bound moves off the members and onto the key. Two passes over the same
 * production:
 *
 * <ol>
 *   <li><b>Which keys exist.</b> A bounded read of the source class, reduced, to learn
 *       key values that really occur. Nothing from this pass is shown — its groups are
 *       exactly the partial ones this query exists to avoid.</li>
 *   <li><b>Every member of those keys.</b> The source read again, narrowed to the chosen
 *       keys as far as the acquisition can express it, and reduced. Only the chosen
 *       groups are kept.</li>
 * </ol>
 *
 * <p>How far the narrowing reaches is a property of the model, not a setting: a key
 * component that reads the statement's OBJECT becomes an explicit object bound, and one
 * that reads a qualifier cannot be pushed into the query at all. Nobel's (category, year)
 * narrows on category — a third of the corpus rather than all of it — and reaches the
 * year by reducing what comes back. {@link #summary} says which happened, because "the
 * sample read the whole source class" is something the reader should learn from the
 * result and not from the wait.
 */
public final class SampleAggregateClassQuery implements Query<ClassSampleResult> {

    /** How many source records the first pass reads for every key it is asked to find.
     *  Keys repeat — that is what makes them keys — so finding N of them takes more
     *  than N records, and reading too few finds fewer keys than the sample asked for. */
    private static final int RECORDS_PER_KEY = 8;

    private final GeneratedProjectModel snapshot;
    private final String className;
    private final String productionRoute;
    private final int limit;

    public SampleAggregateClassQuery(GeneratedProjectModel project, String className,
            String productionRoute, int limit) {
        snapshot = project == null ? null : project.copy();
        this.className = clean(className);
        this.productionRoute = clean(productionRoute);
        this.limit = Math.max(1, limit);
    }

    @Override public String purpose() { return "Sample aggregate class instances"; }
    @Override public String skeleton() {
        return "bounded source read -> choose keys -> read those keys whole -> reduce";
    }
    @Override public Map<String, String> parameters() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("class", className);
        values.put("productionRoute", productionRoute);
        values.put("keys", String.valueOf(limit));
        return values;
    }

    @Override public ClassSampleResult execute(QueryContext context) throws Exception {
        if (snapshot == null) throw new IllegalStateException("No model to sample");
        CompiledProjectModel compiled = ProjectModelCompiler.compile(snapshot);
        CompiledClass aggregate = compiled.findClass(className).orElseThrow(() ->
                new IllegalStateException("Compiled class is missing: " + className));
        CompiledAggregateSource source = aggregate.aggregateSource();
        if (source == null || !source.configured()) {
            throw new IllegalStateException(
                    className + " has no configured aggregate source to group from");
        }

        String objectField = objectKeyField(compiled, source);
        Keys chosen = context.step("Choose keys to sample", "Workflow",
                skeleton(), parameters(), step -> {
                    GenerationLog log = StepGenerationLog.of(context, step);
                    SampledClassProduction.Records probe = SampledClassProduction.of(
                            snapshot, compiled, source.sourceClassName(),
                            SampledClassProduction.Bound.firstMembers(limit * RECORDS_PER_KEY),
                            context, log);
                    List<WikidataDynamicObject> pool = new ArrayList<>(probe.records());
                    ModelAggregates.apply(compiled, pool, log);
                    Keys keys = keysOf(pool, className, objectField, limit);
                    log.message("Key sample: " + keys.ids().size() + " key(s) of " + className
                            + " found in " + probe.records().size() + " source record(s).\n");
                    return keys;
                });

        if (chosen.ids().isEmpty()) {
            return ClassSampleResults.materialize(snapshot, className, className,
                    productionRoute, limit, List.of(), false);
        }

        List<String> chosenIds = chosen.ids();
        List<String> objectQids = chosen.objectQids();
        return context.step("Read those keys whole", "Workflow", skeleton(), parameters(),
                step -> {
                    GenerationLog log = StepGenerationLog.of(context, step);
                    log.message(objectQids.isEmpty()
                            ? "No key component reaches the acquisition, so the whole "
                                    + "source class is read.\n"
                            : "Narrowed to " + objectQids.size() + " statement object(s): "
                                    + String.join(", ", objectQids) + ".\n");
                    SampledClassProduction.Records whole = SampledClassProduction.of(
                            snapshot, compiled, source.sourceClassName(),
                            SampledClassProduction.Bound.wholeObjects(objectQids),
                            context, log);
                    List<WikidataDynamicObject> pool = new ArrayList<>(whole.records());
                    ModelAggregates.apply(compiled, pool, log);
                    List<WikidataDynamicObject> groups = keep(pool, className, chosenIds);
                    // Truncation is about KEYS here: the groups themselves are whole,
                    // and saying "more available" of a complete group would say the
                    // opposite of what this query guarantees.
                    return ClassSampleResults.materialize(snapshot, className, className,
                            productionRoute, limit, groups, groups.size() >= limit);
                });
    }

    /**
     * The keys chosen from a probe pass.
     *
     * @param ids        which groups to keep, by identifier
     * @param objectQids the entities those groups' object-side key component names, when
     *                   there is one — the part of the key the acquisition can carry
     */
    record Keys(List<String> ids, List<String> objectQids) { }

    /** The first {@code count} groups of {@code type}, and what they say about the key. */
    static Keys keysOf(List<WikidataDynamicObject> pool, String type,
            String objectField, int count) {
        List<String> ids = new ArrayList<>();
        Set<String> qids = new LinkedHashSet<>();
        for (WikidataDynamicObject group : pool) {
            if (ids.size() >= count) break;
            if (group == null || !group.directClassNames().contains(type)) continue;
            String id = group.getIdentifier();
            if (id == null || ids.contains(id)) continue;
            ids.add(id);
            if (!objectField.isBlank()) {
                String qid = qidOf(group.get(objectField));
                if (!qid.isBlank()) qids.add(qid);
            }
        }
        return new Keys(ids, List.copyOf(qids));
    }

    /** The entity a key value names, or blank when the value is not one. */
    private static String qidOf(Object value) {
        String id = value instanceof WikidataDynamicObject entity ? entity.qid()
                : value instanceof objectview.Viewable viewable ? viewable.getIdentifier()
                : String.valueOf(value);
        return id != null && WikidataIds.isQid(id.trim()) ? id.trim() : "";
    }

    /** Only the named groups, in the order they were chosen. */
    static List<WikidataDynamicObject> keep(
            List<WikidataDynamicObject> pool, String type, List<String> ids) {
        Map<String, WikidataDynamicObject> byId = new LinkedHashMap<>();
        for (WikidataDynamicObject object : pool) {
            if (object != null && object.directClassNames().contains(type)) {
                byId.put(object.getIdentifier(), object);
            }
        }
        List<WikidataDynamicObject> kept = new ArrayList<>();
        for (String id : ids) {
            WikidataDynamicObject found = byId.get(id);
            if (found != null) kept.add(found);
        }
        return kept;
    }

    /**
     * Which of the aggregate's own fields holds the part of the key the acquisition can
     * carry, or blank when none does.
     *
     * <p>Exactly one key component can be: the one whose source field is the statement's
     * value field, because that field IS the statement's object and the object end is
     * bounded by an existing construct. A key component reading a qualifier has no such
     * end to bind, so it is reached by reducing what comes back rather than by asking
     * for less — and an aggregate over a class that is not a statement class has no
     * object end at all.
     */
    static String objectKeyField(
            CompiledProjectModel compiled, CompiledAggregateSource source) {
        CompiledClass sourceClass = compiled.findClass(source.sourceClassName()).orElse(null);
        if (sourceClass == null) return "";
        ModelStatementReifications.Reification reification =
                ModelStatementReifications.deriveOne(sourceClass, compiled);
        if (reification == null) return "";
        String objectField = reification.load().valueField();
        return source.keys().stream()
                .filter(key -> key.sourceField().equals(objectField))
                .map(CompiledAggregateSource.Key::targetField)
                .findFirst().orElse("");
    }

    @Override public int rowCount(ClassSampleResult result) {
        return result == null ? 0 : result.size();
    }

    @Override public String summary(ClassSampleResult result) {
        return result == null ? "0 sampled groups"
                : result.size() + " complete group(s)"
                + (result.truncated() ? "; more keys available" : "; every key in range");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
