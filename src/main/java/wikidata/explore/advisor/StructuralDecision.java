package wikidata.explore.advisor;

import java.util.function.Predicate;

/**
 * One branching point in the model-building workflow — a structural question with
 * the three things the user needs at that point: what to know ({@link #question}),
 * the tool that gathers the deciding info ({@link #tool}), and the hinted decision
 * ({@link #hint}). {@link #applies} says the question is relevant to a class at
 * all; {@link #resolved} says it has already been answered (so the guide can show
 * resolved steps with a check and pending ones as open branches).
 *
 * <p>These are the nodes of the "teach the user the model-builder" script — see
 * {@link DecisionCatalog}.
 */
public record StructuralDecision(
        String id,
        String question,
        String tool,
        String hint,
        Predicate<DecisionContext> applies,
        Predicate<DecisionContext> resolved) {

    public boolean appliesTo(DecisionContext ctx) {
        return applies.test(ctx);
    }

    public boolean isResolved(DecisionContext ctx) {
        return resolved.test(ctx);
    }
}
