package wikidata.explore.generation;

import objectview.Viewable;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.FieldExpectations;
import wikidata.explore.transform.TransformEngine;
import quiz.transform.DynamicViewable;

import java.util.ArrayList;
import java.util.List;

/**
 * What each configured rule accounts for, as instances.
 *
 * <p>A generation's result already states its configuration implicitly: the instances
 * are typed by the generated class, and the card shows the fields that class declares,
 * so the reader reads the config through the data without anything having to say so.
 * A Remap had no equivalent. It reported the size of the pool — a number that barely
 * moves and therefore says nothing — and offered every object in it as a flat list.
 *
 * <p>This is the same reading generalized: one bucket per configured rule, holding the
 * instances that rule accounts for. Read that way, "what did the Remap do" needs no
 * separate change report — the rules ARE the answer, filled in with their data, exactly
 * as classes are for a generation.
 *
 * <p>It is deliberately partial. A rule appears here only if it can say which instances
 * it accounts for; a rule that knows only a count does not get a bucket, because a
 * bucket you cannot open is worse than a log line. Field expectations come first
 * because they already computed the list and threw it away.
 */
public final class RuleEffects {

    private RuleEffects() {}

    /**
     * Whether a rule CHANGES the instances it accounts for or merely names them.
     *
     * <p>The distinction is not cosmetic and it is the one an expectation was designed
     * around: {@code EXPECTED} keeps every record and reports the ones failing it, while
     * {@code REQUIRED} deletes them. A report that called both "affected" would say 56
     * things happened when nothing did.
     */
    public enum Kind {
        /** Named by the rule, and left exactly as they were. */
        FLAGGED,
        /** Altered, created or removed by the rule. */
        CHANGED
    }

    /** Whether wording describes a plan or the completed finalization report. */
    public enum Moment { PLAN, RESULT }

    /**
     * Which step of the run produced an effect.
     *
     * <p>The pipeline is already the one component plan, execution and results share, and
     * its phases are already the steps. Saying which step a bucket came from is what lets
     * the result be read as the plan rather than as a list beside it — and it is what
     * carries the account into the saved run artifact, where phase summaries survive and
     * result tabs do not.
     */
    public enum RunPhase {
        CONSTRUCT("Construct", GenerateDomainPipeline.CONSTRUCT),
        SEMANTIC("Semantic", GenerateDomainPipeline.SEMANTIC),
        FINALIZE("Finalize", GenerateDomainPipeline.FINALIZE);

        private final String label;
        private final String pipelinePhaseId;

        RunPhase(String label, String pipelinePhaseId) {
            this.label = label;
            this.pipelinePhaseId = pipelinePhaseId;
        }

        public String label() { return label; }

        /** The phase this belongs to in a pipeline that HAS one; an operation whose
         *  pipeline is a single step simply has no phase with this id, and the caller
         *  finds nothing to write to rather than inventing a step. */
        public String pipelinePhaseId() { return pipelinePhaseId; }
    }

    /**
     * @param phase     the step of the run that produced this
     * @param rule      the configuration this bucket stands for, in the reader's terms
     * @param detail    what the rule did or found, as one sentence
     * @param kind      whether these instances were changed or merely named
     * @param instances what the rule accounts for
     */
    public record Effect(RunPhase phase, String rule, String detail, Kind kind,
                         List<Viewable> instances) {
        public Effect {
            phase = phase == null ? RunPhase.FINALIZE : phase;
            instances = List.copyOf(instances == null ? List.of() : instances);
        }

        public int size() {
            return instances.size();
        }

        /** The bucket's label, carrying its own count so a tab reads without opening. */
        public String title() {
            return rule + " (" + instances.size() + ")";
        }
    }

    /**
     * The rules that account for something in {@code pool}, worst first.
     *
     * <p>Evaluating rather than applying lets the plan say what a rule would account
     * for. Results use the finalization coverage captured while the action happened;
     * inspecting the post-state would lose records a REQUIRED rule already removed.
     */
    public static List<Effect> of(GeneratedProjectModel model,
                                  List<WikidataDynamicObject> pool) {
        if (model == null || pool == null) {
            return List.of();
        }
        return fromCoverage(FieldExpectations.inspect(model, pool), Moment.PLAN);
    }

