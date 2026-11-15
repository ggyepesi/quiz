package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.api.BindingScope;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.graph.execution.RelationRouteExecutor;
import datasource.graph.store.InMemoryGraphStore;
import datasource.wikidata.WikidataDatasourceProvider;
import datasource.wikidata.WikidataRelationRouteSpec;
import wikidata.WikidataIds;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Fills entity collections from finite, explicitly configured Wikidata routes. */
public final class WikidataRelationRouteFieldAcquisition {
    private WikidataRelationRouteFieldAcquisition() { }

    public static Result apply(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            SourceExecutionPlan plan,
            WikidataApiClient api,
            WikidataSparqlClient sparql,
            GenerationLog log,
            work.CancellationToken cancellation) throws Exception {
        if (model == null || pool == null || pool.isEmpty() || plan == null) {
            return new Result(0, 0, List.of(), List.of());
        }
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        List<Declaration> declarations = declarations(model, plan);
        if (declarations.isEmpty()) return new Result(0, 0, List.of(), List.of());
        WikidataGraphAdjacencyAcquisition acquirer =
                new WikidataGraphAdjacencyAcquisition(api, sparql, sink, cancellation);
        InMemoryGraphStore store = new InMemoryGraphStore();
        int fields = 0;
        int values = 0;
        LinkedHashSet<EntityRef> incomplete = new LinkedHashSet<>();
        LinkedHashSet<EntityRef> unavailable = new LinkedHashSet<>();

        List<WikidataDynamicObject> reachable =
                wikidata.explore.extract.WikidataObjectGraph.reachable(pool);
        for (Declaration declaration : declarations) {
            Map<EntityRef, List<WikidataDynamicObject>> owners = owners(
                    model, reachable, declaration);
            if (owners.isEmpty()) continue;
            sink.message("Follow " + declaration.spec().routes().size()
                    + " bounded route(s) for " + declaration.owner().className() + "."
                    + declaration.field().name() + " from " + owners.size()
                    + " owner(s).\n");
            RelationRouteExecutor.Result result = RelationRouteExecutor.execute(
                    store, declaration.spec().routes(), List.copyOf(owners.keySet()), acquirer);
            fields++;
            incomplete.addAll(result.incomplete());
            unavailable.addAll(result.unavailable());
            for (Map.Entry<EntityRef, List<WikidataDynamicObject>> owner : owners.entrySet()) {
                for (RelationRouteExecutor.Match match : result.matches(owner.getKey())) {
                    EntityRef terminal = match.terminal();
                    if (!EntityRef.WIKIDATA.equals(terminal.namespace())
                            || !WikidataIds.isQid(terminal.id())) continue;
                    List<quiz.source.WikidataStatementSource> evidence =
                            statementSources(match.path());
                    for (WikidataDynamicObject copy : owner.getValue()) {
                        int before = valueCount(copy.get(declaration.field().name()));
                        // A route links; it does not admit. The reference stays bare, and
                        // the ordinary referent stamping decides its class exactly as for
                        // a property-loaded value: on a reference cycle such as
                        // Character.child only an existing member keeps the link (rules 9
                        // and 27), so a route never grows the authored population.
                        copy.merge(declaration.field().name(),
                                new WikidataDynamicObject(terminal.id(), terminal.id()));
                        if (valueCount(copy.get(declaration.field().name())) > before) values++;
                        evidence.forEach(copy::addWikidataStatementSource);
                    }
                }
            }
        }
        sink.message("Wikidata relation routes: " + values + " field value(s) on "
                + fields + " field(s); " + incomplete.size() + " incomplete and "
                + unavailable.size() + " unavailable adjacency node(s).\n");
        return new Result(fields, values, List.copyOf(incomplete),
                List.copyOf(unavailable));
    }

