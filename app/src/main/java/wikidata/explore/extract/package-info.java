/**
 * Running the plan against endpoints, into data.
 *
 * <p>{@code RuleTreeExtractor} executes a {@code RuleNode} plan over SPARQL
 * (root query, then one query per parent for each child edge) producing
 * {@code WikidataDynamicObject} graphs — identity-by-QID, type-stamped, with
 * dynamic field values. {@code DBpediaEnrichment} then fills DBpedia-sourced
 * fields (Wikipedia infobox data) for those objects, joined by {@code owl:sameAs}
 * to their Wikidata QID.
 *
 * <p>{@code WikidataDynamicObjectJsonStore} persists/loads the v2 flat snapshot
 * (a cycle-safe QID pool) that both "Load saved" and the web client read.
 */
package wikidata.explore.extract;
