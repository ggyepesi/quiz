/**
 * The desktop ModelBuilder UI.
 *
 * <p>{@code ModelBuilderFrame} hosts the workbench: edit the project model
 * (classes/fields via {@code ClassSourcePanel} / {@code FieldSourcePanel}),
 * discover types/properties and sample, generate class instances, view the rule tree
 * (readable plan), and Save everything (model + rule tree + snapshot). It also
 * carries the UX helpers built around the large config surface: subtype &amp;
 * DBpedia-property discovery, and the explained {@code FieldRecipes}
 * ("Examples").
 *
 * <p>Panels run queries via {@code query.swing} and edit the {@code model}
 * directly; they never query endpoints themselves.
 */
package wikidata.explore.workbench;
