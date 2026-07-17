package quiz.curation;

import java.util.List;

/**
 * A producer of {@link Correction}s for the overlay. The one seam every kind of
 * fix flows through: manual curation (a sidecar file), a derivation rule, or an
 * external fetch (Wikipedia / DBpedia / …). {@link Corrections#apply} merges them
 * with a single precedence rule.
 */
@FunctionalInterface
public interface CorrectionSource {

    List<Correction> corrections();
}
