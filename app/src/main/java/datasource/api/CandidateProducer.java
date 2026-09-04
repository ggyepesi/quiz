package datasource.api;

import canonical.Candidate;

import java.util.List;

/**
 * A source operation that ends at normalized candidates.
 *
 * <p>The handoff, and the whole of a provider's obligation: acquire, parse, normalize,
 * and stop. What a candidate identifies, whether several of them are one instance, and
 * how their values combine are model semantics that every provider shares — so a provider
 * that answered them would be answering for all the others.
 *
 * <p>The line is between saying what was produced and saying what becomes of it. A
 * provider may name its own identities and report its own values; it may not import the
 * reduction engine, the compiled plan, or the reducer vocabulary, and
 * {@code DatasourceCannotCanonicalizeTest} fails if one does.
 */
public interface CandidateProducer {

    /** Which provider produced these, so an identity can be qualified by its namespace. */
    String providerId();

    /** What was produced, already normalized and typed. */
    List<Candidate> candidates();
}
