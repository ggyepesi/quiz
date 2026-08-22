/**
 * What a domain IS, apart from anything that shows one.
 *
 * <p>{@link domain.DomainModel} is the contract every backing implements and every consumer
 * reads: a Wikidata snapshot, a hand-written {@code Viewable} domain, a curated overlay, a
 * group-scoped projection. It lived in {@code quiz.transform.ui} — a Swing package — so
 * {@code WikidataDynamicObjectJsonStore} and {@code SnapshotFieldGraph} imported the transform
 * app's user interface in order to describe a saved snapshot, and the two packages depended on
 * each other. Nothing about the contract was ever about the workbench; only its address was.
 *
 * <p>The one non-obvious dependency here is deliberate: {@code wikipediaCategoryRule} and
 * {@code entityKindRule} name types from {@code wikidata.explore.model}, because they carry
 * what the PRODUCING MODEL declared and in this application that model is Wikidata-shaped. A
 * backing with nothing to say returns null, which is what the hand-written domains do. That is
 * the whole of it: {@code DomainContractTest} allows those two and nothing else, so this stays
 * a description of a domain rather than drifting back into being part of an app.
 *
 * <p>Swing belongs on the other side of this line. A domain that can show its own schema says
 * so through {@code quiz.transform.ui.SchemaView}, which is a capability of the workbench, not
 * of the domain.
 */
package domain;
