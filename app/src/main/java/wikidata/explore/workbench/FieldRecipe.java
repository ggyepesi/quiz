package wikidata.explore.workbench;

import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * A worked, explained configuration example for a field: a goal, a one-line
 * summary, the individual settings each with a plain-language reason, and an
 * action that applies them all to a field. Used by the "Examples" helper so a
 * user learns the (large) config surface by seeing real, applyable recipes.
 */
public final class FieldRecipe {

    /** One setting in the recipe: what it is, the value, and why. */
    public record Step(String setting, String value, String why) {}

    private final String goal;
    private final String summary;
    private final List<Step> steps;
    private final BiPredicate<GeneratedFieldModel, GeneratedProjectModel> applicability;
    private final BiConsumer<GeneratedFieldModel, GeneratedProjectModel> apply;

    public FieldRecipe(
            String goal,
            String summary,
            List<Step> steps,
            BiPredicate<GeneratedFieldModel, GeneratedProjectModel> applicability,
            BiConsumer<GeneratedFieldModel, GeneratedProjectModel> apply) {
        this.goal = goal;
        this.summary = summary;
        this.steps = steps;
        this.applicability = applicability;
        this.apply = apply;
    }

    public String goal() { return goal; }
    public String summary() { return summary; }
    public List<Step> steps() { return steps; }

    /** Whether this worked example's required domain concepts exist here. */
    public boolean appliesTo(GeneratedFieldModel field, GeneratedProjectModel project) {
        return field != null && project != null && applicability != null
                && applicability.test(field, project);
    }

    public void applyTo(GeneratedFieldModel field, GeneratedProjectModel project) {
        if (appliesTo(field, project) && apply != null) {
            apply.accept(field, project);
        }
    }

    @Override
    public String toString() {
        return goal;
    }
}
