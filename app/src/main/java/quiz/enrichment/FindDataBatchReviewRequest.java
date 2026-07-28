package quiz.enrichment;

import process.ProcessInputRequest;

import java.util.List;

/** ONE review over every member's proposal — a single approve/skip step for the whole
 *  batch, replacing a modal per member. The UI returns the accepted decisions. Only
 *  proposals that actually carry a value/image should be passed. */
public record FindDataBatchReviewRequest(
        String title,
        String prompt,
        List<EnrichmentProposal> proposals)
        implements ProcessInputRequest<BatchReviewDecision> {

    public FindDataBatchReviewRequest {
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
    }

    @Override public Class<BatchReviewDecision> responseType() {
        return BatchReviewDecision.class;
    }
}
