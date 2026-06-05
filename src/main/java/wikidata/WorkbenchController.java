package wikidata;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.WikidataTripleSample;
import wikidata.explore.CommonsMedia;
import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyStore;
import wikidata.explore.WorkbenchState;
import wikidata.explore.ui.GroupedTripleTree;
import wikidata.explore.ui.OutputPanel;
import wikidata.query.WikidataExplorerQueries;
import wikidata.rule.WikidataRuleSpec;
import wikidata.rule.WikidataRuleSpecStore;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class WorkbenchController {
    private final WikidataPropertyStore propertyStore =
            new WikidataPropertyStore();

    private final WikidataSparqlClient client;
    private final WorkbenchState state;
    private final OutputPanel output;

    private GroupedTripleTree tripleTree;
    private SwingWorker<Void, Void> currentWorker;

    private String currentTaskName;

    public WorkbenchController(
            WikidataSparqlClient client,
            WorkbenchState state,
            OutputPanel output) {

        this.client = client;
        this.state = state;
        this.output = output;
        output.getCancelButton().addActionListener(e -> cancelCurrentQuery());
    }

    public void downloadPropertyCache(
            java.util.function.Consumer<
                    List<IncomingExtractionFrame.IncomingPropertyRow>> consumer) {

        String sparql = WikidataExplorerQueries.allPropertiesForCache();

        runAsync(
                "Download Wikidata property cache",
                sparql,
                () -> downloadPropertyCacheImpl(sparql, consumer));
    }

    public void loadPropertyCache(
            java.util.function.Consumer<
                    List<IncomingExtractionFrame.IncomingPropertyRow>> consumer) {

        runAsync(
                "Load local Wikidata property cache",
                null,
                () -> loadPropertyCacheImpl(consumer));
    }

    private void loadPropertyCacheImpl(
            java.util.function.Consumer<
                    List<IncomingExtractionFrame.IncomingPropertyRow>> consumer)
            throws Exception {

        List<WikidataProperty> properties = propertyStore.read();

        List<IncomingExtractionFrame.IncomingPropertyRow> rows =
                toIncomingPropertyRows(properties);

        SwingUtilities.invokeLater(() -> {
            output.append("Loaded property cache: "
                                  + properties.size()
                                  + " properties from "
                                  + propertyStore.file()
                                  + "\n");

            consumer.accept(rows);
        });
    }

    private void downloadPropertyCacheImpl(
            String sparql,
            java.util.function.Consumer<
                    List<IncomingExtractionFrame.IncomingPropertyRow>> consumer)
            throws Exception {

        List<WikidataProperty> properties = new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            String pid = b.qid("property");
            String label = b.label("property");
            String description = b.value("propertyDescription");

            if (pid != null && pid.matches("P\\d+")) {
                properties.add(new WikidataProperty(
                        pid,
                        label == null ? pid : label,
                        description == null ? "" : description));
            }
        }

        propertyStore.write(properties);

        List<IncomingExtractionFrame.IncomingPropertyRow> rows =
                toIncomingPropertyRows(properties);

        SwingUtilities.invokeLater(() -> {
            output.append("Saved property cache: "
                                  + properties.size()
                                  + " properties to "
                                  + propertyStore.file()
                                  + "\n");

            consumer.accept(rows);
        });
    }

    private static List<IncomingExtractionFrame.IncomingPropertyRow>
    toIncomingPropertyRows(List<WikidataProperty> properties) {

        List<IncomingExtractionFrame.IncomingPropertyRow> rows =
                new ArrayList<>();

        for (WikidataProperty p : properties) {
            rows.add(new IncomingExtractionFrame.IncomingPropertyRow(
                    false,
                    p.pid(),
                    p.pid(),
                    p.label(),
                    p.description(),
                    0));
        }

        return rows;
    }

    public void setTripleTree(GroupedTripleTree tripleTree) {
        this.tripleTree = tripleTree;

        tripleTree.setRelationSelectedHandler(sample -> {
            state.selectedRelation(sample);
            state.selectedValue(sample);
            refreshDraftPreview();
        });

        tripleTree.setValueDoubleClickedHandler(this::followTriple);
    }

    public void runAsync(
            String taskName,
            String sparql,
            Task task) {

        if (currentWorker != null && !currentWorker.isDone()) {
            output.append("Another query is already running: "
                                  + currentTaskName + "\n");
            return;
        }

        currentTaskName = taskName;
        output.setCurrentTask(taskName);
        output.getCancelButton().setEnabled(true);

        output.append("\n" + taskName + "\n");

        if (sparql != null && !sparql.isBlank()) {
            output.appendSparql(sparql);
        }

        output.append("Running...\n");

        currentWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                task.run();
                return null;
            }

            @Override
            protected void done() {
                output.getCancelButton().setEnabled(false);
                output.setCurrentTask("Idle");
                currentTaskName = null;

                try {
                    if (isCancelled()) {
                        output.append("Cancelled.\n");
                        return;
                    }

                    get();
                    output.append("Done.\n");
                } catch (Exception ex) {
                    output.append("ERROR: " + ex.getMessage() + "\n");
                    ex.printStackTrace();
                }
            }
        };

        currentWorker.execute();
    }

    public void cancelCurrentQuery() {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);

            try {
                client.cancelCurrentQuery();
            } catch (Exception ignored) {
            }

            output.append("Cancel requested...\n");
        }
    }

    public void loadOutgoingTriples() {
        String sparql =
                WikidataExplorerQueries.outgoingTriples(
                        state.rootQid(),
                        state.queryLimit(),
                        state.requireLabelBox.isSelected(),
                        state.minLengthKm(),
                        state.minAreaKm2());

        runAsync(
                "Load outgoing relations",
                sparql,
                () -> loadRelationRows(
                        sparql,
                        "ROOT_TO_ITEM",
                        "Outgoing triples"));
    }

    public void loadIncomingTriples() {
        String sparql =
                WikidataExplorerQueries.incomingTriples(
                        state.rootQid(),
                        state.queryLimit(),
                        state.requireLabelBox.isSelected(),
                        state.minLengthKm(),
                        state.minAreaKm2());

        runAsync(
                "Load incoming relations " + filterSummary(),
                sparql,
                () -> loadRelationRows(
                        sparql,
                        "ITEM_TO_ROOT",
                        "Incoming triples"));
    }

    public void loadTypes() {
        String sparql =
                WikidataExplorerQueries.instanceOfTypes(
                        state.rootQid());

        runAsync(
                "Load target types",
                sparql,
                () -> loadTypesImpl(sparql));
    }

    public void loadTypesOfSelectedRelationValue() {
        WikidataTripleSample sample = state.selectedValue();

        if (sample == null) {
            output.append("Select a relation/value first.\n");
            return;
        }

        loadTypesOfValue(sample);
    }

    public void loadTypesOfValue(WikidataTripleSample sample) {
        if (sample == null || sample.media()) {
            return;
        }

        String qid = sample.valueQid();

        if (qid == null || !qid.matches("Q\\d+")) {
            output.append("Cannot load types for non-Wikidata value: "
                                  + sample.valueLabel()
                                  + "\n");
            return;
        }

        String sparql =
                WikidataExplorerQueries.instanceOfTypes(qid);

        runAsync(
                "Load types of " + sample.valueLabel() + " (" + qid + ")",
                sparql,
                () -> loadTypesOfValueImpl(
                        sparql,
                        sample.valueLabel(),
                        qid));
    }

    private void loadTypesOfValueImpl(
            String sparql,
            String itemLabel,
            String itemQid)
            throws Exception {

        List<WikidataTripleSample> rows = new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            rows.add(new WikidataTripleSample(
                    "TYPE",
                    "P31",
                    "instance of",
                    b.qid("value"),
                    b.label("value")));
        }

        SwingUtilities.invokeLater(() -> {
            output.append("Types for "
                                  + itemLabel
                                  + " ("
                                  + itemQid
                                  + "): "
                                  + rows.size()
                                  + "\n");

            for (WikidataTripleSample row : rows) {
                output.append("  "
                                      + row.valueLabel()
                                      + " ("
                                      + row.valueQid()
                                      + ")\n");
            }

            state.typesModel.clear();
            rows.forEach(state.typesModel::addElement);
        });
    }

    private void loadTypesImpl(String sparql) throws Exception {
        List<WikidataTripleSample> rows = new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            rows.add(new WikidataTripleSample(
                    "TYPE",
                    "P31",
                    "instance of",
                    b.qid("value"),
                    b.label("value")));
        }

        SwingUtilities.invokeLater(() -> {
            state.typesModel.clear();
            rows.forEach(state.typesModel::addElement);
            output.append("Types for " + state.rootName()
                                  + ": " + rows.size() + "\n");
        });
    }

    private void loadRelationRows(
            String sparql,
            String direction,
            String title) throws Exception {

        List<WikidataTripleSample> rows = new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            String pid = b.qid("property");
            String propertyLabel = b.label("property");

            String valueRaw = b.value("value");
            String valueQid = b.qid("value");

            if (valueQid != null && !valueQid.matches("Q\\d+")) {
                valueQid = null;
            }

            String valueLabel = b.label("value");

            boolean media =
                    CommonsMedia.isMediaProperty(pid)
                            || CommonsMedia.isImageFilename(valueRaw)
                            || CommonsMedia.isImageFilename(valueLabel);

            if (media) {
                String mediaSource = firstNonBlank(valueRaw, valueLabel, null);
                String label = CommonsMedia.fileName(mediaSource);

                String mediaUrl = CommonsMedia.filePathUrl(mediaSource);

                rows.add(new WikidataTripleSample(
                        direction,
                        pid,
                        propertyLabel,
                        null,
                        label,
                        true,
                        mediaUrl));
            } else if (pid != null && valueQid != null) {
                rows.add(new WikidataTripleSample(
                        direction,
                        pid,
                        propertyLabel,
                        valueQid,
                        valueLabel));
            } else if (pid != null && valueRaw != null && !valueRaw.isBlank()) {
                rows.add(new WikidataTripleSample(
                        direction,
                        pid,
                        propertyLabel,
                        null,
                        valueRaw,
                        false,
                        null));
            }
        }

        SwingUtilities.invokeLater(() -> {
            tripleTree.setTriples(rows);
            output.append(title + " for " + state.rootName()
                                  + ": " + rows.size() + "\n");
        });
    }

    private String filterSummary() {
        StringBuilder sb = new StringBuilder();

        if (state.requireLabelBox.isSelected()) {
            sb.append("[label] ");
        }

        if (state.minLengthKm() != null) {
            sb.append("[length >= ")
              .append(state.minLengthKm())
              .append(" km] ");
        }

        if (state.minAreaKm2() != null) {
            sb.append("[area >= ")
              .append(state.minAreaKm2())
              .append(" km²] ");
        }

        return sb.toString().trim();
    }

    public void addManualType() {
        String qid = state.manualTypeQidField.getText().trim();
        String label = state.manualTypeLabelField.getText().trim();

        if (qid.startsWith("wd:")) {
            qid = qid.substring(3);
        }

        if (qid.isBlank()) {
            return;
        }

        state.typesModel.addElement(new WikidataTripleSample(
                "TYPE",
                "P31",
                "instance of",
                qid,
                label.isBlank() ? qid : label));

        refreshDraftPreview();
    }

    public void goBack() {
        if (state.history.isEmpty()) {
            output.append("No previous root.\n");
            return;
        }

        WorkbenchState.RootRef previous = state.history.pop();

        state.rootNameField.setText(previous.name());
        state.rootQidField.setText(previous.qid());

        loadTypes();
        loadOutgoingTriples();
    }

    private void followTriple(WikidataTripleSample sample) {
        if (sample == null || sample.media()) {
            return;
        }

        String qid = sample.valueQid();

        if (qid == null || !qid.matches("Q\\d+")) {
            output.append("Cannot follow non-Wikidata value: "
                                  + sample.valueLabel()
                                  + " from "
                                  + sample.propertyLabel()
                                  + " ("
                                  + sample.propertyPid()
                                  + ")\n");
            return;
        }

        state.history.push(new WorkbenchState.RootRef(
                state.rootName(),
                state.rootQid()));

        state.rootNameField.setText(sample.valueLabel());
        state.rootQidField.setText(qid);

        loadTypes();
        loadOutgoingTriples();
    }

    public void previewDraftRule() {
        try {
            WikidataRuleSpec spec = buildSpecFromSelection();
            String sparql =
                    spec.commentedQuery(
                            state.rootName(),
                            state.rootQid());

            output.setRuleInfo(ruleInfo(spec));

            runAsync(
                    "Preview draft rule",
                    sparql,
                    () -> {});
        } catch (Exception e) {
            output.append("Cannot preview rule: "
                                  + e.getMessage() + "\n");
        }
    }

    public void refreshDraftPreview() {
        try {
            WikidataRuleSpec spec = buildSpecFromSelection();
            output.setRuleInfo(ruleInfo(spec));
        } catch (Exception ignored) {
            output.setRuleInfo("");
        }
    }

    public void testDraftRule() {
        try {
            WikidataRuleSpec spec = buildSpecFromSelection();
            testRule(spec);
        } catch (Exception e) {
            output.append("Cannot test draft rule: "
                                  + e.getMessage() + "\n");
        }
    }

    public void saveDraftRule() {
        try {
            WikidataRuleSpec spec = buildSpecFromSelection();

            runAsync(
                    "Save draft rule",
                    null,
                    () -> saveRuleImpl(spec));
        } catch (Exception e) {
            output.append("Cannot save draft rule: "
                                  + e.getMessage() + "\n");
        }
    }

    private void saveRuleImpl(WikidataRuleSpec spec) throws Exception {
        state.specs.add(spec);
        WikidataRuleSpecStore.write(state.specFile(), state.specs);

        SwingUtilities.invokeLater(() -> {
            state.specsModel.addElement(spec);
            output.append("Saved rule: " + spec + "\n");
        });
    }

    public void removeSelectedSavedRule() {
        WikidataRuleSpec selected = state.specsList.getSelectedValue();

        if (selected == null) {
            output.append("Select a saved rule first.\n");
            return;
        }

        runAsync(
                "Remove saved rule",
                null,
                () -> removeSavedRuleImpl(selected));
    }
    public void loadIncomingPropertySummary(
            String rootQid,
            int limit,
            java.util.function.Consumer<
                    List<IncomingExtractionFrame.IncomingPropertyRow>> consumer) {

        String sparql =
                WikidataExplorerQueries.incomingPropertySummary(rootQid, limit);

        runAsync(
                "Discover incoming properties for " + rootQid,
                sparql,
                () -> loadIncomingPropertySummaryImpl(sparql, consumer));
    }

    private void loadIncomingPropertySummaryImpl(
            String sparql,
            java.util.function.Consumer<
                    List<IncomingExtractionFrame.IncomingPropertyRow>> consumer)
            throws Exception {

        List<IncomingExtractionFrame.IncomingPropertyRow> rows =
                new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            String propertyQid = b.qid("property");
            String label = b.label("property");
            String pUri = b.value("p");
            String pid = directClaimUriToPid(pUri);

            long count = 0;
            try {
                count = Long.parseLong(b.value("count"));
            } catch (Exception ignored) {
            }

            rows.add(new IncomingExtractionFrame.IncomingPropertyRow(
                    false,
                    pid,
                    propertyQid,
                    label,
                    count));
        }

        SwingUtilities.invokeLater(() -> {
            output.append("Incoming property groups: " + rows.size() + "\n");
            consumer.accept(rows);
        });
    }

    public void loadIncomingValuesForProperties(
            String rootQid,
            List<IncomingExtractionFrame.IncomingPropertyRow> properties,
            int limit,
            boolean requireLabel,
            boolean includeMedia,
            String excludeTypeQid) {

        if (properties == null || properties.isEmpty()) {
            output.append("No incoming properties selected.\n");
            return;
        }
        if (properties.size() == 1
                && "P31".equals(properties.get(0).pid())) {

            addTypeIfMissing(rootQid, state.rootName());
        }
        String excludeFilter = "";

        if (excludeTypeQid != null
                && !excludeTypeQid.isBlank()) {

            excludeFilter = """
            FILTER NOT EXISTS {
              ?value wdt:P31 wd:%s .
            }
            """.formatted(excludeTypeQid);
        }
        for (IncomingExtractionFrame.IncomingPropertyRow property : properties) {
            String sparql =
                    WikidataExplorerQueries.incomingValuesForProperty(
                            rootQid,
                            property.pid(),
                            limit,
                            excludeFilter,
                            requireLabel,
                            includeMedia
                            );

            runAsync(
                    "Load incoming values for "
                            + property.label()
                            + " ("
                            + property.pid()
                            + ")",
                    sparql,
                    () -> loadIncomingValuesForPropertyImpl(
                            sparql,
                            property));
        }
    }

    private void addTypeIfMissing(String qid, String label) {
        if (qid == null || qid.isBlank()) {
            return;
        }

        if (qid.startsWith("wd:")) {
            qid = qid.substring(3);
        }

        final String cleanQid = qid;
        final String cleanLabel =
                label == null || label.isBlank() ? cleanQid : label;

        for (int i = 0; i < state.typesModel.size(); i++) {
            WikidataTripleSample existing = state.typesModel.getElementAt(i);

            if (cleanQid.equals(existing.valueQid())) {
                return;
            }
        }

        state.typesModel.addElement(new WikidataTripleSample(
                "TYPE",
                "P31",
                "instance of",
                cleanQid,
                cleanLabel));

        output.append("Added target type automatically: "
                              + cleanLabel
                              + " ("
                              + cleanQid
                              + ")\n");
    }

    private void loadIncomingValuesForPropertyImpl(
            String sparql,
            IncomingExtractionFrame.IncomingPropertyRow property)
            throws Exception {

        List<WikidataTripleSample> rows = new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            String valueRaw = b.value("value");
            String valueQid = b.qid("value");
            String valueLabel = b.label("value");

            if (valueQid != null && !valueQid.matches("Q\\d+")) {
                valueQid = null;
            }

            String imageRaw = b.value("image");

            if (imageRaw != null && !imageRaw.isBlank()) {
                String label = CommonsMedia.fileName(imageRaw);
                String mediaUrl = CommonsMedia.filePathUrl(imageRaw);

                rows.add(new WikidataTripleSample(
                        "ITEM_TO_ROOT",
                        "P18",
                        "image",
                        null,
                        label,
                        true,
                        mediaUrl));
            }

            if (valueQid != null) {
                rows.add(new WikidataTripleSample(
                        "ITEM_TO_ROOT",
                        property.pid(),
                        property.label(),
                        valueQid,
                        valueLabel));
            } else if (valueRaw != null && !valueRaw.isBlank()) {
                rows.add(new WikidataTripleSample(
                        "ITEM_TO_ROOT",
                        property.pid(),
                        property.label(),
                        null,
                        valueRaw,
                        false,
                        null));
            }
        }

        SwingUtilities.invokeLater(() -> {
            tripleTree.setTriples(rows);
            output.append("Incoming values for "
                                  + property.label()
                                  + " ("
                                  + property.pid()
                                  + "): "
                                  + rows.size()
                                  + "\n");
        });
    }

    private static String directClaimUriToPid(String uri) {
        if (uri == null || uri.isBlank()) {
            return "";
        }

        int i = uri.lastIndexOf('/');
        String last = i >= 0 ? uri.substring(i + 1) : uri;

        return last.startsWith("P") ? last : "";
    }

    private void removeSavedRuleImpl(WikidataRuleSpec selected)
            throws Exception {

        state.specs.remove(selected);
        WikidataRuleSpecStore.write(state.specFile(), state.specs);

        SwingUtilities.invokeLater(() -> {
            state.specsModel.removeElement(selected);
            output.append("Removed rule: " + selected.name() + "\n");
        });
    }

    public void testSelectedSavedRule() {
        WikidataRuleSpec selected = state.specsList.getSelectedValue();

        if (selected == null) {
            output.append("Select a saved rule first.\n");
            return;
        }

        testRule(selected);
    }

    private void testRule(WikidataRuleSpec spec) {
        String sparql =
                spec.commentedQuery(
                        state.rootName(),
                        state.rootQid());

        output.setRuleInfo(ruleInfo(spec));

        runAsync(
                "Test rule",
                sparql,
                () -> testRuleImpl(spec));
    }

    private void testRuleImpl(WikidataRuleSpec spec) throws Exception {
        List<WikidataBinding> rows =
                client.query(spec.toSparql(state.rootQid()));

        SwingUtilities.invokeLater(() -> {
            output.append("Rows=" + rows.size() + "\n");

            rows.stream().limit(50)
                .forEach(b -> output.append("  "
                                                    + b.label(spec.itemVar())
                                                    + " "
                                                    + b.qid(spec.itemVar())
                                                    + "\n"));
        });
    }

    private WikidataRuleSpec buildSpecFromSelection() {
        WikidataTripleSample relation = state.selectedRelation();
        List<WikidataTripleSample> selectedTypes =
                state.typesList.getSelectedValuesList();

        if (relation == null) {
            throw new IllegalStateException(
                    "Select a relation/property group first.");
        }

        if (selectedTypes.isEmpty()) {
            throw new IllegalStateException(
                    "Select at least one target type.");
        }

        List<String> typeQids = selectedTypes.stream()
                                             .map(WikidataTripleSample::valueQid)
                                             .filter(s -> s != null && !s.isBlank())
                                             .distinct()
                                             .toList();

        return new WikidataRuleSpec(
                state.ruleNameField.getText().trim(),
                state.rootName(),
                state.rootQid(),
                state.itemVarField.getText().trim(),
                relation.propertyPid(),
                relation.direction(),
                typeQids,
                state.subclassClosureBox.isSelected());
    }

    private String ruleInfo(WikidataRuleSpec spec) {
        WikidataTripleSample relation = state.selectedRelation();

        StringBuilder sb = new StringBuilder();

        sb.append("Rule\n");
        sb.append("  name: ").append(spec.name()).append("\n");
        sb.append("  root: ")
          .append(state.rootName())
          .append(" (")
          .append(state.rootQid())
          .append(")\n");
        sb.append("  item variable: ")
          .append(spec.itemVar())
          .append("\n\n");

        sb.append("Relation\n");

        if (relation == null) {
            sb.append("  <none selected>\n\n");
        } else {
            sb.append("  property: ")
              .append(nullToUnknown(relation.propertyLabel()))
              .append(" (")
              .append(nullToUnknown(relation.propertyPid()))
              .append(")\n");

            sb.append("  direction: ")
              .append(nullToUnknown(relation.direction()))
              .append("\n\n");
        }

        sb.append("Target types\n");

        List<WikidataTripleSample> selectedTypes =
                state.typesList.getSelectedValuesList();

        if (selectedTypes.isEmpty()) {
            sb.append("  <none selected>\n");
        } else {
            for (WikidataTripleSample t : selectedTypes) {
                sb.append("  ")
                  .append(nullToUnknown(t.valueLabel()))
                  .append(" (")
                  .append(nullToUnknown(t.valueQid()))
                  .append(")\n");
            }
        }

        sb.append("\nMeaning\n");
        sb.append(spec.meaning(state.rootName(), state.rootQid()));

        return sb.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    private static String nullToUnknown(String s) {
        return s == null || s.isBlank() ? "?" : s;
    }

    public interface Task {
        void run() throws Exception;
    }
}