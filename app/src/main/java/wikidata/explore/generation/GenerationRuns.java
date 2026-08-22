package wikidata.explore.generation;

import wikidata.explore.codegen.GeneratedViewableRuntime;

/**
 * Handing the workbench from one generation run to the next.
 *
 * <p>A run owns a compiled runtime — class loaders for the classes it generated — and when a
 * new run replaces it, that runtime has to be closed or the loaders leak. Except when the
 * incoming run is the outgoing one with a detail changed and carries the SAME runtime, which
 * is what happens when a fetched declaration is forgotten: closing there would shut a runtime
 * still in use. Per {@link GeneratedViewableRuntime#close()} that is not fatal — instances
 * already loaded keep working — but further class lookups through those loaders stop, so a
 * run that later maps a new type fails for no visible reason.
 *
 * <p>Both halves of the rule existed and neither was stated: one place closed and one place
 * pointedly did not, each correct, with nothing to consult when writing a third.
 */
public final class GenerationRuns {

    private GenerationRuns() { }

    /** The runtime that nothing will use once {@code next} takes over, or null. */
    public static GeneratedViewableRuntime superseded(GenerationRun previous, GenerationRun next) {
        if (previous == null || previous == next || previous.runtime() == null) return null;
        GeneratedViewableRuntime carriedOver = next == null ? null : next.runtime();
        return previous.runtime() == carriedOver ? null : previous.runtime();
    }

    /**
     * Closes whatever {@code next} supersedes and returns it, for {@code
     * lastRun = GenerationRuns.handOver(lastRun, run)} — so a caller states the handover
     * rather than remembering the rule.
     */
    public static GenerationRun handOver(GenerationRun previous, GenerationRun next) {
        GeneratedViewableRuntime finished = superseded(previous, next);
        if (finished != null) finished.close();
        return next;
    }
}
