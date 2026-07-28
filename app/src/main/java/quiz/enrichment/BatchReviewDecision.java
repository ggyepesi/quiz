package quiz.enrichment;

import java.util.List;

/** The outcome of one batch review: the decisions the user accepted (one per approved
 *  member). Wrapping the list keeps a concrete {@code responseType()} for the process
 *  input pause. */
public record BatchReviewDecision(List<EnrichmentDecision> accepted) {

    public BatchReviewDecision {
        accepted = accepted == null ? List.of() : List.copyOf(accepted);
    }
}
