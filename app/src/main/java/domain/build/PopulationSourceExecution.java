package domain.build;

import domain.datasource.api.BindingScope;
import domain.datasource.api.SourceExecutionPlan;
import domain.datasource.api.acquisition.AcquiredPopulation;
import domain.datasource.api.acquisition.PopulationAcquisition;
import domain.datasource.api.acquisition.PopulationRequest;
import wikidata.WikidataIds;
import domain.config.RuleDirection;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.ImportedPopulationInputs;
import domain.config.MembershipPattern;
import domain.compile.rule.RuleNode;
import domain.runtime.WikidataDynamicObject;

import java.util.Collection;
import java.util.List;

/** Applies a resolved class-population step to the existing extraction rule. */
public final class PopulationSourceExecution {
    private PopulationSourceExecution() { }

    /** One answer to how a class receives its root instances in every run scope. */
    public record Resolution(
            Kind kind, List<String> qids, List<String> selectionNames,
            SourceExecutionPlan.Step step, String reason,
            List<WikidataDynamicObject> materializedObjects) {
        public enum Kind {
            LOCAL_SOURCE,
            LOCAL_ACQUISITION,
            LOCAL_ACQUIRED,
            IMPORTED_POPULATION,
            IMPORTED_MATERIALIZED,
            NONE
        }

        public Resolution(Kind kind, List<String> qids, List<String> selectionNames,
                SourceExecutionPlan.Step step, String reason) {
            this(kind, qids, selectionNames, step, reason, List.of());
        }

        public Resolution {
            qids = List.copyOf(qids == null ? List.of() : qids);
            selectionNames = List.copyOf(
                    selectionNames == null ? List.of() : selectionNames);
            reason = reason == null ? "" : reason;
            materializedObjects = List.copyOf(
                    materializedObjects == null ? List.of() : materializedObjects);
        }

        public boolean available() { return kind != Kind.NONE; }
        public boolean importedPopulation() { return kind == Kind.IMPORTED_POPULATION; }
        public boolean importedMaterialized() { return kind == Kind.IMPORTED_MATERIALIZED; }
        public boolean pendingAcquisition() { return kind == Kind.LOCAL_ACQUISITION; }

        /** Whether this resolved population still has identities for Wikidata to materialize. */
        public boolean requiresRootQuery() {
            return switch (kind) {
                case LOCAL_SOURCE -> true;
                case LOCAL_ACQUISITION -> throw new IllegalStateException(
                        "Population source must be acquired before planning its root query");
                case LOCAL_ACQUIRED, IMPORTED_POPULATION -> !qids.isEmpty();
                case IMPORTED_MATERIALIZED -> !qidsRequiringAcquisition().isEmpty();
                case NONE -> false;
            };
        }
        public List<String> qidsRequiringAcquisition() {
            if (!importedMaterialized()) return qids;
            java.util.Set<String> materialized = materializedObjects.stream()
                    .map(WikidataDynamicObject::getIdentifier)
                    .collect(java.util.stream.Collectors.toSet());
            return qids.stream().filter(qid -> !materialized.contains(qid)).toList();
        }

        public RuleNode apply(RuleNode root) {
            return switch (kind) {
                // An offline caller resolved without a plan and carries no step; its
                // rule is already compiled, so there is nothing to adapt.
                case LOCAL_SOURCE -> step == null
                        ? root : PopulationSourceExecution.apply(root, step);
                case LOCAL_ACQUISITION -> throw new IllegalStateException(
                        "Population source must be acquired before it is applied");
                case LOCAL_ACQUIRED -> PopulationSourceExecution.applyExact(root, qids);
                case IMPORTED_POPULATION -> PopulationSourceExecution.applyExact(root, qids);
                case IMPORTED_MATERIALIZED -> PopulationSourceExecution.applyExact(
                        root, qidsRequiringAcquisition());
                case NONE -> throw new IllegalStateException(
                        reason.isBlank() ? "No class population input" : reason);
            };
        }

        /**
         * Executes a self-acquiring population operation once, then returns the exact
         * identities which the ordinary extraction rule will materialize.
         */
        public Resolution acquire(
                work.QueryContext context,
                domain.runtime.GenerationLog log) throws Exception {
            if (!pendingAcquisition()) return this;
            PopulationAcquisition acquisition = step == null ? null
                    : step.prepared().configuration(PopulationAcquisition.class);
            if (acquisition == null) {
                throw new IllegalStateException("Population acquisition is not prepared");
            }
            work.QueryContext runContext = context == null
                    ? new work.QueryContext() : context;
            AcquiredPopulation result = acquisition.query().execute(runContext);
            List<String> resolved = new java.util.ArrayList<>();
            for (domain.datasource.EntityRef member : result.members()) {
                if (member == null || !acquisition.namespace().equals(member.namespace())) {
                    throw new IllegalStateException("Population source "
                            + acquisition.displayName() + " returned an identity outside "
                            + acquisition.namespace());
                }
                if (!domain.datasource.EntityRef.WIKIDATA.equals(member.namespace())
                        || !WikidataIds.isQid(member.id())) {
                    throw new IllegalStateException("Generation cannot materialize population "
                            + "identity " + member.qualifiedId());
                }
                if (!resolved.contains(member.id())) resolved.add(member.id());
            }
            if (log != null) {
                log.message("Load " + acquisition.displayName() + ": "
                        + resolved.size() + " resolved Wikidata QID(s); "
                        + result.unresolvedSourceRecords().size()
                        + " source record(s) without a Wikidata QID were not added.\n");
                if (!result.unresolvedSourceRecords().isEmpty()) {
                    log.message("Unresolved source records: "
                            + String.join(", ", result.unresolvedSourceRecords()) + ".\n");
                }
            }
            return new Resolution(Kind.LOCAL_ACQUIRED, resolved, List.of(), step, "");
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
        return resolve(project, clazz, plan, null);
    }

    public static Resolution resolve(GeneratedProjectModel project,
            GeneratedClassModel clazz, SourceExecutionPlan plan,
            ImportedMaterializedPopulationInputs materializedInputs) {
        if (clazz == null) {
            return new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                    "No class selected");
        }
        if (MembershipPattern.of(clazz, project)
                == MembershipPattern.POPULATION_SUBCLASS) {
            return new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                    "its members are classified locally from its base class and saved "
                            + "population, not by a population query");
        }
        if (clazz.isImported()) {
            ImportedPopulationInputs inputs = ImportedPopulationInputs.of(project);
            List<String> qids = inputs.qidsFor(clazz.className());
            List<String> names = inputs.selectionNamesFor(clazz.className());
            List<WikidataDynamicObject> materialized = materializedInputs == null
                    ? List.of() : materializedInputs.membersFor(clazz.className());
            if (!materialized.isEmpty()) {
                List<String> published = materializedInputs.identitiesFor(clazz.className());
                return new Resolution(Resolution.Kind.IMPORTED_MATERIALIZED,
                        published.isEmpty() ? qids : published,
                        names, null, "", materialized);
            }
            return qids.isEmpty()
                    ? new Resolution(Resolution.Kind.NONE, List.of(), names, null,
                            "Imported class contributes configuration only; no local "
                                    + "construct uses one of its saved populations")
                    : new Resolution(
                            Resolution.Kind.IMPORTED_POPULATION, qids, names, null, "");
        }
        if (clazz.materializedByTransformation()) {
            return new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                    "its members are produced by saved transformation, not by a "
                            + "population query");
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
                domain.datasource.api.SourceBindingTarget.classPopulation(clazz.className()));
        if (localStep == null) {
            return new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                    "no population source is configured");
        }
        PopulationRequest request = localStep.prepared().configuration(PopulationRequest.class);
        if (request != null) {
            return new Resolution(
                    Resolution.Kind.LOCAL_SOURCE, List.of(), List.of(), localStep, "");
        }
        PopulationAcquisition acquisition = localStep.prepared()
                .configuration(PopulationAcquisition.class);
        return acquisition == null
                ? new Resolution(Resolution.Kind.NONE, List.of(), List.of(), null,
                        localStep.prepared().description())
                : new Resolution(Resolution.Kind.LOCAL_ACQUISITION,
                        List.of(), List.of(), localStep, "");
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
        if (!domain.datasource.EntityRef.WIKIDATA.equals(selection.namespace())) {
            throw new IllegalArgumentException("Generation does not yet adapt population '"
                    + selection.namespace() + "' into its Wikidata extraction boundary");
        }
        root.sourceQid("");
        root.additionalSourceQids().clear();
        root.membershipIncludesDescendants(false);
        root.membershipQualifierCondition(null);
        if (selection.kind() == PopulationRequest.Kind.RELATION) {
            String pid = selection.relationId().toUpperCase();
            if (!WikidataIds.isPid(pid)) {
                throw new IllegalArgumentException("Invalid Wikidata population property: " + pid);
            }
            root.propertyPid(pid);
            root.direction(RuleDirection.ITEM_TO_ROOT);
            if (!selection.values().isEmpty()) {
                root.sourceQid(selection.values().getFirst().id());
                selection.values().stream().skip(1)
                        .map(domain.datasource.EntityRef::id).forEach(root::addAdditionalSourceQid);
            }
            root.membershipIncludesDescendants(selection.includeDescendants());
            domain.datasource.api.acquisition.RelationCondition condition =
                    selection.relationCondition();
            root.membershipQualifierCondition(condition == null ? null
                    : new wikidata.QualifierCondition(condition.relationId(),
                            condition.values().stream()
                                    .map(domain.datasource.EntityRef::id).toList()));
            // includedQids remain: with a relation they are an independent restriction
            // over the selected population, not the population recipe itself.
        } else {
            root.includedQids().clear();
            selection.values().stream().map(domain.datasource.EntityRef::id)
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
        root.membershipQualifierCondition(null);
        root.membershipPid("");
        root.membershipQid("");
        root.membershipFilterQualifierCondition(null);
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
