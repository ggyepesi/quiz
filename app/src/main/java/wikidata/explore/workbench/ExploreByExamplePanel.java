package wikidata.explore.workbench;

import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.logical.ExploreEntityQuery;
import wikidata.explore.query.logical.RelationMembersQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * "Explore by example": name a thing (e.g. "Labours of Hercules", "Argonauts"),
 * pick the matching entity, and the panel runs a relation battery
 * ({@link ExploreEntityQuery}) to surface candidate <em>sets</em> it anchors.
 * Each set can be added to the selected class's Seed QIDs in one click, or the
 * entity used as the class's membership type.
 */
public class ExploreByExamplePanel extends JPanel {

    private SwingQueryRunner queryRunner;

    private Consumer<List<String>> onAddSeedQids = qids -> {};
    private Consumer<List<String>> onAddRelationTargets = qids -> {};
    private BiConsumer<String, String> onUseAsSourceQid = (qid, label) -> {};

    private final JTextField searchField = new JTextField(22);
    private final JButton searchButton = new JButton("Search");
    private final EntityResultPanel candidates =
            new EntityResultPanel(List.of("QID", "Label", "Description"), 0, false);

    private final JButton exploreButton = new JButton("Explore relations");
    private final JButton useSourceButton = new JButton("Use as class type (P31)");

    private final RelationModel probeModel = new RelationModel();
    private final JTable probeTable = new JTable(probeModel);
    private final JButton showMembersButton = new JButton("Show members");
    private final JButton addTargetsButton = new JButton("Add as relation targets");
    private final JButton addSeedsButton = new JButton("Add as Seed QIDs");
    private final JLabel hint = new JLabel(
            "<html>Search → pick an entity → Explore relations → pick a "
                    + "relation: <b>Show members</b> (then double-click a member to "
                    + "<b>follow</b> it and explore further), or <b>add members as "
                    + "Seed QIDs</b>. <b>◀ Back</b> retraces your path.</html>");
    private boolean explorationOnly;
    // The entity whose relations are shown — used to fetch a relation's members.
    private String exploredQid = "";
    private String exploredLabel = "";

    // Graph navigation: a header showing the current entity, a Back button, and a
    // history stack — following a relation's member makes it the next explored
    // entity, so the user walks the Wikidata graph live.
    private final JLabel currentLabel = new JLabel(" ");
    private final JButton backButton = new JButton("◀ Back");
    private final java.util.Deque<String[]> history = new java.util.ArrayDeque<>();
    // Set when following a member (vs exploring a fresh candidate from search).
    private String pendingQid = "";
    private String pendingLabel = "";

    private final JLabel status = new JLabel(" ");

    public ExploreByExamplePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    /** One-shot: binds the buttons to the first runner's workflow. */
    public void setQueryRunner(SwingQueryRunner queryRunner) {
        if (this.queryRunner != null || queryRunner == null) {
            return;
        }
        this.queryRunner = queryRunner;

        queryRunner.wireButton(
                searchButton, this::acceptSearch, this::buildSearchQuery,
                ex -> showError("Search failed", ex));
        queryRunner.wireButton(
                exploreButton, this::acceptProbes, this::buildExploreQuery,
                ex -> showError("Explore failed", ex));
        queryRunner.wireButton(
                showMembersButton, this::acceptMembersPreview, this::buildMembersQuery,
                ex -> showError("Fetch members failed", ex));
        queryRunner.wireButton(
                addTargetsButton, this::acceptTargets, this::buildMembersQuery,
                ex -> showError("Fetch members failed", ex));
        queryRunner.wireButton(
                addSeedsButton, this::acceptMembers, this::buildMembersQuery,
                ex -> showError("Fetch members failed", ex));
        updateButtons();
    }

