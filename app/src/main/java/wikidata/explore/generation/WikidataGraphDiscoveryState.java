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
import wikidata.explore.model.FieldType;
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
        for (ModelStatementReifications.Reification reification
                : ModelStatementReifications.derive(model)) {
            var load = reification.load();
            GeneratedClassModel statement = model.findClass(load.statementType());
            if (statement == null || statement.statementSource() == null
                    || statement.statementSource().graphExpansionPolicy()
                    != GraphExpansionPolicy.CURATED) continue;
            GraphExpansionPattern pattern = structuralPattern(model, reification);
            if (pattern == null) continue;
            patterns.add(pattern);

            GraphRelation relation = pattern.relation();
            Set<String> expanded = new LinkedHashSet<>(load.discoveryValueQids());
            Set<String> encountered = encounteredValues(
                    objects, load.statementType(), pattern.targetField());
            expanded.stream().filter(WikidataIds::isQid).forEach(qid -> coverage.add(
                    item(pattern, relation, qid, GraphExpansionCoverage.State.EXPANDED)));
            encountered.stream().filter(qid -> !expanded.contains(qid)).forEach(qid ->
                    coverage.add(item(pattern, relation, qid,
                            GraphExpansionCoverage.State.ENCOUNTERED)));
        }
        return new GraphDiscoveryState(patterns, coverage);
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
        return ModelStatementReifications.derive(model).stream()
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
                load.statementType(), source.name(), target.name());
    }

    private static GraphExpansionCoverage item(
            GraphExpansionPattern pattern, GraphRelation relation, String qid,
            GraphExpansionCoverage.State state) {
        return new GraphExpansionCoverage(pattern.id(), EntityRef.wikidata(qid), relation,
                GraphExpansionCoverage.Direction.INCOMING, state);
    }

    private static Set<String> encounteredValues(
            Collection<WikidataDynamicObject> objects, String statementClass,
            String targetField) {
        Map<String, String> values = new LinkedHashMap<>();
        if (objects == null) return values.keySet();
        // A statement or its target may exist only as a nested reference. Coverage
        // describes the graph that will be saved, so walk that exact reachable graph.
        for (WikidataDynamicObject object : WikidataObjectGraph.reachable(objects)) {
            // Membership must inspect the explicit stamp. typeName() falls back to the
            // Java class for an unstamped object and must never answer this question.
            if (object == null || !object.hasTypeStamp()
                    || !object.directClassNames().contains(statementClass)) continue;
            collect(FieldAccess.getPathValues(object,
                    objectview.field.FieldPath.parse(targetField)), values);
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
