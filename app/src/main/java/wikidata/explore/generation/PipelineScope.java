package wikidata.explore.generation;

/**
 * Which population a run may operate on.
 *
 * <p>One value, one way of being scoped — the shape {@link wikidata.explore.model.EntityBound}
 * settled for bounding an entity end, and for the same reason: two independent fields
 * would let a run name a class AND claim the whole domain, and something would have to
 * rank them.
 *
 * <p>Scope owns population bounds and nothing else. How much of that population is read
 * is {@link PipelineLimits}; whether facts may be fetched for it is {@link Acquisition}.
 * A class scope follows the production chain required to MAKE that class, which is why
 * it names a class rather than a set of classes: an aggregate, a statement class or an
 * owned class cannot be produced as an unrelated root, and the chain is what says so.
 */
public record PipelineScope(Kind kind, String className) {

    public enum Kind {
        /** Every class the model can produce. */
        WHOLE_DOMAIN,
        /** One class and the production chain that makes it. */
        CLASS_PRODUCTION_CHAIN,
        /** Whatever is already in the input graph — no discovery. */
        EXISTING_POPULATION
    }

    public PipelineScope {
        if (kind == null) throw new IllegalArgumentException("A scope needs a kind");
        className = className == null ? "" : className.trim();
        if (kind == Kind.CLASS_PRODUCTION_CHAIN && className.isEmpty()) {
            throw new IllegalArgumentException("A class scope names its class");
        }
        if (kind != Kind.CLASS_PRODUCTION_CHAIN && !className.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only a class scope names a class; " + kind + " named " + className);
        }
    }

    public static PipelineScope wholeDomain() {
        return new PipelineScope(Kind.WHOLE_DOMAIN, "");
    }

    public static PipelineScope productionChainOf(String className) {
        return new PipelineScope(Kind.CLASS_PRODUCTION_CHAIN, className);
    }

    public static PipelineScope existingPopulation() {
        return new PipelineScope(Kind.EXISTING_POPULATION, "");
    }

    /** Whether this run may look for members it does not already have. */
    public boolean discovers() {
        return kind != Kind.EXISTING_POPULATION;
    }

    @Override public String toString() {
        return kind == Kind.CLASS_PRODUCTION_CHAIN
                ? "the production chain of " + className
                : kind == Kind.WHOLE_DOMAIN ? "the whole domain"
                        : "the existing population";
    }
}
