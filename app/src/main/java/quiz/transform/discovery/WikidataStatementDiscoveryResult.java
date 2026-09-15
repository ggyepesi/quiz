package quiz.transform.discovery;

import objectview.Viewable;
import objectview.field.DynamicFieldSet;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import quiz.source.SourceIdentities;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.statement.EntityStatementSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ObjectView-ready statement trees with one union schema derived by SnapshotDomain. */
public final class WikidataStatementDiscoveryResult {
    private WikidataStatementDiscoveryResult() { }

    public static List<WikidataDynamicObject> materialize(
            List<EntityStatementSummary> summaries,
            List<? extends Viewable> loadedInstances) {
        Map<String, Viewable> loaded = new LinkedHashMap<>();
        if (loadedInstances != null) for (Viewable value : loadedInstances) {
            String qid = SourceIdentities.wikidataQid(value);
            if (qid != null) loaded.putIfAbsent(qid, value);
        }
        Map<String, WikidataDynamicObject> external = new LinkedHashMap<>();
        List<WikidataDynamicObject> roots = new ArrayList<>();
        if (summaries == null) return List.of();
        for (EntityStatementSummary entity : summaries) {
            Viewable original = loaded.get(entity.qid());
            WikidataDynamicObject root = new WikidataDynamicObject(entity.qid(),
                    original == null ? entity.qid() : original.getDisplayName());
            root.type("Wikidata statement discovery");
            Map<String, String> propertyLabels = new LinkedHashMap<>();
            for (EntityStatementSummary.Property property : entity.properties()) {
                String field = property.pid();
                propertyLabels.put(field, named(property.label(), property.pid()));
                for (EntityStatementSummary.Statement statement : property.statements()) {
                    WikidataDynamicObject occurrence = new WikidataDynamicObject(
                            statementIdentifier(entity.qid(), property.pid(), statement.id()),
                            field + " statement");
                    occurrence.type("Wikidata statement");
                    occurrence.part(true);
                    occurrence.put("Property", property.pid());
                    occurrence.put("Value", value(statement.value(), statement.valueQid(),
                            loaded, external));
                    Map<String, String> statementLabels = new LinkedHashMap<>();
                    for (EntityStatementSummary.Qualifier qualifier : statement.qualifiers()) {
                        occurrence.merge(qualifier.pid(),
                                value(qualifier.value(), qualifier.valueQid(), loaded, external));
                        statementLabels.put(qualifier.pid(),
                                named(qualifier.label(), qualifier.pid()));
                    }
                    occurrence.dynamicFieldSchema(schema(occurrence, statementLabels));
                    root.merge(field, occurrence);
                }
            }
            root.dynamicFieldSchema(schema(root, propertyLabels));
            roots.add(root);
        }
        return List.copyOf(roots);
    }

    /** One non-result carrier containing every root field and every nested qualifier field. */
    public static WikidataDynamicObject unionSample(List<WikidataDynamicObject> roots) {
        // The shape sample must be a normal QID-backed instance too. ObjectView builds
        // the visible field configuration from this carrier; a synthetic identifier has
        // no declared wikidataSource value and therefore hid that field from every real
        // result card even though the cards themselves carried it.
        WikidataDynamicObject sample = new WikidataDynamicObject("Q1",
                "Discovered statement shape");
        sample.type("Wikidata statement discovery");
        Map<String, List<WikidataDynamicObject>> statementsByProperty = new LinkedHashMap<>();
        Map<String, String> propertyLabels = new LinkedHashMap<>();
        if (roots != null) for (WikidataDynamicObject root : roots) {
            root.dynamicFields().forEach((property, value) -> {
                    propertyLabels.putIfAbsent(property, label(root.dynamicFieldSchema(), property));
                    collectStatements(value, statementsByProperty
                            .computeIfAbsent(property, ignored -> new ArrayList<>()));
            });
        }
        statementsByProperty.forEach((property, statements) -> {
            WikidataDynamicObject statementShape = new WikidataDynamicObject(
                    "__statement_union__:" + property, property);
            statementShape.type("Wikidata statement");
            Map<String, String> statementLabels = new LinkedHashMap<>();
            for (WikidataDynamicObject statement : statements) {
                statement.dynamicFields().forEach((field, value) -> {
                    if (statementShape.get(field) == null) statementShape.put(field, value);
                    statementLabels.putIfAbsent(field,
                            label(statement.dynamicFieldSchema(), field));
                });
            }
            statementShape.dynamicFieldSchema(schema(statementShape, statementLabels));
            sample.put(property, statementShape);
        });
        sample.dynamicFieldSchema(schema(sample, propertyLabels));
        return sample;
    }

    /** Dynamic map keys are stable machine keys; Wikidata labels are presentation only. */
    private static FieldSchema schema(
            WikidataDynamicObject value, Map<String, String> labels) {
        List<FieldRef> fields = new DynamicFieldSet(value).fields().stream()
                .map(field -> FieldRef.described(
                        field.name(), labels.getOrDefault(field.name(), field.label()),
                        field.role(), field.kind(), field.valueKind(), field.typeLabel(),
                        field.reference(), field.collection(), field.targetType(),
                        field.structural(), field.minor(), field.inline(), field.embedded(),
                        field.link(), field.linkText(), field.annotatedReference()))
                .toList();
        return () -> fields;
    }

    private static String label(FieldSchema schema, String key) {
        FieldRef field = schema == null ? null : schema.field(key);
        return field == null ? key : field.label();
    }

    private static void collectStatements(Object value, List<WikidataDynamicObject> out) {
        if (value instanceof WikidataDynamicObject statement) out.add(statement);
        else if (value instanceof Iterable<?> values) for (Object item : values) {
            if (item instanceof WikidataDynamicObject statement) out.add(statement);
        }
    }

    private static Object value(String label, String qid, Map<String, Viewable> loaded,
                                Map<String, WikidataDynamicObject> external) {
        if (!WikidataIds.isQid(qid)) return label;
        Viewable existing = loaded.get(qid);
        if (existing != null) return existing;
        return external.computeIfAbsent(qid,
                key -> new WikidataDynamicObject(key, label));
    }

    private static String named(String label, String pid) {
        return label == null || label.isBlank() || label.equals(pid)
                ? pid : label + " (" + pid + ")";
    }

    private static String statementIdentifier(String qid, String pid, String statementId) {
        if (statementId != null && !statementId.isBlank()) {
            int slash = statementId.lastIndexOf('/');
            return slash >= 0 ? statementId.substring(slash + 1) : statementId;
        }
        return qid + "$" + pid;
    }
}
