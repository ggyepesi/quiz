package wikidata.explore.generation;

import datasource.api.SourceExecutionPlan;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/** Executes installed external field-acquisition families from a resolved source plan. */
public final class ExternalSourceAcquisition {
    private ExternalSourceAcquisition() { }

    public enum FailurePolicy { STRICT, CONTINUE_OPTIONAL }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            WikidataSparqlClient dbpedia, wikidata.api.WikidataApiClient wikidata,
            GenerationLog log, work.CancellationToken cancellation,
            FailurePolicy failurePolicy) throws Exception {
        ExternalSourceFamilyRegistry registry = StandardExternalSourceFamilies.create();
        Set<String> all = registry.families().stream().map(ExternalSourceFamily::id)
                .collect(java.util.stream.Collectors.toSet());
        return apply(model, pool, plan, dbpedia, wikidata, log, cancellation,
                failurePolicy, registry, all);
    }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            WikidataSparqlClient dbpedia, wikidata.api.WikidataApiClient wikidata,
            GenerationLog log, work.CancellationToken cancellation,
            FailurePolicy failurePolicy, Set<String> selectedFamilyIds) throws Exception {
        return apply(model, pool, plan, dbpedia, wikidata, log, cancellation,
                failurePolicy, StandardExternalSourceFamilies.create(), selectedFamilyIds);
    }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            WikidataSparqlClient dbpedia, wikidata.api.WikidataApiClient wikidata,
            GenerationLog log, work.CancellationToken cancellation,
            FailurePolicy failurePolicy, ExternalSourceFamilyRegistry registry,
            Set<String> selectedFamilyIds) throws Exception {
        if (registry == null) throw new IllegalArgumentException("Family registry is required");
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        work.CancellationToken token = cancellation == null
                ? new work.CancellationToken() : cancellation;
        Set<String> selected = selectedFamilyIds == null
                ? Set.of() : Set.copyOf(selectedFamilyIds);
        selected.forEach(registry::require);
        ExternalSourceFamily.Context context = new ExternalSourceFamily.Context(
                model, pool == null ? List.of() : pool, plan, dbpedia, wikidata, sink, token);
        List<ExternalSourceFamily.Outcome> outcomes = new ArrayList<>();

        for (ExternalSourceFamily family : registry.families()) {
            if (!selected.contains(family.id())) continue;
            ExternalSourceFamily.Outcome empty = family.empty();
            if (!family.configured(plan)) {
                outcomes.add(empty);
                continue;
            }
            outcomes.add(run(family.displayName(), failurePolicy, sink, empty,
                    () -> family.acquire(context)));
        }
        return new Result(outcomes);
    }

    static <T> T run(String family, FailurePolicy policy, GenerationLog log,
            T fallback, Callable<T> work) throws Exception {
        try {
            return work.call();
        } catch (CancellationException | InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            if (policy != FailurePolicy.CONTINUE_OPTIONAL) throw failure;
            log.message(family + " acquisition failed; continuing: "
                    + failure.getMessage() + "\n");
            return fallback;
        }
    }

    public static final class Result {
        private final List<ExternalSourceFamily.Outcome> outcomes;
        private final Map<String, ExternalSourceFamily.Outcome> byFamily;

        Result(List<ExternalSourceFamily.Outcome> outcomes) {
            this.outcomes = List.copyOf(outcomes == null ? List.of() : outcomes);
            LinkedHashMap<String, ExternalSourceFamily.Outcome> index = new LinkedHashMap<>();
            for (ExternalSourceFamily.Outcome outcome : this.outcomes) {
                if (index.putIfAbsent(outcome.familyId(), outcome) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate external source outcome: " + outcome.familyId());
                }
            }
            this.byFamily = Map.copyOf(index);
        }

        public List<ExternalSourceFamily.Outcome> outcomes() { return outcomes; }
        public ExternalSourceFamily.Outcome outcome(String familyId) {
            return byFamily.get(familyId);
        }
        public int values() {
            return outcomes.stream().mapToInt(ExternalSourceFamily.Outcome::values).sum();
        }
        public String summary() {
            return outcomes.stream()
                    .sorted(java.util.Comparator.comparingInt(
                            ExternalSourceFamily.Outcome::summaryOrder))
                    .map(ExternalSourceFamily.Outcome::summary)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        /** The non-empty family outcomes, ordered for one cumulative live update. */
        public String acquiredSummary() {
            return outcomes.stream()
                    .filter(outcome -> outcome.values() > 0)
                    .sorted(java.util.Comparator.comparingInt(
                            ExternalSourceFamily.Outcome::summaryOrder))
                    .map(ExternalSourceFamily.Outcome::summary)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.joining(", "));
        }
    }
}
