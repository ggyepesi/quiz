package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.explore.query.template.rule.RuleIncludedFieldSparql;
import wikidata.explore.query.template.rule.RuleTreeQueries;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;
import wikidata.WikidataBinding;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.model.FieldSampleContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SampleFieldQuery implements Query<TableQueryResult> {

    private final FieldSampleContext sampleContext;
    private final int sampleLimit;

    public SampleFieldQuery(
            FieldSampleContext sampleContext,
            int sampleLimit) {

        this.sampleContext = sampleContext;
        this.sampleLimit = Math.max(1, sampleLimit);
    }

    @Override
    public String purpose() {
        return "Sample selected field";
    }

    @Override
    public String skeleton() {
        return "sample parent class instances -> sample selected field values";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();

        if (sampleContext != null && sampleContext.ownerClass() != null) {
            p.put("ownerClass", sampleContext.ownerClass().className());
        }

        if (sampleContext != null && sampleContext.field() != null) {
            p.put("field", sampleContext.field().name());
        }

        return p;
    }

    @Override
    public TableQueryResult execute(QueryContext context) throws Exception {
        if (sampleContext == null || sampleContext.field() == null) {
            return empty();
        }

        RuleNode parentSample =
                RuleTreeCompiler.compileClass(sampleContext.ownerClass())
                                .sampleCopy(sampleLimit);

        RuleIncludedField includedField =
                RuleTreeCompiler.compileField(sampleContext.field());

        if (includedField == null) {
            return empty();
        }

        String ownerName =
                sampleContext.ownerClass().className();

        String fieldPid =
                includedField.propertyPid() != null
                        ? includedField.propertyPid()
                        : "?";

        String propLabel =
                includedField.propertyLabel() != null
                        && !includedField.propertyLabel().isBlank()
                        ? includedField.propertyLabel()
                        : sampleContext.field().name();

        String parentSparql =
                RuleTreeQueries.valuesQueryWithoutIncludedFields(parentSample);

        List<Parent> parents = context.step(
                "Sample parent instances",
                "SPARQL",
                null,
                Map.of("sampleLimit", String.valueOf(sampleLimit)),
                step -> {
                    step.request(parentSparql);

                    List<Parent> result = new ArrayList<>();

                    for (WikidataBinding b : context.sparql().query(parentSparql)) {
                        String qid = b.qid("value");
                        String label = b.label("value");

                        if (qid != null && WikidataIds.isQid(qid)) {
                            result.add(new Parent(qid, label == null ? "" : label));
                        }
                    }

                    step.summary(result.size() + " parents");
                    return result;
                });

        if (parents.isEmpty()) {
            return new TableQueryResult(
                    columns(ownerName, fieldPid, propLabel),
                    List.of());
        }

        List<String> parentQids =
                parents.stream()
                       .map(Parent::qid)
                       .toList();

        String sparql =
                RuleTreeQueries.fieldValueSampleQuery(
                        includedField,
                        parentQids,
                        parentSample.labelConfig(),
                        sampleLimit * 3);

        Map<String, Parent> parentByQid =
                new LinkedHashMap<>();

        for (Parent p : parents) {
            parentByQid.put(p.qid(), p);
        }

        String var =
                RuleIncludedFieldSparql.variableName(
                        includedField,
                        0);

        Map<String, String> fieldParams = new LinkedHashMap<>();
        fieldParams.put("pid", fieldPid);
        fieldParams.put("sampleLimit", String.valueOf(sampleLimit));

        List<List<Object>> rows = context.step(
                "Sample field values: " + sampleContext.field().name(),
                "SPARQL",
                null,
                fieldParams,
                step -> {
                    step.request(sparql);

                    List<List<Object>> result = new ArrayList<>();

                    for (WikidataBinding b : context.sparql().query(sparql)) {
                        String parentQid = b.qid("parent");
                        Parent parent = parentByQid.get(parentQid);

                        if (parent == null) {
                            continue;
                        }

                        String value =
                                includedField.isMediaField()
                                        ? b.value(var)
                                        : firstNonBlank(
                                        b.qid(var),
                                        b.value(var));

                        String label =
                                b.value(var + "Label");

                        result.add(List.of(
                                parent.qid(),
                                parent.label(),
                                value == null ? "" : value,
                                label == null ? "" : label));
                    }

                    step.summary(result.size() + " values");
                    return result;
                });

        return new TableQueryResult(
                columns(ownerName, fieldPid, propLabel),
                rows);
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(TableQueryResult result) {
        return rowCount(result) + " values";
    }

    private static List<String> columns(
            String ownerName,
            String fieldPid,
            String propLabel) {

        return List.of(
                ownerName + " QID",
                ownerName,
                fieldPid + " · " + propLabel,
                propLabel + " label");
    }

    private static TableQueryResult empty() {
        return new TableQueryResult(
                List.of("Parent QID", "Parent label", "Value", "Value label"),
                List.of());
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private record Parent(String qid, String label) {
    }
}