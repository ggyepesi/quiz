package quiz.enrichment;

import java.util.List;

/** Context supplied to enrichment providers for one field of one subject. */
public record EnrichmentRequest(
        EnrichmentProposal.Subject subject,
        String targetField,
        boolean collection,
        List<EnrichmentProposal.SourceRef> sources) {

    public EnrichmentRequest {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
