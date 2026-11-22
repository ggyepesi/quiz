package domain.build;

import domain.datasource.api.SourceExecutionPlan;
import domain.datasource.api.SourceRuntimeServices;
import domain.runtime.GenerationLog;
import domain.runtime.WikidataDynamicObject;
import domain.config.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/** Executes installed external field-acquisition families from a resolved source plan. */
public final class ExternalSourceAcquisition {
    private static final int MAX_REFERENCE_PASSES = 12;

    private ExternalSourceAcquisition() { }

    public enum FailurePolicy { STRICT, CONTINUE_OPTIONAL }

    /** External acquisition plus the semantic work unlocked by entity-valued results. */
    public record Applied(Result sources, SemanticConvergence.Result semantic) { }

    public static Applied applyWithSemanticReplay(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            SourceExecutionPlan plan,
            SourceRuntimeServices services,
            GenerationLog log,
            work.CancellationToken cancellation,
            FailurePolicy failurePolicy,
            java.util.Collection<domain.runtime.LoadedDeclaration> alreadyLoaded,
            GenerationQualityTracker quality,
            Set<String> selectedFamilyIds) throws Exception {
        return applyWithSemanticReplay(model, pool, plan, services, log, cancellation,
                failurePolicy, alreadyLoaded, quality, StandardExternalSourceFamilies.create(),
                selectedFamilyIds);
    }

    static Applied applyWithSemanticReplay(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            SourceExecutionPlan plan,
            SourceRuntimeServices services,
            GenerationLog log,
            work.CancellationToken cancellation,
            FailurePolicy failurePolicy,
            java.util.Collection<domain.runtime.LoadedDeclaration> alreadyLoaded,
            GenerationQualityTracker quality,
            ExternalSourceFamilyRegistry registry,
            Set<String> selectedFamilyIds) throws Exception {
        Set<String> selected = selectedFamilyIds == null
                ? registry.families().stream().map(ExternalSourceFamily::id)
                        .collect(java.util.stream.Collectors.toCollection(
                                java.util.LinkedHashSet::new))
                : new java.util.LinkedHashSet<>(selectedFamilyIds);
        Set<String> producing = registry.families().stream()
                .filter(ExternalSourceFamily::producesEntityReferences)
                .map(ExternalSourceFamily::id).filter(selected::contains)
                .collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
        Result route = new Result(List.of(), List.of());
        SemanticConvergence.Result semantic = null;
        java.util.Collection<domain.runtime.LoadedDeclaration> loaded =
                alreadyLoaded == null ? List.of() : alreadyLoaded;
        wikidata.api.WikidataApiClient api = services == null ? null : services.find(
                domain.datasource.wikidata.WikidataDatasourceProvider.ID,
                wikidata.api.WikidataApiClient.class).orElse(null);
        for (int pass = 1; !producing.isEmpty() && pass <= MAX_REFERENCE_PASSES; pass++) {
            Result round = apply(model, pool, plan, services, log, cancellation,
                    failurePolicy, registry, producing);
            route = mergeRounds(route, round);
            if (round.values() == 0) break;
            if (log != null) log.message("Entity-valued sources pass " + pass + " added "
                    + round.values() + " reference(s); settling their configured classes "
                    + "and fields before checking for newly eligible owners.\n");
            SemanticConvergence.Result settled = SemanticConvergence.apply(
                    model, pool, api, log, loaded, quality, plan);
            semantic = SemanticConvergence.combine(semantic, settled);
            loaded = settled.completedDeclarations().values();
            if (pass == MAX_REFERENCE_PASSES) {
                String warning = "Entity-valued source acquisition exceeded "
                        + MAX_REFERENCE_PASSES + " fixed-point passes";
                if (quality != null) quality.failed("entity-reference-acquisition", warning);
                route = route.withFailure(warning);
                if (log != null) log.message("WARNING: " + warning + ".\n");
            }
        }
        selected.removeAll(producing);
        Result remaining = selected.isEmpty()
                ? new Result(List.of(), List.of())
                : apply(model, pool, plan, services, log, cancellation, failurePolicy,
                        registry, selected);
        List<ExternalSourceFamily.Outcome> outcomes = new ArrayList<>(route.outcomes());
        outcomes.addAll(remaining.outcomes());
        List<String> failures = new ArrayList<>(route.failures());
        failures.addAll(remaining.failures());
        return new Applied(new Result(outcomes, failures), semantic);
    }

