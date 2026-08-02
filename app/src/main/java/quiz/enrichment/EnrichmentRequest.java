package quiz.enrichment;

import java.util.List;

/** Context supplied to enrichment providers for one field of one subject. */
public record EnrichmentRequest(
        EnrichmentProposal.Subject subject,
        String targetField,
        boolean collection,
        List<EnrichmentProposal.SourceRef> sources,
        objectview.field.FieldRef targetSchema,
        Object currentValue) {

    public EnrichmentRequest(EnrichmentProposal.Subject subject, String targetField,
                             boolean collection,
                             List<EnrichmentProposal.SourceRef> sources) {
        this(subject, targetField, collection, sources, null);
    }

    public EnrichmentRequest(EnrichmentProposal.Subject subject, String targetField,
                             boolean collection,
                             List<EnrichmentProposal.SourceRef> sources,
                             objectview.field.FieldRef targetSchema) {
        this(subject, targetField, collection, sources, targetSchema, null);
    }

    public EnrichmentRequest {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
