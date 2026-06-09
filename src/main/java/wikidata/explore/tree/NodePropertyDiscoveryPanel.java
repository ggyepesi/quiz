package wikidata.explore.tree;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Discovers which Wikidata properties the result entities of a RuleNode
 * actually have, with value types, frequency, sample value, quantity unit,
 * and a recommended use in the Rule Tree.
 *
 * Recommendation rule:
 *
 *   ENTITY + repeated values       -> Child edge
 *   ENTITY + single-ish value      -> Included entity field
 *   MEDIA                          -> Included media field
 *   QUANTITY/STRING/TIME/URL/etc.  -> Included scalar field
 *
 * The recommendation is guidance only. The user can still add a property as
 * either a child edge or an included field.
 */
public class NodePropertyDiscoveryPanel extends JPanel {

    private static final int SAMPLE_QID_LIMIT =
            10;

    private static final int PROPERTY_RESULT_LIMIT =
            80;

    private static final String TYPE_ITEM =
            "http://wikiba.se/ontology#WikibaseItem";
    private static final String TYPE_STRING =
            "http://wikiba.se/ontology#String";
    private static final String TYPE_MONO_TEXT =
            "http://wikiba.se/ontology#Monolingualtext";
    private static final String TYPE_QUANTITY =
            "http://wikiba.se/ontology#Quantity";
    private static final String TYPE_TIME =
            "http://wikiba.se/ontology#Time";
    private static final String TYPE_URL =
            "http://wikiba.se/ontology#Url";
    private static final String TYPE_COMMONS =
            "http://wikiba.se/ontology#CommonsMedia";
    private static final String TYPE_GLOBE =
            "http://wikiba.se/ontology#GlobeCoordinate";
    private static final String TYPE_EXT_ID =
            "http://wikiba.se/ontology#ExternalId";
    private static final String TYPE_MATH =
            "http://wikiba.se/ontology#Math";

    private WikidataSparqlClient client;
    private Consumer<String> log = s -> {};
    private Runnable applyEdits =
            () -> {
            };
    private Supplier<RuleNode> nodeSupplier =
            () -> null;

    private Consumer<DiscoveredProperty> onAddChildEdge =
            p -> {
            };

    private Consumer<DiscoveredProperty> onAddIncludedField =
            p -> {
            };

    private Consumer<String> onAddAllowedQid =
            qid -> {
            };

    private Consumer<String> onAddExcludedQid =
            qid -> {
            };

    private final JButton discoverButton =
            new JButton("Run discovery");

    private final JLabel nodeTitleLabel =
            new JLabel("Current node: none");

    private final JLabel statusLabel =
            new JLabel(" ");

    private final JTextField searchField =
            new JTextField(20);

    private final List<DiscoveredProperty> properties =
            new ArrayList<>();

    private final PropertyTableModel tableModel =
            new PropertyTableModel(properties);

    private final TableRowSorter<PropertyTableModel> sorter =
            new TableRowSorter<>(tableModel);

    private final JTable table =
            new JTable(tableModel);

    public NodePropertyDiscoveryPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void setClient(WikidataSparqlClient client) {
        this.client = client;
        if (client != null) client.registerRunButton(discoverButton);
    }

    public void setApplyEdits(Runnable applyEdits) {
        this.applyEdits =
                applyEdits == null ? () -> {
                } : applyEdits;
    }

