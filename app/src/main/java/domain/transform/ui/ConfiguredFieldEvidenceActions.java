package domain.transform.ui;

import domain.datasource.api.SourceBinding;
import domain.datasource.wikipedia.WikipediaEvidenceRecipes;
import domain.workbench.ui.WorkbenchActionResultNavigator;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** The configured recipes for one field, each opening the existing evidence review action. */
public final class ConfiguredFieldEvidenceActions {
    private final WorkbenchActionResultNavigator navigator;
    private final WorkbenchActionResultNavigator.ActionGroup group;
    private final JLabel overview = new JLabel();
    private final Function<SourceBinding, JComponent> detailFor;
    private Target shown;
    /** Each recipe's detail, kept while the same field is shown, so rebuilding the list
     *  after an Apply leaves a recipe's opened panel (and any run it started) in place. */
    private final java.util.Map<SourceBinding, JPanel> details = new java.util.HashMap<>();
    private final java.util.Map<SourceBinding, WorkbenchActionResultNavigator.Action> actions =
            new java.util.HashMap<>();

    private record Target(Object scope, String field, List<SourceBinding> recipes, String unavailable) { }

    public ConfiguredFieldEvidenceActions(WorkbenchActionResultNavigator navigator,
            WorkbenchActionResultNavigator.Source source, String id,
            Function<SourceBinding, JComponent> detailFor) {
        this.navigator = navigator;
        this.detailFor = detailFor;
        var detail = new JPanel(new BorderLayout());
        detail.add(overview, BorderLayout.NORTH);
        group = navigator.addActionGroup(source, id, "Configured field evidence", detail, null);
    }

    public void show(Object scope, String field, List<SourceBinding> recipes, String unavailable) {
        var target = new Target(scope, field, List.copyOf(recipes), unavailable);
        if (target.equals(shown)) return;
        if (shown == null || !shown.field().equals(field) || !Objects.equals(shown.scope(), scope)) {
            details.clear();
        }
        details.keySet().retainAll(recipes);
        shown = target;
        actions.clear();
        navigator.clearActions(group);
        if (recipes.isEmpty()) {
            overview.setText(unavailable);
            return;
        }
        overview.setText("Choose a configured evidence recipe for " + field + ".");
        for (int index = 0; index < recipes.size(); index++) {
            SourceBinding recipe = recipes.get(index);
            String label = WikipediaEvidenceRecipes.describe(recipe);
            var detail = details.computeIfAbsent(recipe, ignored -> new JPanel(new BorderLayout()));
            actions.put(recipe, navigator.addAction(group, "recipe-" + index, label, detail, () -> {
                if (detail.getComponentCount() > 0) return;
                var panel = detailFor.apply(recipe);
                if (panel == null) {
                    detail.add(new JLabel("Select one or more instances for this recipe."), BorderLayout.NORTH);
                } else {
                    detail.add(panel, BorderLayout.CENTER);
                }
                detail.revalidate();
                detail.repaint();
            }));
        }
    }

    /** Shows the recipe's action again after the list was rebuilt; false when it is gone. */
    public boolean reveal(SourceBinding recipe) {
        var action = actions.get(recipe);
        if (action == null) return false;
        navigator.selectAction(action);
        return true;
    }
}
