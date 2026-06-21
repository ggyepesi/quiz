/**
 * Wikidata-driven quiz generation.
 *
 * <p>Turns an editable <em>project model</em> (classes + fields + where each
 * comes from) into compiled, queryable {@code Quizable} objects, with no
 * per-domain code. The flow:
 *
 * <pre>
 *   model      → an editable schema of classes/fields           ({@code model})
 *   rule       → compiled SPARQL query plan (RuleNode tree)      ({@code rule})
 *   extract    → run the plan → WikidataDynamicObject graphs     ({@code extract})
 *   codegen    → compile a Java Quizable class per model class   ({@code codegen})
 *   generation → chain the stages + run provenance              ({@code generation})
 * </pre>
 *
 * <p>It is driven from the desktop ModelBuilder ({@code workbench}), which edits
 * the model and runs queries through {@code query}. Generated data also reaches
 * the web client via a flat snapshot ({@code extract}).
 */
package wikidata.explore;
