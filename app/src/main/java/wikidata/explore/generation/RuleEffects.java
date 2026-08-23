package wikidata.explore.generation;

import objectview.Viewable;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.FieldExpectations;

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

    /**
     * @param rule      the configuration this bucket stands for, in the reader's terms
     * @param detail    what the rule did or found, as one sentence
     * @param kind      whether these instances were changed or merely named
     * @param instances what the rule accounts for
     */
    public record Effect(String rule, String detail, Kind kind, List<Viewable> instances) {
        public Effect {
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
     * <p>Evaluating rather than applying is what lets the same call answer before a run
     * and after it: the plan says what a rule would account for, the result says what it
     * did, and the two being the same shape is what makes them comparable.
     */
    public static List<Effect> of(GeneratedProjectModel model,
                                  List<WikidataDynamicObject> pool) {
        List<Effect> effects = new ArrayList<>();
        if (model == null || pool == null) {
            return effects;
        }
        for (FieldExpectations.FieldCoverage coverage
                : FieldExpectations.inspect(model, pool)) {
            if (coverage == null || coverage.missingInstances().isEmpty()) {
                continue;   // a rule that holds has nothing to show
            }
            boolean required = coverage.level() == FieldExpectation.REQUIRED;
            effects.add(new Effect(
                    coverage.className() + "." + coverage.fieldName()
                            + " is " + (required ? "required" : "expected"),
                    coverage.present() + " of " + coverage.total() + " have it; "
                            + coverage.missing()
                            + (required ? " will be dropped" : " do not, and are kept"),
                    required ? Kind.CHANGED : Kind.FLAGGED,
                    List.copyOf(coverage.missingInstances())));
        }
        effects.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return effects;
    }

    /** One sentence for the whole set, or the empty string when every rule holds. */
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
