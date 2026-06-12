package wikidata.explore.tree;

import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ClassSourcePanel extends JPanel {

    private final JComboBox<ClassSearchQuery.Mode> searchModeBox =
            new JComboBox<>(ClassSearchQuery.Mode.values());

    private GeneratedClassModel clazz;
    private SwingQueryRunner queryRunner;

    private Consumer<String> log = s -> {};
    private Consumer<Void> afterChange = v -> {};

    private final JLabel titleLabel = new JLabel("Class");

    private final JTextField classNameField = new JTextField(18);
    private final JTextField typeQidField = new JTextField(10);
    private final JLabel typeLabel = new JLabel("(not selected)");

    private final JTextField searchTextField = new JTextField(18);
    private final JButton searchTypeButton = new JButton("Search");

    private final SearchTableModel searchModel = new SearchTableModel();
    private final JTable searchTable = new JTable(searchModel);
    private final JButton useSelectedButton = new JButton("Use selected");

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(200, 1, 10000, 10));

    private final JCheckBox requireLabelBox =
            new JCheckBox("Require label", true);

    private final JTextField langField =
            new JTextField("en", 4);

    private final JButton applyButton =
            new JButton("Apply class source");

    private final JLabel summaryLabel =
            new JLabel(" ");

    public ClassSourcePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    /**
     * One-shot: the search button's action listener binds to the first
     * runner's workflow, so later calls are ignored.
     */
    public void setQueryRunner(SwingQueryRunner queryRunner) {
        if (this.queryRunner != null || queryRunner == null) {
            return;
        }

        this.queryRunner = queryRunner;
        wireQuery();
        updateSearchButtonState();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange = afterChange == null ? v -> {} : afterChange;
    }

    public void edit(GeneratedClassModel clazz) {
        this.clazz = clazz;

        if (clazz == null) {
            clear();
            return;
        }

        FieldSourceMapping m = clazz.instanceMapping();

        titleLabel.setText("Class: " + clazz.className());

        classNameField.setText(clazz.className());
        searchTextField.setText(clazz.className());

        typeQidField.setText(m.sourceQid());
        typeLabel.setText(m.displaySource());

        limitSpinner.setValue(Math.max(1, m.limit()));
        requireLabelBox.setSelected(m.requireLabel());
        langField.setText(m.labelLanguage());

        updateSummary();
        updateSearchButtonState();
    }

    public void useSourceQid(String qid, String label) {
        if (clazz == null) {
            return;
        }

        clazz.instanceMapping().sourceQid(qid);
        clazz.instanceMapping().sourceLabel(label);

        typeQidField.setText(clazz.instanceMapping().sourceQid());
        typeLabel.setText(clazz.instanceMapping().displaySource());

        updateSummary();
        afterChange.accept(null);
    }

    public RuleNode toRuleNode() {
        return clazz == null ? null : RuleTreeCompiler.compileClass(clazz);
    }

    public void applyEdits() {
        apply();
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        addWide(form, c, y++, titleLabel);

        JLabel question =
                new JLabel("How do we find instances of this class?");
        question.setFont(question.getFont().deriveFont(Font.ITALIC));
        addWide(form, c, y++, question);

        addRow(form, c, y++, "Class name:", classNameField);

        JPanel typeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        typeRow.add(typeQidField);
        typeRow.add(typeLabel);
        addRow(form, c, y++, "Wikidata type/class:", typeRow);

        JLabel relation =
                new JLabel("Relation: item is instance of selected type (P31)");
        addWide(form, c, y++, relation);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        options.add(new JLabel("Limit:"));
        options.add(limitSpinner);
        options.add(requireLabelBox);
        options.add(new JLabel("lang:"));
        options.add(langField);
        addWide(form, c, y++, options);

        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        searchRow.add(new JLabel("Search:"));
        searchRow.add(searchTextField);
        searchRow.add(new JLabel("Method:"));
        searchRow.add(searchModeBox);
        searchRow.add(searchTypeButton);
        addWide(form, c, y++, searchRow);

        installSearchTableBehavior();

        JScrollPane tableScroll = new JScrollPane(searchTable);
        tableScroll.setPreferredSize(new Dimension(700, 180));
        addWide(form, c, y++, tableScroll);

        JPanel useRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        useSelectedButton.setEnabled(false);
        useRow.add(useSelectedButton);
        addWide(form, c, y++, useRow);

        addWide(form, c, y++, summaryLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(applyButton);
        addWide(form, c, y++, buttons);

        add(scroll, BorderLayout.CENTER);

        applyButton.addActionListener(e -> apply());
        searchTextField.addActionListener(e -> searchTypeButton.doClick());
        useSelectedButton.addActionListener(e -> useSelectedSearchRow());

        searchTable.getSelectionModel().addListSelectionListener(e ->
                                                                         useSelectedButton.setEnabled(searchTable.getSelectedRow() >= 0));

        updateSearchButtonState();
    }

    private void wireQuery() {
        queryRunner.wireButton(
                searchTypeButton,
                this::acceptSearchResult,
                this::buildSearchQuery,
                ex -> JOptionPane.showMessageDialog(
                        this,
                        "Search failed:\n" + ex.getMessage(),
                        "Search failed",
                        JOptionPane.ERROR_MESSAGE));
    }

    private ClassSearchQuery buildSearchQuery() {
        if (clazz == null) {
            return null;
        }

        String text = searchText();

        if (text.isBlank()) {
            return null;
        }

        ClassSearchQuery.Mode mode =
                (ClassSearchQuery.Mode) searchModeBox.getSelectedItem();

        log.accept("Class/type search: "
                           + text
                           + " using "
                           + mode
                           + "\n");

        searchModel.setRows(List.of());
        useSelectedButton.setEnabled(false);

        return new ClassSearchQuery(
                text,
                mode,
                25);
    }

    private String searchText() {
        String text =
                searchTextField.getText() == null
                        ? ""
                        : searchTextField.getText().trim();

        if (text.isBlank()) {
            text =
                    classNameField.getText() == null
                            ? ""
                            : classNameField.getText().trim();
        }

        return text;
    }

    private void acceptSearchResult(TableQueryResult result) {
        List<SearchResult> rows = new ArrayList<>();

        if (result != null) {
            for (List<Object> row : result.rows()) {
                rows.add(new SearchResult(
                        value(row, 0),
                        value(row, 1),
                        value(row, 2)));
            }
        }

        SwingUtilities.invokeLater(() -> {
            searchModel.setRows(rows);
            useSelectedButton.setEnabled(false);
        });
    }

    private static String value(List<Object> row, int index) {
        if (row == null || index >= row.size()) {
            return "";
        }

        Object v = row.get(index);
        return v == null ? "" : String.valueOf(v);
    }

    private void updateSearchButtonState() {
        searchTypeButton.setEnabled(clazz != null && queryRunner != null);
    }

    private void useSelectedSearchRow() {
        int row = searchTable.getSelectedRow();

        if (row < 0) {
            return;
        }

        SearchResult r =
                searchModel.row(searchTable.convertRowIndexToModel(row));

        useSourceQid(r.qid(), r.label());
    }

    private void installSearchTableBehavior() {
        searchTable.setRowHeight(24);
        searchTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        searchTable.getColumnModel().getColumn(0)
                   .setPreferredWidth(80);
        searchTable.getColumnModel().getColumn(1)
                   .setPreferredWidth(180);
        searchTable.getColumnModel().getColumn(2)
                   .setPreferredWidth(420);

        searchTable.getColumnModel().getColumn(0)
                   .setCellRenderer(new QidLinkRenderer());

        searchTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int viewRow = searchTable.rowAtPoint(e.getPoint());
                int viewCol = searchTable.columnAtPoint(e.getPoint());

                if (viewRow < 0 || viewCol != 0) {
                    return;
                }

                Object val = searchTable.getValueAt(viewRow, viewCol);
                String qid = val == null ? "" : val.toString();

                if (qid.matches("Q\\d+")) {
                    openInBrowser("https://www.wikidata.org/wiki/" + qid);
                }
            }
        });

        searchTable.addMouseMotionListener(
                new java.awt.event.MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(java.awt.event.MouseEvent e) {
                        int col = searchTable.columnAtPoint(e.getPoint());

                        searchTable.setCursor(col == 0
                                                      ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                                      : Cursor.getDefaultCursor());
                    }
                });
    }

    private void apply() {
        if (clazz == null) {
            return;
        }

        clazz.className(classNameField.getText());

        FieldSourceMapping m = clazz.instanceMapping();
        m.sourceQid(typeQidField.getText());
        m.propertyPid("P31");
        m.propertyLabel("instance of");
        m.direction(RuleDirection.ITEM_TO_ROOT);
        m.limit(((Number) limitSpinner.getValue()).intValue());
        m.requireLabel(requireLabelBox.isSelected());
        m.labelLanguage(langField.getText());

        titleLabel.setText("Class: " + clazz.className());
        typeLabel.setText(m.displaySource());

        updateSummary();
        afterChange.accept(null);
    }

    private void updateSummary() {
        if (clazz == null) {
            summaryLabel.setText(" ");
            return;
        }

        summaryLabel.setText(
                "Current source: items with instance of "
                        + clazz.instanceMapping().displaySource());
    }

    private void clear() {
        titleLabel.setText("Class");
        classNameField.setText("");
        searchTextField.setText("");
        typeQidField.setText("");
        typeLabel.setText("(not selected)");
        summaryLabel.setText(" ");
        searchModel.setRows(List.of());
        updateSearchButtonState();
    }

    private record SearchResult(
            String qid,
            String label,
            String description) {
    }

    private static final class SearchTableModel extends AbstractTableModel {

        private final String[] columns =
                {"QID", "Label", "Description"};

        private List<SearchResult> rows = new ArrayList<>();

        public void setRows(List<SearchResult> rows) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            fireTableDataChanged();
        }

        public SearchResult row(int i) {
            return rows.get(i);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SearchResult r = rows.get(rowIndex);

            return switch (columnIndex) {
                case 0 -> r.qid();
                case 1 -> r.label();
                case 2 -> r.description();
                default -> "";
            };
        }
    }

    private static final class QidLinkRenderer
            extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focus,
                int row,
                int col) {

            super.getTableCellRendererComponent(
                    table,
                    value,
                    selected,
                    focus,
                    row,
                    col);

            String text = value == null ? "" : value.toString();

            if (!selected && text.matches("Q\\d+")) {
                setForeground(new Color(0, 80, 200));
                setText("<html><u>" + text + "</u></html>");
            } else {
                setText(text);
            }

            return this;
        }
    }

    private static void openInBrowser(String url) {
        try {
            Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception ignored) {
        }
    }

    private static void addRow(
            JPanel form,
            GridBagConstraints c,
            int y,
            String label,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1;
        form.add(comp, c);
    }

    private static void addWide(
            JPanel form,
            GridBagConstraints c,
            int y,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(comp, c);
        c.gridwidth = 1;
    }
}