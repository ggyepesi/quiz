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
            Kind kind, List<String> qids, List<String> selectionNames,
            SourceExecutionPlan.Step step, String reason) {
        public enum Kind { LOCAL_SOURCE, IMPORTED_POPULATION, NONE }

        public Resolution {
            qids = List.copyOf(qids == null ? List.of() : qids);
            selectionNames = List.copyOf(
                    selectionNames == null ? List.of() : selectionNames);
            reason = reason == null ? "" : reason;
        }

        public boolean available() { return kind != Kind.NONE; }
        public boolean importedPopulation() { return kind == Kind.IMPORTED_POPULATION; }

        public RuleNode apply(RuleNode root) {
            return switch (kind) {
                // An offline caller resolved without a plan and carries no step; its
                // rule is already compiled, so there is nothing to adapt.
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
     *
     * <p>A plan that has no step for this class and no plan at all are different
     * answers. The first says the class has no population source, which is why a
     * reference-only class is skipped rather than asked for; the second is an offline
     * caller running an already compiled rule, where there is nothing to adapt. Reading
     * both off a null step made a domain run ask Wikidata for three classes that have
     * no population, each answering with the empty set.
     */
    public static Resolution resolve(GeneratedProjectModel project,
            GeneratedClassModel clazz, SourceExecutionPlan plan) {
        if (clazz == null) {
            return new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                    "No class selected");
        }
        if (clazz.isImported()) {
            ImportedPopulationInputs inputs = ImportedPopulationInputs.of(project);
            List<String> qids = inputs.qidsFor(clazz.className());
            List<String> names = inputs.selectionNamesFor(clazz.className());
            return qids.isEmpty()
                    ? new Resolution(Resolution.Kind.NONE, List.of(), names, null,
                            "Imported class contributes configuration only; no local "
                                    + "construct uses one of its saved populations")
                    : new Resolution(
                            Resolution.Kind.IMPORTED_POPULATION, qids, names, null, "");
        }
        if (clazz.reifiesStatements()) {
            return new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                    "it is produced by statement reification, not by a population query");
        }
        if (plan == null) {
            return new Resolution(Resolution.Kind.LOCAL_SOURCE,
                    List.of(), List.of(), null, "");
        }
        SourceExecutionPlan.Step localStep = plan.step(
                datasource.api.SourceBindingTarget.classPopulation(clazz.className()));
        if (localStep == null) {
            return new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                    "no population source is configured");
        }
        PopulationRequest request = localStep.prepared().configuration(PopulationRequest.class);
        return request == null
                ? new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                        localStep.prepared().description())
                : new Resolution(
                        Resolution.Kind.LOCAL_SOURCE, List.of(), List.of(), localStep, "");
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
            // The listed entities ARE the population, so the class's configured limit
            // (200 by default) could only subtract from what the modeller enumerated.
            // It cannot even be set to their number: the query projects ?valueLabel
            // through the en,mul label service, so an entity carrying both labels
            // occupies two rows and the cut starts before the last QID is reached.
            // What the modeller authored AROUND the seeds — exclusions, value filters,
            // label and sitelink requirements — deliberately restricts them and stays.
            // That is what separates this from applyExact, where nothing of an
            // imported producer's rule survives.
            root.limit(Integer.MAX_VALUE);
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
