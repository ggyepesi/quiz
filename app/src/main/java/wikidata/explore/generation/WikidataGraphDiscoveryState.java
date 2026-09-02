package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphExpansionPolicy;
import datasource.graph.GraphRelation;
import objectview.field.FieldAccess;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataObjectGraph;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.ModelStatementReifications;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Wikidata adapter deriving the first graph-discovery pattern from statement models. */
public final class WikidataGraphDiscoveryState {
    private WikidataGraphDiscoveryState() { }

    public static GraphDiscoveryState compute(
            GeneratedProjectModel model, Collection<WikidataDynamicObject> objects) {
        if (model == null) return GraphDiscoveryState.EMPTY;
        List<GraphExpansionPattern> patterns = new ArrayList<>();
        List<GraphExpansionCoverage> coverage = new ArrayList<>();
        // A model that does not compile has nothing to say about traversal, which is the
        // same empty answer a null model gives. Saving records what is there; deciding
        // whether a model is fit to run belongs to generation, not to persistence.
        var compiled = wikidata.explore.compiled.ProjectModelCompiler.compileIfValid(model);
        if (compiled.isEmpty()) return GraphDiscoveryState.EMPTY;
        for (ModelStatementReifications.Reification reification
                : ModelStatementReifications.derive(compiled.get())) {
            var load = reification.load();
            GeneratedClassModel statement = model.findClass(load.statementType());
            if (statement == null || statement.statementSource() == null
                    || statement.statementSource().graphExpansionPolicy()
                    != GraphExpansionPolicy.CURATED) continue;
            GraphExpansionPattern pattern = structuralPattern(model, reification);
            if (pattern == null) continue;
            patterns.add(pattern);

            coverage.addAll(GraphExpansionCoverage.of(pattern,
                    nodes(load.discoveryValueQids()),
                    nodes(encounteredValues(
                            objects, load.statementType(), pattern.targetField()))));
        }
        // A field edge reaches its nodes through the field itself: no statement class
        // stands between the two ends, so the values of the source field ARE what the
        // walk encountered. The statement loop above needs a reification to find them;
        // this needs only the class the field is on.
        for (datasource.graph.GraphTraversalStep step
                : WikidataFieldGraphTraversal.derive(model)) {
            if (step.policy() != GraphExpansionPolicy.CURATED) continue;
            coverage.addAll(GraphExpansionCoverage.of(step,
                    nodes(seedQids(model, step.sourceNodeClass())),
                    nodes(encounteredValues(
                            objects, step.sourceNodeClass(), step.sourceField()))));
        }
        return new GraphDiscoveryState(patterns, coverage);
    }

    /** What this class was already told to acquire — the walk's expanded nodes. */
    private static Set<String> seedQids(GeneratedProjectModel model, String className) {
        GeneratedClassModel clazz = model.findClass(className);
        return clazz == null ? Set.of() : new LinkedHashSet<>(clazz.seedQids());
    }

    /**
     * Adds data-side expansion work to the disposable model copy used by generation.
     * The authored model remains unchanged; its seed QIDs are only the graph anchors.
     */
    public static void applyExpansionLedger(
            GeneratedProjectModel model, GraphDiscoveryState ledger) {
        if (model == null || ledger == null) return;
        List<GraphExpansionPattern> active =
                WikidataGraphExpansionPlan.compile(model).patterns();
        for (GraphExpansionPattern pattern : ledger.patterns()) {
            if (active.stream().noneMatch(candidate ->
                    candidate.id().equals(pattern.id())
                            && candidate.relation().equals(pattern.relation())
                            && candidate.direction() == pattern.direction())) continue;
            GeneratedClassModel target = model.findClass(pattern.targetNodeClass());
            if (target == null) continue;
            seedFromCoverage(ledger, pattern, target);
        }
        // The same for a field edge. Its definition is derived from the model rather
        // than persisted, so it is re-derived here and matched by id — which is what
        // the pattern loop above already does with its own active list.
        for (datasource.graph.GraphTraversalStep step
                : WikidataFieldGraphTraversal.derive(model)) {
            if (step.policy() != GraphExpansionPolicy.CURATED) continue;
            GeneratedClassModel target = model.findClass(step.targetNodeClass());
            if (target != null) seedFromCoverage(ledger, step, target);
        }
    }

