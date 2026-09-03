package canonical;

/**
 * How two values are compared for being the same thing.
 *
 * <p>A capability the application binds, not a rule this package writes. Reduction needs
 * to know when candidates agree; what makes a date, a reference or a collection equal is
 * knowledge about values, and it already has an owner — {@code StableIdentity}, which
 * every existing grouping path uses. Declaring the need here and binding the answer there
 * keeps ONE definition of equality: a comparison function passed per call site would
 * become two the first time two callers passed different ones.
 */
@FunctionalInterface
public interface StableForm {

    /** A process-independent encoding: equal encodings mean the same value. */
    String of(Object value);
}