    /** Accumulates repeated outcomes from one family without exposing duplicate family
     * entries to callers. The last summary supplies the wording; its leading count is
     * replaced by the cumulative number when it follows the standard outcome contract. */
    private static Result mergeRounds(Result first, Result later) {
        LinkedHashMap<String, ExternalSourceFamily.Outcome> merged = new LinkedHashMap<>();
        for (ExternalSourceFamily.Outcome outcome : first.outcomes()) {
            merged.put(outcome.familyId(), outcome);
        }
        for (ExternalSourceFamily.Outcome outcome : later.outcomes()) {
            ExternalSourceFamily.Outcome prior = merged.get(outcome.familyId());
            if (prior == null) {
                merged.put(outcome.familyId(), outcome);
                continue;
            }
            int values = prior.values() + outcome.values();
            LinkedHashSet<String> warnings = new LinkedHashSet<>(prior.warnings());
            warnings.addAll(outcome.warnings());
            String summary = cumulativeSummary(outcome.summary(), outcome.values(), values);
            merged.put(outcome.familyId(), new ExternalSourceFamily.Outcome(
                    outcome.familyId(), values, summary, outcome.summaryOrder(),
                    List.copyOf(warnings)));
        }
        LinkedHashSet<String> failures = new LinkedHashSet<>(first.failures());
        failures.addAll(later.failures());
        return new Result(List.copyOf(merged.values()), List.copyOf(failures));
    }

    private static String cumulativeSummary(String summary, int round, int total) {
        if (summary == null || summary.isBlank() || round == total) return summary;
        String prefix = Integer.toString(round);
        return summary.startsWith(prefix + " ")
                ? total + summary.substring(prefix.length()) : summary;
    }

    public static Applied applyWithSemanticReplay(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            SourceExecutionPlan plan,
            SourceRuntimeServices services,
            GenerationLog log,
            work.CancellationToken cancellation,
            FailurePolicy failurePolicy,
            java.util.Collection<domain.runtime.LoadedDeclaration> alreadyLoaded,
            GenerationQualityTracker quality) throws Exception {
        return applyWithSemanticReplay(model, pool, plan, services, log, cancellation,
                failurePolicy, alreadyLoaded, quality, null);
    }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            SourceRuntimeServices services,
            GenerationLog log, work.CancellationToken cancellation,
            FailurePolicy failurePolicy) throws Exception {
        ExternalSourceFamilyRegistry registry = StandardExternalSourceFamilies.create();
        Set<String> all = registry.families().stream().map(ExternalSourceFamily::id)
                .collect(java.util.stream.Collectors.toSet());
        return apply(model, pool, plan, services, log, cancellation,
                failurePolicy, registry, all);
    }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            SourceRuntimeServices services,
            GenerationLog log, work.CancellationToken cancellation,
            FailurePolicy failurePolicy, Set<String> selectedFamilyIds) throws Exception {
        return apply(model, pool, plan, services, log, cancellation,
                failurePolicy, StandardExternalSourceFamilies.create(), selectedFamilyIds);
    }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            SourceRuntimeServices services,
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
                model, pool == null ? List.of() : pool, plan,
                services == null ? SourceRuntimeServices.empty() : services, sink, token);
        List<ExternalSourceFamily.Outcome> outcomes = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (ExternalSourceFamily family : registry.families()) {
            if (!selected.contains(family.id())) continue;
            ExternalSourceFamily.Outcome empty = family.empty();
            if (!family.configured(plan)) {
                outcomes.add(empty);
                continue;
            }
            try {
                ExternalSourceFamily.Outcome outcome = family.acquire(context);
                outcomes.add(outcome);
                failures.addAll(outcome.warnings());
            } catch (CancellationException | InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                if (failurePolicy != FailurePolicy.CONTINUE_OPTIONAL) throw failure;
                String message = family.displayName() + " acquisition failed: "
                        + failure.getMessage();
                sink.message(message + "\n");
                failures.add(message);
                outcomes.add(empty);
            }
        }
        return new Result(outcomes, failures);
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
        private final List<String> failures;

        Result(List<ExternalSourceFamily.Outcome> outcomes) {
            this(outcomes, List.of());
        }

        Result(List<ExternalSourceFamily.Outcome> outcomes, List<String> failures) {
            this.outcomes = List.copyOf(outcomes == null ? List.of() : outcomes);
            this.failures = List.copyOf(failures == null ? List.of() : failures);
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
        public List<String> failures() { return failures; }
        public boolean complete() { return failures.isEmpty(); }
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

        Result withFailure(String failure) {
            LinkedHashSet<String> all = new LinkedHashSet<>(failures);
            if (failure != null && !failure.isBlank()) all.add(failure);
            return new Result(outcomes, List.copyOf(all));
        }
    }
}
