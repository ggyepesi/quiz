package wikidata.explore.workbench;

import wikidata.explore.query.logical.DiscoverDBpediaPropertiesQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A passive picker for a DBpedia (Wikipedia infobox) property, parallel to Explore's
 * {@code findProperty}: given the seed instances to inspect, it lists their {@code dbp:}
 * infobox properties (Property / Have / Example) and RETURNS the chosen one to the caller
 * via {@code onSelected} — it never sets anything itself. Curate uses it to pick a field's
 * DBpedia source; the same picker can back the ModelBuilder source panel's discovery.
 */
public final class DbpediaPropertyPicker {

    private DbpediaPropertyPicker() { }

    /** Inspect {@code seedQids}' DBpedia infobox properties and deliver the picked
     *  {@code (property, exampleValue)} to {@code onSelected}. */
    public static void findProperty(
            Component parent, SwingQueryRunner runner,
            List<String> seedQids, BiConsumer<String, String> onSelected) {
        if (seedQids == null || seedQids.isEmpty()) {
            return;
        }
        run(parent, runner, new DiscoverDBpediaPropertiesQuery(seedQids), onSelected);
    }

    /** Sample {@code sampleSize} instances of the class {@code typeQid}, then pick from their
     *  DBpedia infobox properties — for a caller (e.g. the source panel) that has the class
     *  type rather than specific instances. */
    public static void findPropertyByType(
            Component parent, SwingQueryRunner runner,
            String typeQid, int sampleSize, BiConsumer<String, String> onSelected) {
        run(parent, runner, new DiscoverDBpediaPropertiesQuery(typeQid, sampleSize), onSelected);
    }

    private static void run(
            Component parent, SwingQueryRunner runner,
            DiscoverDBpediaPropertiesQuery query, BiConsumer<String, String> onSelected) {
        if (runner == null) {
            return;
        }
        runner.run(query,
                result -> SwingUtilities.invokeLater(
                        () -> showPicker(parent, result, onSelected)),
                ex -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        parent, "Discover DBpedia properties failed:\n" + ex.getMessage(),
                        "Discover failed", JOptionPane.ERROR_MESSAGE)));
    }

    private static void showPicker(
            Component parent, TableQueryResult result, BiConsumer<String, String> onSelected) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "No Wikipedia-infobox (DBpedia) properties for the sampled instance(s) — "
                            + "the sample may have no Wikidata sameAs link to DBpedia, or its "
                            + "Wikipedia article has no infobox.",
                    "Discover properties", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String[] cols = {"Property", "Have", "Example"};
        Object[][] data = new Object[rows.size()][3];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            for (int j = 0; j < 3; j++) {
                data[i][j] = j < r.size() ? r.get(j) : "";
            }
        }
        DefaultTableModel model = new DefaultTableModel(data, cols) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(50);
        table.getColumnModel().getColumn(2).setPreferredWidth(320);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(660, 320));

        JButton use = new JButton("Use selected property");
        use.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(
                e -> use.setEnabled(table.getSelectedRow() >= 0));

        JDialog dialog = new JDialog(quiz.ui.Dialogs.owner(parent),
                "DBpedia infobox properties", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(0, 6));
        dialog.add(new JLabel("<html>&nbsp;Wikipedia-infobox (DBpedia) properties of the "
                        + "sampled instance(s), reached via their Wikidata <i>sameAs</i>. "
                        + "<b>Have</b> = how many of the sampled instances carry it; "
                        + "<b>Example</b> = a sample value. Pick the one holding the field "
                        + "you want.</html>"),
                BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(use);
        south.add(close);
        dialog.add(south, BorderLayout.SOUTH);

        use.addActionListener(ev -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                String property = String.valueOf(table.getValueAt(row, 0));
                String example = String.valueOf(table.getValueAt(row, 2));
                dialog.dispose();
                if (onSelected != null) {
                    onSelected.accept(property, example);
                }
            }
        });
        close.addActionListener(ev -> dialog.dispose());

        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
