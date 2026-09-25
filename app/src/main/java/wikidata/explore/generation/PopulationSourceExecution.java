package wikidata.explore.generation;

import datasource.api.BindingScope;
import datasource.api.SourceExecutionPlan;
import datasource.api.acquisition.PopulationRequest;
import wikidata.WikidataIds;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.ImportedPopulationInputs;
import wikidata.explore.rule.RuleNode;

import java.util.Collection;
import java.util.List;

/** Applies a resolved class-population step to the existing extraction rule. */
public final class PopulationSourceExecution {
    private PopulationSourceExecution() { }

    /** One answer to how a class receives its root instances in every run scope. */
    public record Resolution(
            Kind kind, List<String> qids, List<String> selectionNames, String reason) {
        public enum Kind { LOCAL_SOURCE, IMPORTED_POPULATION, NONE }

        public Resolution {
            qids = List.copyOf(qids == null ? List.of() : qids);
            selectionNames = List.copyOf(
                    selectionNames == null ? List.of() : selectionNames);
            reason = reason == null ? "" : reason;
        }

        public boolean available() { return kind != Kind.NONE; }
        public boolean importedPopulation() { return kind == Kind.IMPORTED_POPULATION; }

        public RuleNode apply(RuleNode root, SourceExecutionPlan.Step step) {
            return switch (kind) {
                // Compatibility/offline callers can supply an already compiled rule
                // without a datasource plan; in that case there is nothing to adapt.
                case LOCAL_SOURCE -> step == null
                        ? root : PopulationSourceExecution.apply(root, step);
                case IMPORTED_POPULATION -> PopulationSourceExecution.applyExact(root, qids);
                case NONE -> throw new IllegalStateException(
                        reason.isBlank() ? "No class population input" : reason);
            };
        }
    }

    /**
     * Resolves the population boundary before either a domain run or a class preview.
     * Imported classes never fall through to their owner's source recipe.
     */
    public static Resolution resolve(GeneratedProjectModel project,
            GeneratedClassModel clazz, SourceExecutionPlan.Step localStep) {
        if (clazz == null) {
            return new Resolution(Resolution.Kind.NONE, List.of(), List.of(),
                    "No class selected");
        }
        if (clazz.isImported()) {
            ImportedPopulationInputs inputs = ImportedPopulationInputs.of(project);
            List<String> qids = inputs.qidsFor(clazz.className());
            List<String> names = inputs.selectionNamesFor(clazz.className());
            return qids.isEmpty()
                    ? new Resolution(Resolution.Kind.NONE, List.of(), names,
                            "Imported class contributes configuration only; no local "
                                    + "construct uses one of its saved populations")
                    : new Resolution(Resolution.Kind.IMPORTED_POPULATION, qids, names, "");
        }
        if (localStep == null) {
            return new Resolution(Resolution.Kind.LOCAL_SOURCE,
                    List.of(), List.of(), "");
        }
        PopulationRequest request = localStep.prepared().configuration(PopulationRequest.class);
        return request == null
                ? new Resolution(Resolution.Kind.NONE, List.of(), List.of(),
                        localStep.prepared().description())
                : new Resolution(Resolution.Kind.LOCAL_SOURCE, List.of(), List.of(), "");
    }

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
        root.sourceQid("");
        root.additionalSourceQids().clear();
        root.membershipIncludesDescendants(false);
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
            root.membershipIncludesDescendants(selection.includeDescendants());
            // includedQids remain: with a relation they are an independent restriction
            // over the selected population, not the population recipe itself.
        } else {
            root.includedQids().clear();
            selection.values().stream().map(datasource.EntityRef::id)
                    .forEach(root::addIncludedQid);
        }
        return root;
    }

    /**
     * Replaces a producer's membership rule with an explicitly saved population.
     *
     * <p>The QIDs are the population here, not an extra filter over the imported
     * class's original rule.  In particular, the imported relation, exclusions,
     * ranking and old limit must not silently turn a 410-member saved population back
     * into the producer's 1,319 members (or truncate it by counting multiple language
     * label rows before all QIDs have been visited).
     */
    public static RuleNode applyExact(RuleNode root, Collection<String> qids) {
        if (root == null) throw new IllegalArgumentException("Root rule is required");
        root.sourceQid("");
        root.additionalSourceQids().clear();
        root.propertyPid("");
        root.membershipIncludesDescendants(false);
        root.membershipPid("");
        root.membershipQid("");
        root.membershipConstraints().clear();
        root.includedFields().forEach(field -> field.optional(true));
        root.labelConfig().requireLabel(false);
        root.requireSitelink(false);
        root.rankBySitelinks(false);
        root.rankPropertyPid("");
        root.rankBand(RuleNode.UNBOUNDED, RuleNode.UNBOUNDED);
        root.sortFieldName("");
        root.includedQids().clear();
        root.excludedQids().clear();
        root.excludedPredicateObjects().clear();
        root.valueFilters().clear();
        if (qids != null) qids.forEach(root::addIncludedQid);
        // VALUES already bounds the request.  A LIMIT equal to the number of QIDs is
        // not equivalent: the query selects ?valueLabel too, so one entity carrying
        // both an English and a mul label occupies two DISTINCT rows.  In History two
        // such rows displaced the final two unlabeled positions from a 410-QID
        // population.  Keep the query practically unlimited and let the registry's
        // QID identity perform the intended deduplication.
        root.limit(Integer.MAX_VALUE);
        return root;
    }
}
