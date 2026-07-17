/**
 * The editable project model — the single source of truth the UI edits and
 * everything downstream compiles from.
 *
 * <p>{@code GeneratedProjectModel} holds {@code GeneratedClassModel}s, each with
 * {@code GeneratedFieldModel} fields and a {@code FieldSourceMapping} describing
 * where the value comes from: Wikidata type/property, direction, limit, label
 * config, value filter, {@code EdgeMembershipMode}, sort, multi-QID membership,
 * and the {@code FieldSourceType} (Wikidata SPARQL or DBpedia infobox).
 *
 * <p>{@code GeneratedProjectModelStore} (de)serializes it; the generation depth
 * is stored with it. This package is plain data — no query/IO logic.
 */
package wikidata.explore.model;
