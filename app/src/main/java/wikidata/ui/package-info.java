/**
 * Showing a Wikidata identifier to a person: a QID or PID as a clickable link, and a resolved
 * identity as a card-header chip.
 *
 * <p>Both are pure renderers — they depend on nothing in this application — and they were in
 * the shared {@code workbench} package, which needs a query runner to do its job. That put one
 * import from a query result panel reaching for a chip on the wrong side of a cycle. Here they
 * are below everything that shows a Wikidata id, which is the only thing they were ever for.
 */
package wikidata.ui;