    /**
     * Effects from the finalization measurement captured by a generation run.
     * REQUIRED failures are no longer in the post-finalization pool, so this report—not
     * re-inspection of that pool—is the only truthful result-side source.
     */
    public static List<Effect> fromCoverage(
            List<FieldExpectations.FieldCoverage> coverage, Moment moment) {
        List<Effect> effects = new ArrayList<>();
        if (coverage == null) return effects;
        Moment when = moment == null ? Moment.RESULT : moment;
        for (FieldExpectations.FieldCoverage field : coverage) {
            if (field == null || field.missingInstances().isEmpty()) {
                continue;   // a rule that holds has nothing to show
            }
            boolean required = field.level() == FieldExpectation.REQUIRED;
            String action = required
                    ? when == Moment.PLAN ? " will be dropped" : " were dropped"
                    : when == Moment.PLAN
                            ? " do not have it, and will be kept"
                            : " do not have it, and were kept";
            effects.add(new Effect(
                    RunPhase.FINALIZE,
                    field.className() + "." + field.fieldName()
                            + " is " + (required ? "required" : "expected"),
                    field.present() + " of " + field.total() + " have it; "
                            + field.missing() + action,
                    required ? Kind.CHANGED : Kind.FLAGGED,
                    List.copyOf(field.missingInstances())));
        }
        effects.sort(BY_PHASE_THEN_SIZE);
        return effects;
    }

    /**
     * What the self-referential-phantom rule accounted for, from the decisions a reify
     * recorded (#99).
     *
     * <p>Wikidata records a shared award on every recipient, so a work can carry a bare
     * statement for an award that really belongs to a person. The rule drops such an
     * atom only when a WITNESS exists — a real record, in the same category, that
     * references this atom's subject through a reference role — because without one a
     * self-nomination is legitimate: a film IS its own Best Picture nominee.
     *
     * <p>Each decision already carries the atom, the witness and the reason, and the
     * reason is the interesting part: 324 lines of it went to the log on the last real
     * generation and nowhere else, so the records they name could not be looked at. Two
     * buckets, because dropping something and deciding not to are different answers and
     * a reader checking this rule wants both.
     */
    public static List<Effect> fromSelfReference(
            List<TransformEngine.SelfRefFinding> findings, Moment moment) {

        List<Effect> effects = new ArrayList<>();
        if (findings == null || findings.isEmpty()) {
            return effects;
        }
        Moment when = moment == null ? Moment.RESULT : moment;
        List<Viewable> dropped = new ArrayList<>();
        List<Viewable> kept = new ArrayList<>();
        for (TransformEngine.SelfRefFinding finding : findings) {
            if (finding == null || finding.atom() == null) {
                continue;
            }
            (finding.decision() == TransformEngine.SelfRefDecision.DROPPED
                    ? dropped : kept).add(selfReferenceDecision(finding));
        }
        if (!dropped.isEmpty()) {
            effects.add(new Effect(
                    RunPhase.CONSTRUCT,
                    "Self-referential records with a witness",
                    dropped.size() + (when == Moment.PLAN ? " will be" : " were")
                            + " dropped as denormalized copies — each has a real record "
                            + "in the same category referencing its subject",
                    Kind.CHANGED, dropped));
        }
        if (!kept.isEmpty()) {
            effects.add(new Effect(
                    RunPhase.CONSTRUCT,
                    "Self-referential records with no witness",
                    kept.size() + (when == Moment.PLAN ? " are" : " were")
                            + " kept — nothing else claims their award, so each may be a "
                            + "genuine self-nomination rather than a copy",
                    Kind.FLAGGED, kept));
        }
        effects.sort(BY_PHASE_THEN_SIZE);
        return effects;
    }

    /**
     * What owned composition manufactured, as instances (#112).
     *
     * <p>A part is produced per owning instance and identified by ⟨production site, owner
     * id⟩, so composition is meant to find the parts it made last time and reuse them:
     * on a settled domain it should create nothing. A bucket appears only when it did
     * create, because that is the reportable event — and it is the one nobody saw when a
     * Remap manufactured 6863 duplicate Names on every press while every visible number
     * stayed plausible.
     */
    public static List<Effect> fromOwnedComposition(
            GenerationRun.OwnedCompositionAudit audit, Moment moment) {

        if (audit == null || !audit.executed() || audit.created().isEmpty()) {
            return new ArrayList<>();
        }
        Moment when = moment == null ? Moment.RESULT : moment;
        List<Viewable> created = new ArrayList<>(audit.created());
        List<Effect> effects = new ArrayList<>();
        effects.add(new Effect(
                RunPhase.SEMANTIC,
                "Owned parts newly created",
                created.size() + (when == Moment.PLAN ? " will be" : " were")
                        + " manufactured because no existing part was found for their "
                        + "owner and site — on a settled domain this is normally none",
                Kind.CHANGED, created));
        return effects;
    }

