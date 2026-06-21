/**
 * The compiled query plan.
 *
 * <p>{@code RuleTreeCompiler} turns the editable {@code model} into a
 * {@code RuleNode} tree: membership (instance-of, possibly multi-QID), included
 * fields, value filters, label config, sort, and child edges (each with its own
 * direction, per-parent limit, membership and sort). This is the structure the
 * extractor walks.
 *
 * <p>{@code RuleTreeExplainer} renders a node tree as a plain-language plan (the
 * readable "Show rule tree"); {@code RuleTreeSerializer} / {@code RuleTreeConfig}
 * (de)serialize it. DBpedia-sourced fields are intentionally NOT compiled here —
 * they're filled by a separate enrichment (see {@code extract}).
 */
package wikidata.explore.rule;
