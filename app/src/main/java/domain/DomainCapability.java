package domain;

/**
 * Something a domain may additionally be able to do — show its schema, hold curation,
 * apply a merge, promote a rule into the model it was generated from. Optional by nature:
 * a hand-written domain has no curation sidecar and no model to promote into.
 *
 * <p>Asking used to mean {@code instanceof}, which reads fine at one call site and fails at
 * the next: a wrapper that does not itself implement the capability hides its base's, so
 * every wrapper had to re-implement each capability as a forwarding shim, and every
 * capability added later needed another shim in every wrapper. Twenty-two of those probes
 * had accumulated. {@link DomainModel#capability} asks once, and
 * {@link DelegatingDomainModel} answers for a whole chain of wrappers.
 *
 * <p>The marker is what makes that rule checkable: {@code DomainCapabilityTest} enumerates
 * the implementations of this interface and fails on an {@code instanceof} against any of
 * them.
 */
public interface DomainCapability { }
