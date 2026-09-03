package wikidata.explore.generation;

import datasource.api.BindingScope;
import datasource.api.SourceExecutionPlan;
import datasource.api.acquisition.PopulationRequest;
import wikidata.WikidataIds;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleNode;

/** Applies a resolved class-population step to the existing extraction rule. */
public final class PopulationSourceExecution {
    private PopulationSourceExecution() { }

    public static RuleNode apply(RuleNode root, SourceExecutionPlan.Step step) {
        if (root == null) throw new IllegalArgumentException("Root rule is required");
        if (step == null || step.target().scope() != BindingScope.CLASS_POPULATION) {
            throw new IllegalArgumentException("A class-population plan step is required");
        }
        PopulationRequest selection = step.prepared().configuration(
                PopulationRequest.class);
        if (selection == null) {
            throw new IllegalArgumentException("Datasource operation "
                    + step.recipe().providerId() + "." + step.recipe().operationId()
                    + " cannot describe a class population to generation");
        }
        // The NAMESPACE, not the provider: this boundary can adapt any source whose
        // identifiers are Wikidata items, whoever fetched them.
        if (!datasource.EntityRef.WIKIDATA.equals(selection.namespace())) {
            throw new IllegalArgumentException("Generation does not yet adapt population '"
                    + selection.namespace() + "' into its Wikidata extraction boundary");
        }
        if (selection.includeDescendants()) {
            throw new IllegalArgumentException(
                    "Subclass-closure population is not yet supported by generation");
        }

        root.sourceQid("");
        root.additionalSourceQids().clear();
        if (selection.kind() == PopulationRequest.Kind.RELATION) {
            String pid = selection.relationId().toUpperCase();
            if (!WikidataIds.isPid(pid)) {
                throw new IllegalArgumentException("Invalid Wikidata population property: " + pid);
            }
            root.propertyPid(pid);
            root.direction(RuleDirection.ITEM_TO_ROOT);
            root.sourceQid(selection.values().getFirst().id());
            selection.values().stream().skip(1)
                    .map(datasource.EntityRef::id).forEach(root::addAdditionalSourceQid);
            // includedQids remain: with a relation they are an independent restriction
            // over the selected population, not the population recipe itself.
        } else {
            root.includedQids().clear();
            selection.values().stream().map(datasource.EntityRef::id)
                    .forEach(root::addIncludedQid);
        }
        return root;
    }
}
