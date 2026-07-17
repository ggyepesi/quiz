/**
 * Numeric value filters on fields.
 *
 * <p>{@code WikidataValueFilter} + {@code WikidataValueFilterOperator} express a
 * comparison on a (numeric) property — e.g. {@code apparentMagnitude <= 6} to
 * keep naked-eye stars. The rule compiler attaches a field's filter to the node
 * that owns the field, and the query builders emit it as a SPARQL FILTER.
 */
package wikidata.explore.filter;
