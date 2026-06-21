/**
 * SPARQL (and API) builders/templates that turn rule nodes and parameters into
 * runnable query strings.
 *
 * <p>Subpackages: {@code rule} (the rule-node query builders —
 * {@code RuleNodeQueryBuilder} and friends — that render a RuleNode plan into
 * root and per-parent child SPARQL, incl. the grouped/aggregated
 * limit-distinct-entities pattern), {@code sparql} (the {@code SparqlQueries}
 * catalogue of standalone queries: sampling, discovery, DBpedia), {@code api}
 * and {@code wikiproject}.
 */
package wikidata.explore.query.template;
