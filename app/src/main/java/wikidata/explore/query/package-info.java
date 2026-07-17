/**
 * A small query abstraction that decouples "what to ask" from "how to run and
 * log it".
 *
 * <p>A {@code Query} (see {@code query.core}) produces a typed result from a
 * {@code QueryContext} (SPARQL/API access + step logging). The subpackages:
 *
 * <ul>
 *   <li>{@code core} — the Query/QueryContext contracts.</li>
 *   <li>{@code logical} — concrete queries (search, discovery, sampling, generation).</li>
 *   <li>{@code template} — SPARQL builders/templates (rule-node + the SparqlQueries catalogue).</li>
 *   <li>{@code result} — typed result types (tables, discovered properties, objects).</li>
 *   <li>{@code workflow} / {@code log} — execution + the query-log model.</li>
 *   <li>{@code swing} — running queries off the EDT and wiring them to UI.</li>
 * </ul>
 */
package wikidata.explore.query;
