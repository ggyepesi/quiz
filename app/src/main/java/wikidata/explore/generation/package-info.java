/**
 * The generation pipeline as separable stages.
 *
 * <p>{@code GenerationPipeline} exposes each step — {@code plan} (model → rule
 * tree), {@code extract} (rule tree → dynamic objects), {@code buildRuntime}
 * (model → compiled classes) and {@code materialize} (runtime + data → mapped
 * Quizables) — and chains them in {@code fullRun}. The DBpedia enrichment runs
 * between extract and buildRuntime; {@code remap} re-maps a previous download
 * without re-fetching.
 *
 * <p>{@code GenerationRun} carries the run's provenance (model snapshot, plan,
 * dynamic objects, runtime, instances) for the UI and "Save domain".
 */
package wikidata.explore.generation;
