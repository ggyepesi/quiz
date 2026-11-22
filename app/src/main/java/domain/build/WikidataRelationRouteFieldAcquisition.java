package domain.build;

import domain.datasource.EntityRef;
import domain.datasource.api.BindingScope;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceExecutionPlan;
import domain.datasource.graph.execution.RelationRouteExecutor;
import domain.datasource.graph.store.InMemoryGraphStore;
import domain.datasource.wikidata.WikidataDatasourceProvider;
import domain.datasource.wikidata.WikidataRelationRouteSpec;
import wikidata.WikidataIds;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import domain.runtime.GenerationLog;
import domain.runtime.WikidataDynamicObject;
import domain.config.FieldCardinality;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;

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
        return apply(model, pool, plan, sink, acquirer, store);
    }

    /** Run-scoped entry used by the source family so fixed-point passes share acquired
     * adjacency and never ask the same route edge from the network twice. */
    static Result apply(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            SourceExecutionPlan plan,
            GenerationLog log,
            WikidataGraphAdjacencyAcquisition acquirer,
            InMemoryGraphStore store) throws Exception {
        if (model == null || pool == null || pool.isEmpty() || plan == null) {
            return new Result(0, 0, List.of(), List.of());
        }
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        List<Declaration> declarations = declarations(model, plan);
        if (declarations.isEmpty()) return new Result(0, 0, List.of(), List.of());
        int fields = 0;
        int values = 0;
        LinkedHashSet<EntityRef> incomplete = new LinkedHashSet<>();
        LinkedHashSet<EntityRef> unavailable = new LinkedHashSet<>();

        for (Declaration declaration : declarations) {
            List<WikidataDynamicObject> reachable =
                    domain.runtime.WikidataObjectGraph.reachable(pool);
            Map<EntityRef, List<WikidataDynamicObject>> owners = owners(
                    model, reachable, declaration);
            if (owners.isEmpty()) continue;
            owners.values().stream().flatMap(List::stream).forEach(owner ->
                    owner.clearWikidataRelationRouteMatches(
                            declaration.owner().className(), declaration.field().name()));
            Map<EntityRef, WikidataDynamicObject> references = references(
                    model, reachable, declaration);
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
                    List<domain.provenance.WikidataStatementSource> evidence =
                            statementSources(match.path());
                    WikidataDynamicObject reference = references.computeIfAbsent(
                            terminal, ignored -> new WikidataDynamicObject(
                                    terminal.id(), terminal.id()));
                    for (WikidataDynamicObject copy : owner.getValue()) {
                        // A route links; it does not admit. The reference stays bare, and
                        // the ordinary referent stamping decides its class exactly as for
                        // a property-loaded value. On a reference cycle, reuse the
                        // compatible typed member already in the pool; a genuinely new
                        // terminal remains bare and cannot grow the authored population.
                        if (mergeReference(copy, declaration.field().name(), reference)) {
                            values++;
                        }
                        copy.addWikidataRelationRouteMatch(
                                new domain.provenance.WikidataRelationRouteMatch(
                                        declaration.owner().className(),
                                        declaration.field().name(),
                                        match.route().id(), match.route().label(),
                                        new domain.provenance.WikidataSource(terminal.id()),
                                        domain.provenance.WikidataRelationRouteMatch.Status.MATCHED));
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

    private static Map<EntityRef, WikidataDynamicObject> references(
            GeneratedProjectModel model,
            List<WikidataDynamicObject> pool,
            Declaration declaration) {
        LinkedHashMap<EntityRef, WikidataDynamicObject> result = new LinkedHashMap<>();
        for (WikidataDynamicObject object : pool) {
            if (object == null || !object.hasTypeStamp()
                    || !WikidataIds.isQid(object.qid())
                    || !domain.config.EntityRepresentations.fieldAccepts(
                            model, declaration.field().entityClassName(),
                            object.directClassNames())) continue;
            result.putIfAbsent(EntityRef.wikidata(object.qid()), object);
        }
        return result;
    }

    /** Merge by source identity, replacing an older bare copy with the compatible typed
     * pool member without counting that replacement as a newly acquired route value. */
    private static boolean mergeReference(
            WikidataDynamicObject owner, String field, WikidataDynamicObject reference) {
        Object existing = owner.get(field);
        if (existing instanceof WikidataDynamicObject value
                && value.qid().equals(reference.qid())) {
            if (value != reference && !value.hasTypeStamp() && reference.hasTypeStamp()) {
                owner.put(field, reference);
            }
            return false;
        }
        if (existing instanceof List<?> values) {
            for (int index = 0; index < values.size(); index++) {
                Object value = values.get(index);
                if (value instanceof WikidataDynamicObject dynamic
                        && dynamic.qid().equals(reference.qid())) {
                    if (dynamic != reference && !dynamic.hasTypeStamp()
                            && reference.hasTypeStamp()) {
                        List<Object> replacement = new ArrayList<>(values);
                        replacement.set(index, reference);
                        owner.put(field, replacement);
                    }
                    return false;
                }
            }
        }
        owner.merge(field, reference);
        return true;
    }

    private static List<Declaration> declarations(
            GeneratedProjectModel model, SourceExecutionPlan plan) {
        List<Declaration> result = new ArrayList<>();
        for (SourceExecutionPlan.Step step : plan.steps(BindingScope.FIELD_VALUE)) {
            WikidataRelationRouteSpec spec = step.prepared()
                    .configuration(WikidataRelationRouteSpec.class);
            if (spec == null) continue;
            GeneratedClassModel owner = model.findClass(step.target().className());
            GeneratedFieldModel field = domain.config.OwnedFieldSources
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
            if (field.type() != domain.datasource.schema.FieldType.ENTITY
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
    static List<domain.provenance.WikidataStatementSource> statementSources(
            List<domain.datasource.graph.store.GraphEdge> path) {
        List<domain.provenance.WikidataStatementSource> sources = new ArrayList<>();
        for (int index = 0; index < path.size(); index++) {
            domain.datasource.graph.store.GraphEdge edge = path.get(index);
            domain.datasource.wikidata.WikidataRouteRelation relation =
                    domain.datasource.wikidata.WikidataRouteRelation.parse(edge.relation());
            if (relation == null || edge.provenanceId().isBlank()) continue;
            switch (relation.kind()) {
                case PROPERTY -> {
                    if (edge.target() instanceof EntityRef object) {
                        sources.add(new domain.provenance.WikidataStatementSource(
                                edge.provenanceId(), edge.source().id(), relation.pid(),
                                object.id(), ""));
                    }
                }
                case STATEMENT -> {
                    String subject = edge.source().id();
                    Map<String, List<String>> qualifiers = Map.of();
                    if (index + 1 < path.size()) {
                        domain.datasource.graph.store.GraphEdge next = path.get(index + 1);
                        domain.datasource.wikidata.WikidataRouteRelation read =
                                domain.datasource.wikidata.WikidataRouteRelation.parse(
                                        next.relation());
                        if (read != null && read.kind()
                                == domain.datasource.wikidata.WikidataRouteRelation.Kind.QUALIFIER
                                && next.target() instanceof EntityRef value) {
                            qualifiers = Map.of(read.pid(), List.of(value.id()));
                        }
                    }
                    sources.add(new domain.provenance.WikidataStatementSource(
                            edge.provenanceId(), subject, relation.pid(), "", "",
                            "normal", qualifiers, List.of()));
                }
                case QUALIFIER -> { }
            }
        }
        return List.copyOf(sources);
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