    /** Nodes this edge has settled on become the target class's acquisition seeds. */
    private static void seedFromCoverage(GraphDiscoveryState ledger,
            datasource.graph.GraphEdgeDefinition edge, GeneratedClassModel target) {
        ledger.coverage().stream()
                .filter(item -> item.patternId().equals(edge.id()))
                .filter(item -> item.relation().equals(edge.relation()))
                .filter(item -> item.direction() == edge.direction())
                .filter(item -> item.state() == GraphExpansionCoverage.State.QUEUED
                        || item.state() == GraphExpansionCoverage.State.EXPANDED
                        || item.state() == GraphExpansionCoverage.State.INCOMPLETE)
                .map(GraphExpansionCoverage::node)
                .filter(node -> EntityRef.WIKIDATA.equals(node.namespace()))
                .map(EntityRef::id).filter(WikidataIds::isQid)
                .filter(qid -> !target.seedQids().contains(qid))
                .forEach(target.seedQids()::add);
    }

    /**
     * The pattern this statement class yields by its structure alone, independent of
     * whether its author has enabled expansion. The StatementClass editor asks this
     * to explain a policy the user is choosing but has not yet applied; {@link
     * #compute} asks the same question, but only after the policy gate. One
     * derivation, so the editor cannot promise a pattern generation would not build.
     */
    public static GraphExpansionPattern structuralPattern(
            GeneratedProjectModel model, String statementClassName) {
        if (model == null || statementClassName == null) return null;
        var compiled = wikidata.explore.compiled.ProjectModelCompiler.compileIfValid(model);
        if (compiled.isEmpty()) return null;
        return ModelStatementReifications.derive(compiled.get()).stream()
                .filter(reification ->
                        statementClassName.equals(reification.load().statementType()))
                .map(reification -> structuralPattern(model, reification))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static GraphExpansionPattern structuralPattern(
            GeneratedProjectModel model,
            ModelStatementReifications.Reification reification) {
        var load = reification.load();
        GeneratedClassModel statement = model.findClass(load.statementType());
        if (statement == null) return null;
        // An open forward value domain plus bounded reverse seeds is precisely the
        // curated-frontier pattern. A vocabulary that filters values has no unseen
        // frontier by definition and remains an ordinary bounded reification.
        if (!load.discoverSubjects() || !load.hasDiscoveryValueQids()
                || load.hasValueQids() || load.hasValueType()) return null;
        GeneratedFieldModel target = statement.fields().stream()
                .filter(field -> load.valueField().equals(field.name()))
                .filter(field -> field.type() == FieldType.ENTITY)
                .findFirst().orElse(null);
        GeneratedFieldModel source = statement.fields().stream()
                .filter(field -> reification.reify().sourceField().equals(field.name()))
                .filter(field -> field.type() == FieldType.ENTITY)
                .findFirst().orElse(null);
        // An ENTITY field is allowed to be temporarily unclassed while the model is
        // being edited. structuralPattern is also an editor query, so incomplete
        // endpoint typing means "not available yet", not a constructor exception.
        if (source == null || source.entityClassName().isBlank()
                || target == null || target.entityClassName().isBlank()) {
            return null;
        }
        String id = load.statementType() + ":" + load.propertyPid() + ":"
                + target.entityClassName();
        return new GraphExpansionPattern(
                id, source.entityClassName(), target.entityClassName(),
                new GraphRelation("wikidata", load.propertyPid()),
                load.statementType(), source.name(), target.name(),
                datasource.graph.GraphTraversalDirection.INCOMING);
    }

    /** Wikidata's mapping into the neutral graph layer: QIDs become node identities. */
    public static List<EntityRef> nodes(Collection<String> qids) {
        return qids == null ? List.of() : qids.stream().filter(WikidataIds::isQid)
                .distinct().map(EntityRef::wikidata).toList();
    }

    /** The nodes an edge reached: values of {@code valueField} on every object stamped
     *  with {@code ownerClass}. For a statement pattern the owner is the statement
     *  class and the field is its target role; for a field edge the owner is the class
     *  the field is on. */
    private static Set<String> encounteredValues(
            Collection<WikidataDynamicObject> objects, String ownerClass,
            String valueField) {
        Map<String, String> values = new LinkedHashMap<>();
        if (objects == null) return values.keySet();
        // A statement or its target may exist only as a nested reference. Coverage
        // describes the graph that will be saved, so walk that exact reachable graph.
        for (WikidataDynamicObject object : WikidataObjectGraph.reachable(objects)) {
            // Membership must inspect the explicit stamp. typeName() falls back to the
            // Java class for an unstamped object and must never answer this question.
            if (object == null || !object.hasTypeStamp()
                    || !object.directClassNames().contains(ownerClass)) continue;
            collect(FieldAccess.getPathValues(object,
                    objectview.field.FieldPath.parse(valueField)), values);
        }
        return new LinkedHashSet<>(values.keySet());
    }

    private static void collect(Object value, Map<String, String> values) {
        if (value instanceof WikidataDynamicObject object
                && WikidataIds.isQid(object.qid())) {
            values.putIfAbsent(object.qid(), object.getDisplayName());
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collect(item, values));
        }
    }
}
