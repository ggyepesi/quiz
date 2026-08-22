package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import work.Query;

/**
 * Pluggable discovery source. Implementations may use linked data, an originating
 * record page, an API, or a search service, but all return the same review model.
 */
public interface EnrichmentProvider {

    String name();

    boolean supports(EnrichmentRequest request);

    Query<EnrichmentProposal> discover(EnrichmentRequest request);
}