    public void setNodeSupplier(Supplier<RuleNode> supplier) {
        this.nodeSupplier =
                supplier == null ? () -> null : supplier;

        refreshNodeTitle();
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public void onAddChildEdge(Consumer<DiscoveredProperty> handler) {
        this.onAddChildEdge =
                handler == null ? p -> {
                } : handler;
    }

    public void onAddIncludedField(Consumer<DiscoveredProperty> handler) {
        this.onAddIncludedField =
                handler == null ? p -> {
                } : handler;
    }

    public void onAddAllowedQid(Consumer<String> handler) {
        this.onAddAllowedQid =
                handler == null ? qid -> {
                } : handler;
    }

    public void onAddExcludedQid(Consumer<String> handler) {
        this.onAddExcludedQid =
                handler == null ? qid -> {
                } : handler;
    }

    public void refreshNodeTitle() {
        RuleNode node =
                nodeSupplier == null ? null : nodeSupplier.get();

        if (node == null) {
            nodeTitleLabel.setText("Current node: none");
            return;
        }

        nodeTitleLabel.setText(
                "Current node: "
                        + safe(node.name(), "Node")
                        + "    source: "
                        + safe(node.sourceLabel(), "")
                        + qidSuffix(node.sourceQid())
                        + "    property: "
                        + safe(node.propertyLabel(), "")
                        + pidSuffix(node.propertyPid()));
    }

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setRowSorter(sorter);

        table.getColumnModel().getColumn(PropertyTableModel.COL_PID)
             .setPreferredWidth(60);
        table.getColumnModel().getColumn(PropertyTableModel.COL_LABEL)
             .setPreferredWidth(160);
        table.getColumnModel().getColumn(PropertyTableModel.COL_TYPE)
             .setPreferredWidth(80);
        table.getColumnModel().getColumn(PropertyTableModel.COL_COUNT)
             .setPreferredWidth(70);
        table.getColumnModel().getColumn(PropertyTableModel.COL_UNIT)
             .setPreferredWidth(90);
        table.getColumnModel().getColumn(PropertyTableModel.COL_RECOMMENDED)
             .setPreferredWidth(135);
        table.getColumnModel().getColumn(PropertyTableModel.COL_EXAMPLE)
             .setPreferredWidth(180);
        table.getColumnModel().getColumn(PropertyTableModel.COL_ADD_CHILD)
             .setPreferredWidth(105);
        table.getColumnModel().getColumn(PropertyTableModel.COL_ADD_FIELD)
             .setPreferredWidth(120);
        table.getColumnModel().getColumn(PropertyTableModel.COL_ALLOW_QID)
             .setPreferredWidth(95);
        table.getColumnModel().getColumn(PropertyTableModel.COL_EXCLUDE_QID)
             .setPreferredWidth(95);

        setButtonColumn(
                PropertyTableModel.COL_ADD_CHILD,
                "＋ Child",
                row -> onAddChildEdge.accept(modelPropertyAt(row)));

        setButtonColumn(
                PropertyTableModel.COL_ADD_FIELD,
                "＋ Field",
                row -> onAddIncludedField.accept(modelPropertyAt(row)));

        setButtonColumn(
                PropertyTableModel.COL_ALLOW_QID,
                "Allow QID",
                row -> {
                    String qid = modelPropertyAt(row).exampleQid();
                    if (qid != null && qid.matches("Q\\d+")) {
                        onAddAllowedQid.accept(qid);
                    }
                });

        setButtonColumn(
                PropertyTableModel.COL_EXCLUDE_QID,
                "Exclude QID",
                row -> {
                    String qid = modelPropertyAt(row).exampleQid();
                    if (qid != null && qid.matches("Q\\d+")) {
                        onAddExcludedQid.accept(qid);
                    }
                });

        JPanel top =
                new JPanel(new BorderLayout(6, 2));

        JPanel actionRow =
                new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        actionRow.add(discoverButton);
        actionRow.add(new JLabel("Search:"));
        actionRow.add(searchField);
        actionRow.add(statusLabel);

        JLabel hint =
                new JLabel("Recommended use is inferred from property type and observed multiplicity. Entity properties with repeated values are usually child edges.");

        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));

        nodeTitleLabel.setFont(nodeTitleLabel.getFont().deriveFont(Font.BOLD));

        top.add(nodeTitleLabel, BorderLayout.NORTH);
        top.add(actionRow, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        discoverButton.setEnabled(false);
        discoverButton.addActionListener(e -> runDiscovery());

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { updateFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { updateFilter(); }
        });
    }

    private void setButtonColumn(
            int column,
            String label,
            java.util.function.IntConsumer action) {

        table.getColumnModel()
             .getColumn(column)
             .setCellRenderer(new ButtonRenderer());

        table.getColumnModel()
             .getColumn(column)
             .setCellEditor(new ButtonEditor(label, row -> {
                 int modelRow =
                         table.convertRowIndexToModel(row);

                 if (modelRow >= 0 && modelRow < properties.size()) {
                     action.accept(modelRow);
                 }
             }));
    }

    private DiscoveredProperty modelPropertyAt(int modelRow) {
        return properties.get(modelRow);
    }

    private void updateFilter() {
        String text =
                searchField.getText();

        if (text == null || text.isBlank()) {
            sorter.setRowFilter(null);
            return;
        }

        sorter.setRowFilter(
                RowFilter.regexFilter("(?i)" + Pattern.quote(text.trim())));
    }

    private void runDiscovery() {
        if (client == null) {
            return;
        }

        applyEdits.run();
        refreshNodeTitle();

        RuleNode node =
                nodeSupplier.get();

        if (node == null) {
            statusLabel.setText("No node selected.");
            return;
        }

        statusLabel.setText("Fetching sample QIDs...");
        properties.clear();
        tableModel.fireTableDataChanged();

        SwingWorker<List<DiscoveredProperty>, String> worker =
                new SwingWorker<>() {

                    @Override
                    protected List<DiscoveredProperty> doInBackground()
                            throws Exception {

                        publish("Fetching "
                                        + SAMPLE_QID_LIMIT
                                        + " sample results for "
                                        + node.name());

                        List<String> qids =
                                fetchSampleQids(node);

                        if (qids.isEmpty()) {
                            publish("No results found for current node config.");
                            return List.of();
                        }

                        publish("Got "
                                        + qids.size()
                                        + " sample QIDs. Profiling properties...");

                        List<DiscoveredProperty> discovered =
                                profileProperties(qids, qids.size());

                        publish("Found "
                                        + discovered.size()
                                        + " properties.");

                        return discovered;
                    }

                    @Override
                    protected void process(List<String> chunks) {
                        if (!chunks.isEmpty()) {
                            statusLabel.setText(chunks.get(chunks.size() - 1));
                        }
                    }

                    @Override
                    protected void done() {
                        try {
                            List<DiscoveredProperty> result =
                                    get();

                            properties.clear();
                            properties.addAll(result);
                            tableModel.fireTableDataChanged();

                            statusLabel.setText(
                                    result.isEmpty()
                                            ? "No properties found."
                                            : result.size()
                                                    + " properties found across sampled entities.");
                        } catch (Exception ex) {
                            statusLabel.setText("Error: " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }
                };

        worker.execute();
    }

    private List<String> fetchSampleQids(RuleNode node) throws Exception {
        RuleNode sample =
                NodeSamplePanel.sampleNode(node, SAMPLE_QID_LIMIT);

        String sparql =
                RuleTreeExtractor.valuesQuery(sample);

        log.accept("\nDiscover: sample QIDs for " + node.name() + "\n"
                + "-".repeat(40) + "\n" + sparql + "\n");

        List<String> qids =
                new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            String qid =
                    b.qid("value");

            if (qid != null && qid.matches("Q\\d+")) {
                qids.add(qid);
            }
        }

        return qids;
    }

    private List<DiscoveredProperty> profileProperties(
            List<String> qids,
            int sampleSize) throws Exception {

        String sparql =
                buildProfileQuery(qids);

        log.accept("\nDiscover: profile properties (" + qids.size() + " items)\n"
                + "-".repeat(40) + "\n" + sparql + "\n");

        List<DiscoveredProperty> result =
                new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            String pid =
                    b.qid("prop");

            String label =
                    b.label("prop");

            String typeUri =
                    b.value("type");

            String countStr =
                    b.value("count");

            String example =
                    b.value("example");

            String exLabel =
                    b.value("exampleLabel");

            String unitLabel =
                    b.value("unitLabel");

            if (pid == null || !pid.matches("P\\d+")) {
                continue;
            }

            int count =
                    0;

            try {
                count =
                        Integer.parseInt(countStr);
            } catch (Exception ignored) {
            }

            String typeLabel =
                    typeLabel(typeUri);

            PropertyKind kind =
                    propertyKind(typeUri);

            String exampleQid =
                    entityQid(example);

            String exampleDisplay =
                    StringUtils.firstNonBlank(exLabel, example);

            Recommendation recommendation =
                    recommend(kind, count, sampleSize);

            result.add(new DiscoveredProperty(
                    pid,
                    label,
                    typeUri,
                    typeLabel,
                    kind,
                    count,
                    sampleSize,
                    StringUtils.firstNonBlank(unitLabel, ""),
                    exampleQid,
                    exampleDisplay,
                    recommendation));
        }

        return result;
    }

    private static String buildProfileQuery(List<String> qids) {
        StringBuilder values =
                new StringBuilder();

        for (String qid : qids) {
            values.append("wd:")
                  .append(qid)
                  .append(" ");
        }

        return
                "SELECT ?prop ?propLabel ?type "
                        + "(COUNT(DISTINCT ?item) AS ?count) "
                        + "(SAMPLE(?v) AS ?example) "
                        + "(SAMPLE(?exampleLabel) AS ?exampleLabel) "
                        + "(SAMPLE(?unitLabel) AS ?unitLabel)\n"
                        + "WHERE {\n"
                        + "  VALUES ?item { " + values + "}\n"
                        + "  ?item ?propUri ?v .\n"
                        + "  ?prop wikibase:directClaim ?propUri .\n"
                        + "  OPTIONAL { ?prop wikibase:propertyType ?type . }\n"
                        + "  OPTIONAL {\n"
                        + "    ?v rdfs:label ?exampleLabel .\n"
                        + "    FILTER(LANG(?exampleLabel) = \"en\")\n"
                        + "  }\n"
                        + "  OPTIONAL {\n"
                        + "    ?prop wikibase:claim ?claimProp .\n"
                        + "    ?prop wikibase:statementValue ?statementValueProp .\n"
                        + "    ?item ?claimProp ?statement .\n"
                        + "    ?statement ?statementValueProp ?valueNode .\n"
                        + "    ?valueNode wikibase:quantityUnit ?unit .\n"
                        + "    OPTIONAL {\n"
                        + "      ?unit rdfs:label ?unitLabel .\n"
                        + "      FILTER(LANG(?unitLabel) = \"en\")\n"
                        + "    }\n"
                        + "  }\n"
                        + "  SERVICE wikibase:label {\n"
                        + "    bd:serviceParam wikibase:language \"en\" .\n"
                        + "  }\n"
                        + "}\n"
                        + "GROUP BY ?prop ?propLabel ?type\n"
                        + "ORDER BY DESC(?count)\n"
                        + "LIMIT " + PROPERTY_RESULT_LIMIT + "\n";
    }

    private static Recommendation recommend(
            PropertyKind kind,
            int count,
            int sampleSize) {

        if (kind == PropertyKind.MEDIA) {
            return Recommendation.INCLUDED_MEDIA_FIELD;
        }

        if (kind == PropertyKind.ENTITY) {
            /*
             * Count is number of sampled entities having the property, not
             * total value count. Still, for entity-valued properties we prefer
             * child edge when the property is common across a sample, because
             * it likely denotes related entities rather than a simple display field.
             */
            if (count > 1 || sampleSize > 1) {
                return Recommendation.CHILD_EDGE;
            }

            return Recommendation.INCLUDED_ENTITY_FIELD;
        }

        return Recommendation.INCLUDED_SCALAR_FIELD;
    }

    private static String typeLabel(String uri) {
        if (uri == null) {
            return "unknown";
        }

        return switch (uri) {
            case TYPE_ITEM -> "entity";
            case TYPE_STRING -> "string";
            case TYPE_MONO_TEXT -> "text";
            case TYPE_QUANTITY -> "quantity";
            case TYPE_TIME -> "date/time";
            case TYPE_URL -> "URL";
            case TYPE_COMMONS -> "media";
            case TYPE_GLOBE -> "coordinate";
            case TYPE_EXT_ID -> "external ID";
            case TYPE_MATH -> "math";
            default -> "other";
        };
    }

    private static PropertyKind propertyKind(String uri) {
        if (uri == null) {
            return PropertyKind.SCALAR;
        }

        return switch (uri) {
            case TYPE_ITEM -> PropertyKind.ENTITY;
            case TYPE_COMMONS -> PropertyKind.MEDIA;
            default -> PropertyKind.SCALAR;
        };
    }

    private static String entityQid(String value) {
        if (value == null) {
            return "";
        }

        int idx =
                value.lastIndexOf('/');

        String tail =
                idx >= 0 ? value.substring(idx + 1) : value;

        return tail.matches("Q\\d+") ? tail : "";
    }

    private static String safe(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private static String qidSuffix(String qid) {
        return qid == null || qid.isBlank() ? "" : " (" + qid + ")";
    }

    private static String pidSuffix(String pid) {
        return pid == null || pid.isBlank() ? "" : " (" + pid + ")";
    }

    public enum PropertyKind {
        ENTITY,
        SCALAR,
        MEDIA
    }

    public enum Recommendation {
        CHILD_EDGE("Child edge"),
        INCLUDED_ENTITY_FIELD("Included entity field"),
        INCLUDED_MEDIA_FIELD("Included media field"),
        INCLUDED_SCALAR_FIELD("Included scalar field");

        private final String label;

        Recommendation(String label) {
            this.label =
                    label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record DiscoveredProperty(
            String pid,
            String label,
            String typeUri,
            String typeLabel,
            PropertyKind kind,
            int count,
            int sampleSize,
            String unitLabel,
            String exampleQid,
            String exampleValue,
            Recommendation recommendation) {

        public String fieldName() {
            String s =
                    label == null || label.isBlank() ? pid : label;

            s = s.trim()
                 .replaceAll("[^A-Za-z0-9_]+", "_")
                 .replaceAll("_+", "_");

            if (s.startsWith("_")) {
                s = s.substring(1);
            }

            if (s.endsWith("_")) {
                s = s.substring(0, s.length() - 1);
            }

            if (s.isBlank()) {
                s = "field";
            }

            if (Character.isDigit(s.charAt(0))) {
                s = "v_" + s;
            }

            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }

        public String frequency() {
            return count + "/" + sampleSize;
        }
    }

    static final class PropertyTableModel extends AbstractTableModel {

        static final int COL_PID =
                0;
        static final int COL_LABEL =
                1;
        static final int COL_TYPE =
                2;
        static final int COL_COUNT =
                3;
        static final int COL_UNIT =
                4;
        static final int COL_RECOMMENDED =
                5;
        static final int COL_EXAMPLE =
                6;
        static final int COL_ADD_CHILD =
                7;
        static final int COL_ADD_FIELD =
                8;
        static final int COL_ALLOW_QID =
                9;
        static final int COL_EXCLUDE_QID =
                10;

        private static final String[] COLUMNS = {
                "PID",
                "Label",
                "Type",
                "N/sample",
                "Unit",
                "Recommended",
                "Example value",
                "Child",
                "Field",
                "Allow",
                "Exclude"
        };

        private final List<DiscoveredProperty> rows;

        PropertyTableModel(List<DiscoveredProperty> rows) {
            this.rows =
                    rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int col) {
            return COLUMNS[col];
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return (col == COL_ADD_CHILD
                    || col == COL_ADD_FIELD
                    || col == COL_ALLOW_QID
                    || col == COL_EXCLUDE_QID)
                    ? JButton.class
                    : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == COL_ADD_CHILD
                    || col == COL_ADD_FIELD
                    || col == COL_ALLOW_QID
                    || col == COL_EXCLUDE_QID;
        }

        @Override
        public Object getValueAt(int row, int col) {
            DiscoveredProperty p =
                    rows.get(row);

            return switch (col) {
                case COL_PID -> p.pid();
                case COL_LABEL -> p.label();
                case COL_TYPE -> p.typeLabel();
                case COL_COUNT -> p.frequency();
                case COL_UNIT -> p.unitLabel() == null ? "" : p.unitLabel();
                case COL_RECOMMENDED -> p.recommendation().label();
                case COL_EXAMPLE -> p.exampleValue() == null ? "" : p.exampleValue();
                case COL_ADD_CHILD -> p.recommendation() == Recommendation.CHILD_EDGE
                        ? "★ Child"
                        : "＋ Child";
                case COL_ADD_FIELD -> p.recommendation() != Recommendation.CHILD_EDGE
                        ? "★ Field"
                        : "＋ Field";
                case COL_ALLOW_QID -> p.exampleQid() == null || p.exampleQid().isBlank()
                        ? ""
                        : "Allow QID";
                case COL_EXCLUDE_QID -> p.exampleQid() == null || p.exampleQid().isBlank()
                        ? ""
                        : "Exclude QID";
                default -> "";
            };
        }
    }

    private static class ButtonRenderer
            extends JButton
            implements javax.swing.table.TableCellRenderer {

        ButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {

            setText(value == null ? "" : value.toString());
            setEnabled(value != null && !value.toString().isBlank());
            return this;
        }
    }

    private static class ButtonEditor extends DefaultCellEditor {

        private final JButton button;
        private final java.util.function.IntConsumer action;
        private int currentRow;

        ButtonEditor(String label, java.util.function.IntConsumer action) {
            super(new JCheckBox());

            this.action =
                    action;

            button =
                    new JButton(label);

            button.addActionListener(e -> {
                fireEditingStopped();

                if (button.isEnabled()) {
                    action.accept(currentRow);
                }
            });
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table,
                Object value,
                boolean isSelected,
                int row,
                int column) {

            currentRow =
                    row;

            String text =
                    value == null ? "" : value.toString();

            button.setText(text);
            button.setEnabled(!text.isBlank());

            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return button.getText();
        }
    }
}
