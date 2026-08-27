package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphRelation;
import objectview.field.FieldAccess;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
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
            // An open forward value domain plus bounded reverse seeds is precisely the
            // curated-frontier pattern. A vocabulary that filters values has no unseen
            // frontier by definition and remains an ordinary bounded reification.
            if (!load.discoverSubjects() || !load.hasDiscoveryValueQids()
                    || load.hasValueQids() || load.hasValueType()) continue;
            GeneratedClassModel statement = model.findClass(load.statementType());
            if (statement == null) continue;
            GeneratedFieldModel target = statement.fields().stream()
                    .filter(field -> load.valueField().equals(field.name()))
                    .filter(field -> field.type() == FieldType.ENTITY)
                    .findFirst().orElse(null);
            GeneratedFieldModel source = statement.fields().stream()
                    .filter(field -> reification.reify().sourceField().equals(field.name()))
                    .filter(field -> field.type() == FieldType.ENTITY)
                    .findFirst().orElse(null);
            if (source == null || target == null || target.entityClassName().isBlank()) continue;

            String id = load.statementType() + ":" + load.propertyPid() + ":"
                    + target.entityClassName();
            GraphRelation relation = new GraphRelation("wikidata", load.propertyPid());
            GraphExpansionPattern pattern = new GraphExpansionPattern(
                    id, source.entityClassName(), target.entityClassName(), relation,
                    load.statementType(), source.name(), target.name());
            patterns.add(pattern);

            Set<String> expanded = new LinkedHashSet<>(load.discoveryValueQids());
            Set<String> encountered = encounteredValues(
                    objects, load.statementType(), target.name());
            expanded.stream().filter(WikidataIds::isQid).forEach(qid -> coverage.add(
                    item(pattern, relation, qid, GraphExpansionCoverage.State.EXPANDED)));
            encountered.stream().filter(qid -> !expanded.contains(qid)).forEach(qid ->
                    coverage.add(item(pattern, relation, qid,
                            GraphExpansionCoverage.State.ENCOUNTERED)));
        }
        return new GraphDiscoveryState(patterns, coverage);
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
        for (WikidataDynamicObject object : objects) {
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
