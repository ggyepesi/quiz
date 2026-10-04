package wikidata.explore.generation;

import datasource.api.BindingScope;
import datasource.api.SourceExecutionPlan;
import datasource.wikidata.WikidataDatasourceProvider;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads numeric facts computed by WDQS for already identified model instances. */
public final class WikidataComputedFieldAcquisition {
    private static final int BATCH = 50;

    private WikidataComputedFieldAcquisition() { }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            WikidataSparqlClient client, GenerationLog log) throws Exception {
        return apply(model, pool, plan, client, log, new work.CancellationToken());
    }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            WikidataSparqlClient client, GenerationLog log,
            work.CancellationToken cancellation) throws Exception {
        if (model == null || pool == null || pool.isEmpty() || plan == null || client == null) {
            return new Result(0, 0);
        }
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        int fields = 0;
        int values = 0;
        List<Exception> failures = new ArrayList<>();
        for (GeneratedClassModel owner : model.classes()) {
            Map<String, WikidataDynamicObject> targets = targets(model, pool, owner);
            if (targets.isEmpty()) continue;
            for (SourceExecutionPlan.Step step : plan.steps(BindingScope.FIELD_VALUE)) {
                if (!owner.className().equals(step.target().className())) continue;
                WikidataDatasourceProvider.ComputedFieldSpec spec =
                        step.prepared().configuration(
                                WikidataDatasourceProvider.ComputedFieldSpec.class);
                if (spec == null || step.target().fieldPath().contains(".")) continue;
                GeneratedFieldModel field = owner.fields().stream()
                        .filter(candidate -> candidate != null
                                && step.target().fieldPath().equals(candidate.name()))
                        .findFirst().orElse(null);
                if (field == null || field.isNameField()) continue;
                fields++;
                try {
                    int merged = spec.kind() == WikidataDatasourceProvider
                            .ComputedFieldSpec.Kind.INHERITED_INCOMING_RELATION
                            ? loadInherited(targets, field, spec, client, sink,
                                    cancellation == null ? new work.CancellationToken()
                                            : cancellation)
                            : load(targets, field, spec, client, sink,
                                    cancellation == null ? new work.CancellationToken()
                                            : cancellation);
                    values += merged;
                    sink.message("  " + owner.className() + "." + field.name()
                            + ": " + merged + " computed value(s)\n");
                } catch (java.util.concurrent.CancellationException
                        | InterruptedException cancelled) {
                    throw cancelled;
                } catch (Exception failure) {
                    failures.add(failure);
                    sink.message("  " + owner.className() + "." + field.name()
                            + " failed; continuing with the next computed field: "
                            + failure.getMessage() + "\n");
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new java.io.IOException(failures.size()
                    + " Wikidata computed field(s) did not finish; later fields were "
                    + "still attempted", failures.getFirst());
        }
        return new Result(fields, values);
    }

    private static int load(Map<String, WikidataDynamicObject> targets,
            GeneratedFieldModel field, WikidataDatasourceProvider.ComputedFieldSpec spec,
            WikidataSparqlClient client, GenerationLog log,
            work.CancellationToken cancellation) throws Exception {
        List<String> qids = new ArrayList<>(targets.keySet());
        java.util.concurrent.atomic.AtomicInteger merged =
                new java.util.concurrent.atomic.AtomicInteger();
        List<batch.WorkUnit<Map<String, Long>>> units = new ArrayList<>();
        for (int offset = 0; offset < qids.size(); offset += BATCH) {
            List<String> batch = qids.subList(offset, Math.min(offset + BATCH, qids.size()));
            units.add(unit(List.copyOf(batch), spec, client, field.name()));
        }
        try (GenerationLog.Group group = log.group("Compute " + field.name() + " for "
                + qids.size() + " entities in batches of " + BATCH)) {
            // The SPARQL transport already retries transient endpoint failures. At this
            // level, immediately split a failed query: repeating the same 100-QID query
            // made a timeout consume the whole family and the following computed field
            // was never attempted.
            batch.BatchPolicy policy = new batch.BatchPolicy(1, 4, 0L, false, 2);
            List<batch.WorkDescriptor> failed =
                    new batch.BatchExecutor<Map<String, Long>>(policy, group.batchProgress(),
                    wikidata.WikidataBatchFailureClassifier.INSTANCE, cancellation,
                    batch.BatchCheckpointStore.NONE, client.maxParallelRequests())
                    .runBestEffort(units, (descriptor, values) -> values.forEach((qid, value) -> {
                        WikidataDynamicObject target = targets.get(qid);
                        if (target == null || value == null) return;
                        target.put(field.name(), value);
                        merged.incrementAndGet();
                    }));
            if (!failed.isEmpty()) {
                throw new java.io.IOException(field.name() + " left " + failed.size()
                        + " batch(es) unresolved after all other batches finished: "
                        + failed.stream().map(batch.WorkDescriptor::title)
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
        return merged.get();
    }

    /**
     * Counts inherited sources from a bounded descendant side. Asking WDQS to start
     * with every possible statement and walk P279 toward fifty target ancestors
     * repeatedly timed out even after splitting to three targets. Here each unit
     * starts with at most fifty loaded instances, reads their direct sources, walks
     * upward, and the client merges source QIDs into distinct sets locally. Only
     * loaded instances are descendants here, so a subclass outside the population
     * contributes nothing — the offering says so.
     */
    private static int loadInherited(Map<String, WikidataDynamicObject> targets,
            GeneratedFieldModel field, WikidataDatasourceProvider.ComputedFieldSpec spec,
            WikidataSparqlClient client, GenerationLog log,
            work.CancellationToken cancellation) throws Exception {
        List<String> qids = new ArrayList<>(targets.keySet());
        Map<String, java.util.BitSet> sourcesByAncestor = new LinkedHashMap<>();
        Map<String, Integer> sourceIds = new LinkedHashMap<>();
        List<batch.WorkUnit<InheritedEdges>> units = new ArrayList<>();
        for (int offset = 0; offset < qids.size(); offset += BATCH) {
            List<String> batch = qids.subList(offset, Math.min(offset + BATCH, qids.size()));
            units.add(inheritedUnit(List.copyOf(batch), spec, client, field.name()));
        }
        try (GenerationLog.Group group = log.group("Compute " + field.name() + " for "
                + qids.size() + " instances from loaded-descendant batches of " + BATCH)) {
            batch.BatchPolicy policy = new batch.BatchPolicy(1, 4, 0L, false, 2);
            List<batch.WorkDescriptor> failed =
                    new batch.BatchExecutor<InheritedEdges>(
                            policy, group.batchProgress(),
                            wikidata.WikidataBatchFailureClassifier.INSTANCE, cancellation,
                            batch.BatchCheckpointStore.NONE, client.maxParallelRequests())
                    .runBestEffort(units, (descriptor, values) -> {
                        for (Map.Entry<String, java.util.Set<String>> descendant
                                : values.sourcesByDescendant().entrySet()) {
                            java.util.Set<String> ancestors =
                                    values.ancestorsByDescendant().getOrDefault(
                                            descendant.getKey(), java.util.Set.of());
                            for (String ancestor : ancestors) {
                                if (!targets.containsKey(ancestor)) continue;
                                java.util.BitSet sources = sourcesByAncestor.computeIfAbsent(
                                        ancestor, ignored -> new java.util.BitSet());
                                for (String source : descendant.getValue()) {
                                    int sourceId = sourceIds.computeIfAbsent(source,
                                            ignored -> sourceIds.size());
                                    sources.set(sourceId);
                                }
                            }
                        }
                    });
            if (!failed.isEmpty()) {
                throw new java.io.IOException(field.name() + " left " + failed.size()
                        + " loaded-descendant batch(es) unresolved after splitting: "
                        + failed.stream().map(batch.WorkDescriptor::title)
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
        for (Map.Entry<String, WikidataDynamicObject> target : targets.entrySet()) {
            target.getValue().put(field.name(),
                    (long) sourcesByAncestor.getOrDefault(target.getKey(),
                            new java.util.BitSet()).cardinality());
        }
        return targets.size();
    }

    private static batch.WorkUnit<InheritedEdges> inheritedUnit(List<String> qids,
            WikidataDatasourceProvider.ComputedFieldSpec spec,
            WikidataSparqlClient client, String fieldName) {
        return new batch.WorkUnit<>() {
            @Override public batch.WorkDescriptor descriptor() {
                String ids = String.join(",", qids);
                return new batch.WorkDescriptor("wikidata-computed-field",
                        fieldName + ":" + ids,
                        fieldName + " from " + qids.size() + " loaded descendants",
                        Map.of("field", fieldName, "ids", ids));
            }

            @Override public String request() { return inheritedQuery(qids, spec); }

            @Override public InheritedEdges execute() throws Exception {
                Map<String, java.util.Set<String>> sources = new LinkedHashMap<>();
                Map<String, java.util.Set<String>> ancestors = new LinkedHashMap<>();
                for (WikidataBinding row : client.query(inheritedQuery(qids, spec))) {
                    String descendant = qidFromUri(row.value("descendant"));
                    String ancestor = qidFromUri(row.value("entity"));
                    String source = qidFromUri(row.value("source"));
                    if (descendant == null) continue;
                    if (source != null) {
                        sources.computeIfAbsent(descendant, ignored ->
                                new java.util.LinkedHashSet<>()).add(source);
                    }
                    if (ancestor != null) {
                        ancestors.computeIfAbsent(descendant, ignored ->
                                new java.util.LinkedHashSet<>()).add(ancestor);
                    }
                }
                return new InheritedEdges(sources, ancestors);
            }

            @Override public List<? extends batch.WorkUnit<InheritedEdges>> split() {
                if (qids.size() < 2) return List.of();
                int middle = qids.size() / 2;
                return List.of(
                        inheritedUnit(List.copyOf(qids.subList(0, middle)), spec, client,
                                fieldName),
                        inheritedUnit(List.copyOf(qids.subList(middle, qids.size())), spec,
                                client, fieldName));
            }
        };
    }

    static String inheritedQuery(List<String> descendantQids,
            WikidataDatasourceProvider.ComputedFieldSpec spec) {
        String values = descendantQids.stream().filter(WikidataIds::isQid)
                .map(qid -> "wd:" + qid).collect(java.util.stream.Collectors.joining(" "));
        return "SELECT DISTINCT ?descendant ?entity ?source WHERE { "
                + "VALUES ?descendant { " + values + " } { "
                + "?source wdt:" + spec.propertyPid() + " ?descendant . } UNION { "
                + "?descendant wdt:P279+ ?entity . } "
                + "hint:Query hint:optimizer \"None\" . }";
    }

    /**
     * The two sides of the inherited count deliberately remain separate. Joining them
     * at WDQS materializes holder x ancestor rows: one observed 25-position batch
     * returned 515,491 rows and exhausted the client heap when several responses were
     * parsed concurrently. The union returns each edge once; the compact BitSets above
     * perform the exact distinct-holder join after each bounded batch completes.
     */
    private record InheritedEdges(
            Map<String, java.util.Set<String>> sourcesByDescendant,
            Map<String, java.util.Set<String>> ancestorsByDescendant) { }

    private static batch.WorkUnit<Map<String, Long>> unit(List<String> qids,
            WikidataDatasourceProvider.ComputedFieldSpec spec,
            WikidataSparqlClient client, String fieldName) {
        return new batch.WorkUnit<>() {
            @Override public batch.WorkDescriptor descriptor() {
                String ids = String.join(",", qids);
                return new batch.WorkDescriptor("wikidata-computed-field",
                        fieldName + ":" + ids,
                        fieldName + " for " + qids.size() + " entities",
                        Map.of("field", fieldName, "ids", ids));
            }

            @Override public String request() {
                return query(qids, spec);
            }

            @Override public Map<String, Long> execute() throws Exception {
                Map<String, Long> result = new LinkedHashMap<>();
                for (WikidataBinding row : client.query(query(qids, spec))) {
                    String qid = qidFromUri(row.value("entity"));
                    String value = row.value("count");
                    if (qid == null || value == null || value.isBlank()) continue;
                    try {
                        result.put(qid, Long.parseLong(value));
                    } catch (NumberFormatException ignored) { }
                }
                return result;
            }

            @Override public List<? extends batch.WorkUnit<Map<String, Long>>> split() {
                if (qids.size() < 2) return List.of();
                int middle = qids.size() / 2;
                return List.of(
                        unit(List.copyOf(qids.subList(0, middle)), spec, client, fieldName),
                        unit(List.copyOf(qids.subList(middle, qids.size())), spec, client,
                                fieldName));
            }
        };
    }

    static String query(List<String> qids,
            WikidataDatasourceProvider.ComputedFieldSpec spec) {
        String values = qids.stream().filter(WikidataIds::isQid)
                .map(qid -> "wd:" + qid).collect(java.util.stream.Collectors.joining(" "));
        if (spec.kind() == WikidataDatasourceProvider.ComputedFieldSpec.Kind.SITELINKS) {
            return "SELECT ?entity ?count WHERE { VALUES ?entity { " + values
                    + " } OPTIONAL { ?entity wikibase:sitelinks ?sitelinks . } "
                    + "BIND(COALESCE(?sitelinks, 0) AS ?count) }";
        }
        return "SELECT ?entity (COUNT(DISTINCT ?source) AS ?count) WHERE { VALUES ?entity { "
                + values + " } OPTIONAL { ?source wdt:" + spec.propertyPid() + " "
                + "?entity . } } GROUP BY ?entity";
    }

    private static Map<String, WikidataDynamicObject> targets(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, GeneratedClassModel owner) {
        Map<String, WikidataDynamicObject> result = new LinkedHashMap<>();
        for (WikidataDynamicObject object : pool) {
            if (object == null || object.isPart() || !WikidataIds.isQid(object.qid())) continue;
            boolean applies = object.directClassNames().stream().anyMatch(name -> {
                for (GeneratedClassModel current = model.findClass(name); current != null;
                        current = current.baseClassName().isBlank() ? null
                                : model.findClass(current.baseClassName())) {
                    if (owner.className().equals(current.className())) return true;
                }
                return false;
            });
            if (applies) result.put(object.qid(), object);
        }
        return result;
    }

    private static String qidFromUri(String uri) {
        if (uri == null) return null;
        String tail = uri.substring(uri.lastIndexOf('/') + 1);
        return WikidataIds.isQid(tail) ? tail : null;
    }

    public record Result(int fields, int values) { }
}
