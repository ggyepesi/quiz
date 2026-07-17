/**
 * Concrete, high-level queries — each a {@code Query} the UI can run and log:
 * class/type search ({@code ClassSearchQuery}), class property discovery,
 * subtype discovery ({@code DiscoverSubtypesQuery}), DBpedia property discovery
 * ({@code DiscoverDBpediaPropertiesQuery}), sampling, and instance generation
 * ({@code GenerateInstancesQuery}). They compose SPARQL from {@code query.template}
 * and return {@code query.result} types.
 */
package wikidata.explore.query.logical;
