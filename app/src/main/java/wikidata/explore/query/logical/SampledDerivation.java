package wikidata.explore.query.logical;

import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.SemanticConvergence;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.transform.ModelAggregates;
import work.QueryContext;

import java.util.List;

/**
 * What generation does to a pool after acquiring it — run over a sampled pool too.
 *
 * <p>A sample exists to show what generation would produce, so acquiring the records is
 * only half of it. Roles are stamped, the declarations those roles read are loaded, kinds
 * are settled from that evidence, parts are composed, and groups are reduced. Skip them
 * and the sample shows entities that generation would have turned into something else: an
 * Oscars Nominee that never became a Person, with the P31 field that would have said so
 * never loaded.
 *
 * <p>Every sample route runs this, which is why it is here rather than in one of them. It
 * was written into the derived-class route only, on the reasoning that a class produced
 * by reduction is the one that needs deriving — but what needs deriving is whatever is IN
 * the pool, and every route puts entities there.
 *
 * <p>In generation's order and not the caller's: parts before groups, because a part must
 * exist to be grouped. That order is settled once, in the pipeline; this follows it.
 */
final class SampledDerivation {

    private SampledDerivation() { }

    static void apply(GeneratedProjectModel snapshot, CompiledProjectModel compiled,
            List<WikidataDynamicObject> pool, QueryContext context, GenerationLog log) {
        if (snapshot == null || pool == null || pool.isEmpty()) return;
        SemanticConvergence.Result converged = SemanticConvergence.apply(
                snapshot, pool, WikidataAccess.api(context), log, List.of(), null);
        log.message("Derived " + converged.ownedCreated() + " owned part(s), "
                + converged.classifiedKinds() + " kind(s), "
                + converged.loadedFields() + " field value(s).\n");
        if (compiled != null) ModelAggregates.apply(compiled, pool, log);
    }
}
