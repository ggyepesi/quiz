/**
 * Turning the model + extracted data into first-class {@code Viewable} objects.
 *
 * <p>{@code GeneratedViewableSourceGenerator} emits a Java {@code Viewable} class
 * per model class; {@code GeneratedViewableRuntimeBuilder} compiles every class
 * in the project (root + children, e.g. Constellation + Star) into a runtime.
 * {@code GeneratedViewableMapper} then maps each {@code WikidataDynamicObject}
 * onto its compiled class by stamped type.
 *
 * <p>This lets generated data render, facet and quiz through exactly the same
 * machinery as hand-written domains.
 */
package wikidata.explore.codegen;
