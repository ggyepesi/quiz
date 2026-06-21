/**
 * Turning the model + extracted data into first-class {@code Quizable} objects.
 *
 * <p>{@code GeneratedQuizableSourceGenerator} emits a Java {@code Quizable} class
 * per model class; {@code GeneratedQuizableRuntimeBuilder} compiles every class
 * in the project (root + children, e.g. Constellation + Star) into a runtime.
 * {@code GeneratedQuizableMapper} then maps each {@code WikidataDynamicObject}
 * onto its compiled class by stamped type.
 *
 * <p>This lets generated data render, facet and quiz through exactly the same
 * machinery as hand-written domains.
 */
package wikidata.explore.codegen;
