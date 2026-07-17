package quiz.transform.app;

/**
 * One field of a {@link ProductClass} — the compiled, typed view of a declared
 * model field. Unlike a raw instance field, its shape ({@code reference} /
 * {@code collection}) and display {@code typeLabel} come from the model, not a
 * sample; {@code nestedClassName} names the referenced {@link ProductClass} when
 * this is a reference to a modeled class.
 *
 * <p>{@code structural} marks plumbing/provenance (a statement class's reify
 * {@code source} back-ref, the auto-seeded {@code wikidata} link) — carried as a
 * field so "hide this" is one property, honoured at every level (top-level and
 * nested) rather than re-derived per surface.
 */
public record ProductField(String name,
                           String typeLabel,
                           boolean reference,
                           boolean collection,
                           String nestedClassName,
                           boolean structural) {

    /** A structural (hidden) marker field — plumbing the pickers skip. */
    public static ProductField structural(String name) {
        return new ProductField(name, "", false, false, null, true);
    }
}
