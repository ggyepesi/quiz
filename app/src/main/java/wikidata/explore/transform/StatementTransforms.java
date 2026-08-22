package wikidata.explore.transform;

import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The pure transform sequence that turns a downloaded pool into the served one (#97).
 *
 * <p>Generate and Remap are the same work with one difference: whether the data was just
 * downloaded. They each carried their own copy of this sequence, and the copies drifted
 * — which is how a year projection came to be wired into one path and not the other
 * (#93), and how a value restriction or an invert declared on a reified class applied on
 * Remap but not on Generate: Generate ran those stages over the registry, which does not
 * contain the records the reify had just created. Both are now this method, so a stage
 * added here is a stage both paths run.
 *
 * <p>Everything here is offline and deterministic. The single input that is not — the
 * companion-match sets, which Generate fetches and Remap replays from cache — arrives as
 * a function of the reified records, because the query needs them and they do not exist
 * until the first stage has run.
 */
public final class StatementTransforms {

    private StatementTransforms() {}

    /**
     * @param reified            the statement records this pass created
     * @param demoted            duplicate records dropped during canonicalization
     * @param companionSets      the sets used for companion matching, for a later Remap
     * @param projectedFields    how many field values a projection filled
     * @param stampedReferents   referents given the class their field declares
     * @param unstampedInternals internal load-type stamps removed from served objects
     * @param strippedFields     internal {@code __} fields removed from served objects
     */
    public record Result(
            List<WikidataDynamicObject> reified,
            Set<WikidataDynamicObject> demoted,
            Map<String, Set<List<String>>> companionSets,
            int projectedFields,
            int stampedReferents,
            int unstampedInternals,
            int strippedFields) {}

    /**
     * Runs the sequence over {@code pool}, in place. On return the pool holds the served
     * set: the reified records added, the demoted duplicates removed, and no internal
     * plumbing left on anything.
     */
    public static Result apply(
            GeneratedProjectModel project,
            CompiledProjectModel compiled,
            List<WikidataDynamicObject> pool,
            Function<List<WikidataDynamicObject>, Map<String, Set<List<String>>>>
                    companionSetsFor,
            GenerationLog log) {

        Set<WikidataDynamicObject> demoted =
                Collections.newSetFromMap(new IdentityHashMap<>());

        // Reify appends the new records to the pool, so every stage below sees them —
        // which is the whole point: a restriction or an invert declared on a statement
        // class has to reach the records that class produces.
        List<WikidataDynamicObject> reified =
                ModelStatementReifications.reify(compiled, pool, log, demoted);

        // Per-field allowedQids: the query layer does not enforce them, so e.g. the
        // auto-injected `target` field restricted to the membership's Oscar categories
        // drops the Grammy categories that share P1411.
        FieldValueRestrictions.apply(compiled, pool);

        // Derived (production = INVERT) fields: reverse references already in the pool
        // (Category.nominees is the reverse of Oscarnominations.categories). No query.
        ModelInverts.apply(compiled, pool, log);

        // A DATE field overlaid from a referent's date (Nomination.year <-
        // YEAR(edition.date)). Over the reified records too, so a Nomination sees the
        // generated Edition by qid.
        int projectedFields = ModelYearProjections.apply(compiled, pool, log);

        // Companion-match booleans (Nomination.won). The sets are the one input that
        // costs a request; Remap replays the ones Generate cached.
        Map<String, Set<List<String>>> companionSets =
                companionSetsFor == null ? Map.of() : companionSetsFor.apply(reified);
        CompanionMatch.applyWithSets(compiled, reified, companionSets, log);

        // A demoted duplicate leaves the served pool, not merely its type stamp: the
        // served set is "every pooled object of this type", so an untyped-but-pooled
        // duplicate would still be saved.
        pool.removeIf(demoted::contains);

        int[] cleaned = removeInternalPlumbing(pool);

        // AFTER the internal un-stamp, so a discovered subject that is also a referent
        // is given its real class rather than keeping the internal one.
        int stampedReferents = ReferentClassStamp.apply(project, reified);

        if (log != null) {
            if (cleaned[0] > 0 || cleaned[1] > 0) {
                log.message("Un-stamped " + cleaned[0] + " discovered subject(s), "
                        + "stripped " + cleaned[1] + " internal statement field(s) — "
                        + "kept as referents, not served.\n");
            }
            if (stampedReferents > 0) {
                log.message("Stamped " + stampedReferents
                        + " referent(s) with their declared class.\n");
            }
        }

        return new Result(reified, demoted, companionSets, projectedFields,
                stampedReferents, cleaned[0], cleaned[1]);
    }

    /**
     * The subset of the sequence that is safe to re-run on a pool whose records have
     * ALREADY been reified — an enrich, or a Remap on a snapshot loaded after a restart.
     *
     * <p>Reify and companion-match are excluded because they are not idempotent: reify
     * would build a second copy of every record, and companion-match needs sets that
     * only a request can produce. What remains is overwrite-only, which is why a
     * projection declared after a snapshot was saved still fills on those paths.
     *
     * <p>It is one method because these three sites had three different subsets: the
     * display-only remap ran the projection alone, enrich ran all three, and preview ran
     * the invert alone — so a value restriction declared on a class applied or did not
     * depending on which button was pressed.
     *
     * @return how many field values a projection filled
     */
    public static int applyIdempotent(
            CompiledProjectModel compiled,
            List<WikidataDynamicObject> pool,
            GenerationLog log) {

        FieldValueRestrictions.apply(compiled, pool);
        ModelInverts.apply(compiled, pool, log);
        return ModelYearProjections.apply(compiled, pool, log);
    }

    /** Editable-model overload, for a path that has no compiled model to hand. */
    public static int applyIdempotent(
            GeneratedProjectModel project,
            List<WikidataDynamicObject> pool,
            GenerationLog log) {

        FieldValueRestrictions.apply(project, pool);
        ModelInverts.apply(project, pool, log);
        return ModelYearProjections.apply(project, pool, log);
    }

    /**
     * Strips what only the transform stages needed: the internal load-type stamp a
     * discovered subject carries, and the {@code __}-prefixed statement lists the reify
     * has already promoted to top-level records. A referent that kept its raw statement
     * list would render as a nominee showing {@code __Nomination}.
     *
     * @return {@code [unstamped, strippedFields]}
     */
    private static int[] removeInternalPlumbing(List<WikidataDynamicObject> pool) {
        int unstamped = 0;
        int stripped = 0;
        for (WikidataDynamicObject object : pool) {
            if (object == null) {
                continue;
            }
            // RETRACT, not clear: type(null) leaves the internal name behind as a
            // membership, and the save path reads that back as the object's class.
            for (String internal : object.directClassNames().stream()
                    .filter(WikidataDynamicObject::isInternalClassName).toList()) {
                object.removeClass(internal);
                unstamped++;
            }
            List<String> internalFields = new ArrayList<>();
            for (String field : object.dynamicFields().keySet()) {
                if (field != null && field.startsWith("__")) {
                    internalFields.add(field);
                }
            }
            for (String field : internalFields) {
                object.remove(field);
                stripped++;
            }
        }
        return new int[] {unstamped, stripped};
    }
}
