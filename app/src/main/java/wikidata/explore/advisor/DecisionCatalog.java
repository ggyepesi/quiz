package wikidata.explore.advisor;

import wikidata.WikidataIds;

import wikidata.explore.model.MembershipFields;
import wikidata.explore.model.MembershipPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The model-building "teaching script" as data: the ordered structural decisions a
 * user walks when defining a domain — membership → target set → intrinsic fields →
 * type structure → qualifiers → denormalization → presentation. Each carries its
 * info-tool and a hint. {@link #evaluate} reports, for a class, which apply and
 * which are already resolved — the guide panel renders that as resolved steps +
 * open branches.
 */
public final class DecisionCatalog {

    private DecisionCatalog() {}

    /** A decision paired with whether it's resolved for the evaluated context. */
    public record Evaluated(StructuralDecision decision, boolean resolved) {}

    private static final List<StructuralDecision> DECISIONS = List.of(
            new StructuralDecision(
                    "membership",
                    "How are these entities gathered — by type, a curated list, or a relation?",
                    "Explorer tools: search a seed entity and Explore its relations",
                    "Name the domain, find the seed entity, then pick the relation.",
                    ctx -> true,
                    ctx -> ctx.pattern() != MembershipPattern.UNCONFIGURED),

            new StructuralDecision(
                    "targets",
                    "Pin the relation's target set (one entity, or a set?).",
                    "\"From parts…\" (e.g. Q19020 → P527), or add target QIDs",
                    "Discover the targets from a parent's parts instead of pasting QIDs.",
                    ctx -> {
                        String r = ctx.relationPid();
                        return WikidataIds.isPid(r) && !r.equals("P31");
                    },
                    ctx -> ctx.targetCount() >= 1),

            new StructuralDecision(
                    "intrinsic-fields",
                    "Capture the intrinsic grouping fields (target + type).",
                    "Auto on Apply (MembershipFields), or add the fields manually",
                    "target + type let you group/subclass later — add them now.",
                    ctx -> MembershipFields.appliesType(ctx.clazz())
                            || MembershipFields.appliesTarget(ctx.clazz()),
                    ctx -> (!MembershipFields.appliesType(ctx.clazz()) || ctx.hasTypeField())
                            && (!MembershipFields.appliesTarget(ctx.clazz()) || ctx.hasTargetField())),

            new StructuralDecision(
                    "type-structure",
                    "Is the member type uniform or mixed? → subclass by type, or facet by type.",
                    "\"By type…\"",
                    "Run By type…; mixed-per-target → facet, clean-per-instance → subclass.",
                    DecisionContext::relational,
                    ctx -> ctx.hasSubclass()),

            new StructuralDecision(
                    "qualifiers",
                    "Does the relation's statement carry qualifiers (year, for-work, roles)?",
                    "\"Qualifiers…\"",
                    "Load entity/year qualifiers as fields.",
                    DecisionContext::relational,
                    DecisionContext::qualifiersLoaded),

            new StructuralDecision(
                    "denormalization",
                    "Is the relation denormalized onto both endpoints? → reify into events + dedup.",
                    "\"Qualifiers…\" proposes a canonical reify",
                    "A member-kind entity qualifier (e.g. nominee) signals denormalization → reify.",
                    DecisionContext::relational,
                    DecisionContext::reified));

    public static List<StructuralDecision> all() {
        return DECISIONS;
    }

    /** Decisions that apply to {@code ctx}, in script order, each with its status. */
    public static List<Evaluated> evaluate(DecisionContext ctx) {
        List<Evaluated> out = new ArrayList<>();
        for (StructuralDecision d : DECISIONS) {
            if (d.appliesTo(ctx)) {
                out.add(new Evaluated(d, d.isResolved(ctx)));
            }
        }
        return out;
    }

    /** The next unresolved applicable decision — the single "what to do next". */
    public static StructuralDecision next(DecisionContext ctx) {
        for (StructuralDecision d : DECISIONS) {
            if (d.appliesTo(ctx) && !d.isResolved(ctx)) {
                return d;
            }
        }
        return null;
    }

    /** Convenience: count of applicable decisions still open. */
    public static long openCount(DecisionContext ctx) {
        Predicate<StructuralDecision> open =
                d -> d.appliesTo(ctx) && !d.isResolved(ctx);
        return DECISIONS.stream().filter(open).count();
    }
}
