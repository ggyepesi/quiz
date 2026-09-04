package canonical;

import java.util.Collection;

/** The single datasource-independent entrance to keying and field reduction. */
public final class CanonicalizationEngine {
    private CanonicalizationEngine() { }

    public static KeyedReduction.Result canonicalize(
            CanonicalizationPlan plan,
            Collection<? extends Candidate> candidates,
            StableForm stable) {
        return KeyedReduction.reduce(plan, candidates, stable);
    }
}