    private void showError(String title, Throwable ex) {
        String msg = ex == null ? "Unknown error" : ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = String.valueOf(ex);
        }
        status.setText(title + ": " + msg);
        final String body = msg;
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this, body, title, JOptionPane.ERROR_MESSAGE));
    }

    public void onAddRelationTargets(Consumer<List<String>> handler) {
        this.onAddRelationTargets = handler == null ? qids -> {} : handler;
    }

    public void onAddSeedQids(Consumer<List<String>> handler) {
        this.onAddSeedQids = handler == null ? qids -> {} : handler;
    }

    public void onUseAsSourceQid(BiConsumer<String, String> handler) {
        this.onUseAsSourceQid = handler == null ? (q, l) -> {} : handler;
    }

    /** Use the graph browser outside ModelBuilder. The search, relation preview,
     * member list and follow/back navigation remain; model-mutating actions do not. */
    public void explorationOnly() {
        explorationOnly = true;
        useSourceButton.setVisible(false);
        addTargetsButton.setVisible(false);
        addSeedsButton.setVisible(false);
        hint.setText(
                "<html>Search → pick an entity → Explore relations → pick a "
                        + "relation and <b>Show members</b>. Double-click a member to "
                        + "follow it; <b>◀ Back</b> retraces your path.</html>");
    }

    private void buildUi() {
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        searchRow.add(new JLabel("Describe / name it:"));
        searchRow.add(searchField);
        searchRow.add(searchButton);

        candidates.setColumnWidths(70, 180, 420);

        JPanel midRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        midRow.add(exploreButton);
        midRow.add(useSourceButton);

        probeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        probeTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        probeTable.getColumnModel().getColumn(0).setPreferredWidth(55);
        probeTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        probeTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        probeTable.getColumnModel().getColumn(3).setPreferredWidth(360);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        actionRow.add(showMembersButton);
        addTargetsButton.setToolTipText("Add the relation's members to the selected "
                + "class's \"Also include types\" (membership relation targets)");
        actionRow.add(addTargetsButton);
        actionRow.add(addSeedsButton);
        actionRow.add(status);

        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));

        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.add(searchRow, BorderLayout.NORTH);
        top.add(candidates, BorderLayout.CENTER);
        top.add(midRow, BorderLayout.SOUTH);

        useSourceButton.setToolTipText("Make the selected entity the selected "
                + "class's membership type (instance-of / P31).");

        JPanel navRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        backButton.setEnabled(false);
        backButton.setToolTipText("Back to the previously explored entity");
        navRow.add(backButton);
        navRow.add(currentLabel);

        JPanel bottom = new JPanel(new BorderLayout(4, 2));
        bottom.add(navRow, BorderLayout.NORTH);
        bottom.add(new JScrollPane(probeTable), BorderLayout.CENTER);
        JPanel bottomSouth = new JPanel(new BorderLayout());
        bottomSouth.add(actionRow, BorderLayout.NORTH);
        bottomSouth.add(hint, BorderLayout.SOUTH);
        bottom.add(bottomSouth, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        searchField.addActionListener(e -> searchButton.doClick());
        candidates.onSelectionChanged(this::updateButtons);
        probeTable.getSelectionModel().addListSelectionListener(e -> updateButtons());

        exploreButton.addActionListener(e -> { /* wired via queryRunner */ });
        backButton.addActionListener(e -> {
            if (!history.isEmpty()) {
                String[] prev = history.pop();
                exploreEntity(prev[0], prev[1], false);
            }
        });
        useSourceButton.addActionListener(e -> {
            String qid = candidates.firstSelected(0);
            if (qid.matches("Q\\d+")) {
                onUseAsSourceQid.accept(qid, candidates.firstSelected(1));
                status.setText("Used " + qid + " as class type.");
            }
        });
        // addSeedsButton is wired via the queryRunner (it fetches the relation's
        // members on demand, then onAddSeedQids — see buildMembersQuery /
        // acceptMembers).

        updateButtons();
    }

    private void updateButtons() {
        boolean haveCandidate = candidates.hasSelection();
        boolean haveProbe = probeTable.getSelectedRow() >= 0;
        exploreButton.setEnabled(haveCandidate && queryRunner != null);
        useSourceButton.setEnabled(haveCandidate);
        showMembersButton.setEnabled(haveProbe);
        addTargetsButton.setEnabled(haveProbe);
        addSeedsButton.setEnabled(haveProbe);
    }

    private ClassSearchQuery buildSearchQuery() {
        String text = searchField.getText() == null ? "" : searchField.getText().trim();
        if (text.isBlank()) {
            return null;
        }
        status.setText("Searching…");
        candidates.setRows(List.of());
        probeModel.setRows(List.of());
        return new ClassSearchQuery(text, ClassSearchQuery.Mode.API, 20);
    }

    private void acceptSearch(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        SwingUtilities.invokeLater(() -> {
            candidates.setRows(rows);
            status.setText(rows.size() + " candidate(s) — pick one, then Explore.");
            updateButtons();
        });
    }

    /** Explore an entity's relations from outside (e.g. a model-graph node click):
     *  a fresh exploration that resets the navigation history. */
    public void exploreQid(String qid, String label) {
        if (qid == null || !qid.matches("Q\\d+")) {
            return;
        }
        history.clear();
        exploreEntity(qid, label, false);
    }

    // Explore an arbitrary entity (following a relation's member), pushing the
    // current one onto the Back history. Triggers the same wired explore action.
    private void exploreEntity(String qid, String label, boolean pushHistory) {
        if (qid == null || !qid.matches("Q\\d+")) {
            return;
        }
        if (pushHistory && exploredQid.matches("Q\\d+")) {
            history.push(new String[]{exploredQid, exploredLabel});
        }
        pendingQid = qid;
        pendingLabel = label == null ? qid : label;
        // The explore button is gated on a search-candidate selection, which may
        // not hold mid-navigation — enable it so the follow actually fires.
        exploreButton.setEnabled(queryRunner != null);
        exploreButton.doClick();
    }

    private ExploreEntityQuery buildExploreQuery() {
        String qid;
        String label;
        if (pendingQid.matches("Q\\d+")) {
            qid = pendingQid;
            label = pendingLabel;
            pendingQid = "";
        } else {
            // Fresh exploration from the search candidates — a new root, so the
            // navigation history starts over.
            qid = candidates.firstSelected(0);
            label = candidates.firstSelected(1);
            history.clear();
        }
        if (!qid.matches("Q\\d+")) {
            return null;
        }
        exploredQid = qid;
        exploredLabel = label == null || label.isBlank() ? qid : label;
        currentLabel.setText("Relations of: " + exploredLabel + " (" + qid + ")");
        backButton.setEnabled(!history.isEmpty());
        status.setText("Exploring relations of " + exploredLabel + "…");
        probeModel.setRows(List.of());
        return new ExploreEntityQuery(qid, 200);
    }

    // Rows are (Direction, PID, Relation, Count, Example) — one per relation.
    private void acceptProbes(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        List<RelationRow> relations = new ArrayList<>();
        for (List<Object> row : rows) {
            String dir = str(row, 0);
            String pid = str(row, 1);
            if (!pid.matches("P\\d+")) {
                continue;
            }
            relations.add(new RelationRow(
                    dir, pid, str(row, 2), num(str(row, 3)), str(row, 4)));
        }
        SwingUtilities.invokeLater(() -> {
            probeModel.setRows(relations);
            status.setText(relations.isEmpty()
                    ? "No relations found."
                    : relations.size() + " relation(s) — pick one to "
                            + (explorationOnly ? "inspect its members."
                                    : "add its members as seeds."));
            updateButtons();
        });
    }

    private RelationMembersQuery buildMembersQuery() {
        int r = probeTable.getSelectedRow();
        if (r < 0 || !exploredQid.matches("Q\\d+")) {
            return null;
        }
        RelationRow rel = probeModel.row(r);
        status.setText("Fetching members of " + rel.pid() + "…");
        return new RelationMembersQuery(exploredQid, rel.pid(), rel.incoming(), 5000);
    }

    private static List<String> qidsOf(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        List<String> qids = new ArrayList<>();
        for (List<Object> row : rows) {
            String qid = str(row, 0);
            if (qid.matches("Q\\d+") && !qids.contains(qid)) {
                qids.add(qid);
            }
        }
        return qids;
    }

    private void acceptMembers(TableQueryResult result) {
        List<String> qids = qidsOf(result);
        SwingUtilities.invokeLater(() -> {
            onAddSeedQids.accept(qids);
            status.setText("Added " + qids.size() + " seed QID(s).");
        });
    }

    private void acceptTargets(TableQueryResult result) {
        List<String> qids = qidsOf(result);
        SwingUtilities.invokeLater(() -> {
            onAddRelationTargets.accept(qids);
            status.setText("Added " + qids.size() + " relation target(s).");
        });
    }

    // Read-only preview: show a relation's members (QID + label) in a dialog so
    // the user can test what a relation produces without committing to seeds.
    private void acceptMembersPreview(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        Object[][] data = new Object[rows.size()][2];
        for (int i = 0; i < rows.size(); i++) {
            data[i][0] = str(rows.get(i), 0);
            data[i][1] = str(rows.get(i), 1);
        }
        SwingUtilities.invokeLater(() -> {
            status.setText(rows.size() + " member(s).");
            JTable table = new JTable(data, new Object[]{"QID", "Label"});
            table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.getColumnModel().getColumn(0).setPreferredWidth(90);
            WikidataLinks.installOnColumn(table, 0); // QID → clickable link
            JScrollPane sc = new JScrollPane(table);
            sc.setPreferredSize(new Dimension(460, 320));

            JDialog dialog = new JDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Members (" + rows.size() + ")",
                    Dialog.ModalityType.MODELESS);

            // Follow a member: make it the next explored entity (graph navigation).
            Runnable follow = () -> {
                int r = table.getSelectedRow();
                if (r < 0) {
                    return;
                }
                String qid = String.valueOf(table.getValueAt(r, 0));
                String label = String.valueOf(table.getValueAt(r, 1));
                dialog.dispose();
                exploreEntity(qid, label, true);
            };
            JButton exploreSel = new JButton("Explore selected ▶");
            exploreSel.addActionListener(e -> follow.run());
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) follow.run();
                }
            });

            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
            south.add(new JLabel("Double-click or:"));
            south.add(exploreSel);

            JPanel panel = new JPanel(new BorderLayout(4, 4));
            panel.add(sc, BorderLayout.CENTER);
            panel.add(south, BorderLayout.SOUTH);
            dialog.setContentPane(panel);
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        });
    }

    private static String str(List<Object> row, int i) {
        Object v = row == null || i >= row.size() ? null : row.get(i);
        return v == null ? "" : v.toString();
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------

    // One relation of the explored entity: direction, property, count, example.
    private record RelationRow(
            String dir, String pid, String label, int count, String example) {
        boolean incoming() { return dir != null && dir.startsWith("←"); }
        String relation() { return label + " (" + pid + ")"; }
    }

    private static final class RelationModel extends AbstractTableModel {
        private final String[] cols = {"Dir", "Relation", "Count", "Example"};
        private List<RelationRow> rows = new ArrayList<>();

        void setRows(List<RelationRow> r) {
            rows = r == null ? new ArrayList<>() : r;
            fireTableDataChanged();
        }

        RelationRow row(int r) { return rows.get(r); }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            RelationRow row = rows.get(r);
            return switch (c) {
                case 0 -> row.dir();
                case 1 -> row.relation();
                case 2 -> row.count();
                case 3 -> row.example();
                default -> "";
            };
        }
    }

}