    /**
     * Which entities the kind rules restamped.
     *
     * <p>A kind is settled from stored evidence, so a pool already classified should
     * yield none on the next pass. A bucket appears only when something was restamped,
     * because that is the event — and a run reporting the same thousands every time is
     * not a busy domain, it is a pass that cannot see its own previous work.
     */
    public static List<Effect> fromKindClassification(
            GenerationRun.KindClassificationAudit audit, Moment moment) {

        if (audit == null || !audit.executed() || audit.newlyClassified().isEmpty()) {
            return new ArrayList<>();
        }
        Moment when = moment == null ? Moment.RESULT : moment;
        List<Viewable> restamped = new ArrayList<>(audit.newlyClassified());
        List<Effect> effects = new ArrayList<>();
        effects.add(new Effect(
                RunPhase.SEMANTIC,
                "Entity kinds restamped",
                restamped.size() + (when == Moment.PLAN ? " will be" : " were")
                        + " given a kind from stored evidence — on a pool already "
                        + "classified this is normally none",
                Kind.CHANGED, restamped));
        return effects;
    }

    /** Every rule that can name what it accounts for, from what a run recorded. */
    public static List<Effect> fromRun(
            List<FieldExpectations.FieldCoverage> coverage,
            GenerationRun.SelfReferenceAudit audit,
            GenerationRun.OwnedCompositionAudit composition,
            GenerationRun.KindClassificationAudit kinds) {

        List<Effect> effects = new ArrayList<>(fromCoverage(coverage, Moment.RESULT));
        if (audit != null && audit.executed()) {
            effects.addAll(fromSelfReference(audit.findings(), Moment.RESULT));
        }
        effects.addAll(fromOwnedComposition(composition, Moment.RESULT));
        effects.addAll(fromKindClassification(kinds, Moment.RESULT));
        effects.sort(BY_PHASE_THEN_SIZE);
        return effects;
    }

    /** One inspectable decision, rather than an atom detached from why it was kept or
     * dropped. Atom and witness remain references, so both can be opened as cards. */
    private static Viewable selfReferenceDecision(TransformEngine.SelfRefFinding finding) {
        String atomId = finding.atom().getIdentifier();
        DynamicViewable decision = new DynamicViewable(
                "self-reference-" + finding.decision() + "-" + atomId,
                (finding.decision() == TransformEngine.SelfRefDecision.DROPPED
                        ? "Dropped " : "Kept ") + finding.atom().getDisplayName());
        decision.type("Self-reference decision");
        decision.put("Decision", finding.decision().toString());
        decision.put("Atom", finding.atom());
        decision.put("Witness", finding.witness());
        decision.put("Matching fields", finding.identityFields());
        decision.put("Reason", finding.reason());
        return decision;
    }

    /**
     * What to say when no rule reported anything — which is NOT "the run was clean".
     *
     * <p>Field expectations, the self-reference rule, owned composition and kind
     * classification can name the instances they account for. Value restrictions,
     * inverts, projections and canonicalization cannot, and report nothing here at all.
     * The wording lives with the code that knows what it does and does not cover, so a
     * caller cannot phrase its own silence as a clean bill of health — which is exactly
     * what "every declared rule holds" did.
     */
    public static final String NOTHING_REPORTED =
            "No field-expectation gaps, self-reference decisions, newly created owned "
                    + "parts or restamped kinds. Each rule's audit status is reported "
                    + "separately; other rules do not report here yet.";

    /** The reportable effects as one sentence, or {@link #NOTHING_REPORTED} when there
     *  are none — so a caller never has to decide how to describe having found nothing. */
    public static String describe(List<Effect> effects) {
        String summary = summary(effects);
        return summary.isEmpty() ? NOTHING_REPORTED : summary;
    }

    /** The run's own order first, so the result reads as the plan; biggest bucket
     *  within a step, so a reader scanning one line sees the largest hole. */
    private static final java.util.Comparator<Effect> BY_PHASE_THEN_SIZE =
            java.util.Comparator.comparing((Effect e) -> e.phase().ordinal())
                    .thenComparing(e -> -e.size());

    /** The effects of one step, in order. */
    public static List<Effect> inPhase(List<Effect> effects, RunPhase phase) {
        List<Effect> out = new ArrayList<>();
        for (Effect effect : effects == null ? List.<Effect>of() : effects) {
            if (effect != null && effect.phase() == phase) {
                out.add(effect);
            }
        }
        return out;
    }

    /** One sentence for the reportable effects, or empty when it contains none. */
    public static String summary(List<Effect> effects) {
        if (effects == null || effects.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Effect effect : effects) {
            parts.add(effect.rule() + ": " + effect.size()
                    + (effect.kind() == Kind.CHANGED ? " changed" : " flagged"));
        }
        return String.join("; ", parts);
    }
}
