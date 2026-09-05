package wikidata.explore.generation;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the steps a compiled run says to run, in phase order, and refuses the rest.
 *
 * <p>The order is {@link PipelinePhase}'s and belongs to nobody else. Five flows each
 * deciding it produced five orderings that drifted — and the drift was invisible,
 * because a hand-authored sequence looks correct from inside the method that wrote it.
 *
 * <p>Three refusals, each before the step runs rather than during it:
 *
 * <ul>
 *   <li>a phase the plan did not mark RUN is not run, and the reason is the plan's;</li>
 *   <li>a step whose required stage the graph has not reached is refused, because
 *       running it would mean acting on a graph it cannot handle;</li>
 *   <li>a step that reaches the network under a run forbidden to acquire is refused
 *       even if the plan somehow said RUN — the invariant is worth two locks.</li>
 * </ul>
 */
public final class PipelineExecutor {

    private final List<PipelineStep> steps = new ArrayList<>();

    public PipelineExecutor with(PipelineStep step) {
        if (step != null) steps.add(step);
        return this;
    }

    /** What running the plan did, phase by phase, in the words a reader is shown. */
    public record Outcome(List<String> lines) {
        public Outcome {
            lines = List.copyOf(lines);
        }

        @Override public String toString() {
            return String.join("\n", lines);
        }
    }

    public Outcome run(PipelineContext context, PipelineState state) throws Exception {
        if (context.run().blocked()) {
            throw new IllegalStateException("This run is blocked:\n"
                    + context.run().explain());
        }
        List<String> lines = new ArrayList<>();
        for (PipelinePhase phase : PipelinePhase.values()) {
            PipelineStep step = stepFor(phase);
            if (step == null) continue;
            PhaseDecision decision = context.run().decision(phase);
            if (!decision.runs()) {
                lines.add(phase.label() + ": " + decision);
                continue;
            }
            if (step.network() && !context.run().request().mayAcquire()) {
                throw new IllegalStateException(phase.label()
                        + " reaches the network, and this run may not acquire");
            }
            if (state.stage().ordinal() < step.requires().ordinal()) {
                throw new IllegalStateException(phase.label() + " needs a "
                        + step.requires() + " and the graph is a " + state.stage());
            }
            if (context.cancelled()) {
                lines.add(phase.label() + ": cancelled before it ran");
                break;
            }
            String said = step.execute(context, state);
            state.reached(step.produces());
            lines.add(phase.label() + ": " + said);
            context.log().message(phase.label() + ": " + said + "\n");
        }
        return new Outcome(lines);
    }

    private PipelineStep stepFor(PipelinePhase phase) {
        for (PipelineStep step : steps) {
            if (step.phase() == phase) return step;
        }
        return null;
    }
}