    private static List<Declaration> declarations(
            GeneratedProjectModel model, SourceExecutionPlan plan) {
        List<Declaration> result = new ArrayList<>();
        for (SourceExecutionPlan.Step step : plan.steps(BindingScope.FIELD_VALUE)) {
            WikidataRelationRouteSpec spec = step.prepared()
                    .configuration(WikidataRelationRouteSpec.class);
            if (spec == null) continue;
            GeneratedClassModel owner = model.findClass(step.target().className());
            GeneratedFieldModel field = wikidata.explore.model.OwnedFieldSources
                    .declaredField(model, step.target().className(),
                            step.target().fieldPath());
            if (owner == null || field == null) {
                throw new IllegalArgumentException("Relation-route source targets unknown field "
                        + step.target().className() + "." + step.target().fieldPath());
            }
            if (step.target().fieldPath().contains(".")) {
                throw new IllegalArgumentException(
                        "Relation-route sources currently require a top-level field: "
                                + step.target().className() + "."
                                + step.target().fieldPath());
            }
            if (field.type() != datasource.schema.FieldType.ENTITY
                    || field.cardinality() != FieldCardinality.COLLECTION
                    || field.entityClassName().isBlank()) {
                throw new IllegalArgumentException("Relation-route target "
                        + owner.className() + "." + field.name()
                        + " must be a collection of a configured entity class");
            }
            result.add(new Declaration(owner, field, step.target(), spec));
        }
        return List.copyOf(result);
    }

    private static Map<EntityRef, List<WikidataDynamicObject>> owners(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            Declaration declaration) {
        LinkedHashMap<EntityRef, List<WikidataDynamicObject>> result =
                new LinkedHashMap<>();
        for (WikidataDynamicObject object : pool) {
            if (object == null || !WikidataIds.isQid(object.qid())
                    || !FieldSourceTargeting.applies(model, object,
                            declaration.owner(), declaration.target())) continue;
            result.computeIfAbsent(EntityRef.wikidata(object.qid()),
                    ignored -> new ArrayList<>()).add(object);
        }
        return result;
    }

    /**
     * The Wikidata statements that made one route path true, as the owner's ordinary
     * statement provenance. A statement step and the qualifier step that reads it are
     * one statement; a property edge without a statement id (none was returned) is
     * left out rather than recorded as an anonymous claim.
     */
    static List<quiz.source.WikidataStatementSource> statementSources(
            List<datasource.graph.store.GraphEdge> path) {
        List<quiz.source.WikidataStatementSource> sources = new ArrayList<>();
        for (int index = 0; index < path.size(); index++) {
            datasource.graph.store.GraphEdge edge = path.get(index);
            datasource.wikidata.WikidataRouteRelation relation =
                    datasource.wikidata.WikidataRouteRelation.parse(edge.relation());
            if (relation == null || edge.provenanceId().isBlank()) continue;
            switch (relation.kind()) {
                case PROPERTY -> {
                    if (edge.target() instanceof EntityRef object) {
                        sources.add(new quiz.source.WikidataStatementSource(
                                edge.provenanceId(), edge.source().id(), relation.pid(),
                                object.id(), ""));
                    }
                }
                case STATEMENT -> {
                    String subject = edge.source().id();
                    Map<String, List<String>> qualifiers = Map.of();
                    if (index + 1 < path.size()) {
                        datasource.graph.store.GraphEdge next = path.get(index + 1);
                        datasource.wikidata.WikidataRouteRelation read =
                                datasource.wikidata.WikidataRouteRelation.parse(
                                        next.relation());
                        if (read != null && read.kind()
                                == datasource.wikidata.WikidataRouteRelation.Kind.QUALIFIER
                                && next.target() instanceof EntityRef value) {
                            qualifiers = Map.of(read.pid(), List.of(value.id()));
                        }
                    }
                    sources.add(new quiz.source.WikidataStatementSource(
                            edge.provenanceId(), subject, relation.pid(), "", "",
                            "normal", qualifiers, List.of()));
                }
                case QUALIFIER -> { }
            }
        }
        return List.copyOf(sources);
    }

    private static int valueCount(Object value) {
        if (value == null) return 0;
        return value instanceof List<?> values ? values.size() : 1;
    }

    private record Declaration(
            GeneratedClassModel owner,
            GeneratedFieldModel field,
            SourceBindingTarget target,
            WikidataRelationRouteSpec spec) { }

    public record Result(
            int fields,
            int values,
            List<EntityRef> incomplete,
            List<EntityRef> unavailable) {
        public Result {
            incomplete = incomplete == null ? List.of() : List.copyOf(incomplete);
            unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
        }
        public boolean complete() { return incomplete.isEmpty() && unavailable.isEmpty(); }
    }
}
