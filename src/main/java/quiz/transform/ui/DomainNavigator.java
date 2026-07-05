package quiz.transform.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * First-navigation screen for the transform workbench: lists every domain in the
 * {@link DomainCatalog} — generated Wikidata datasets AND the built-in Quizable
 * domains (Nobel, State, SportTeam, …) — and opens the chosen one in a
 * {@link TransformWorkbenchPanel}. The (possibly heavy) domain load runs off the
 * EDT so the picker stays responsive.
 */
public final class DomainNavigator {

    private DomainNavigator() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(DomainNavigator::show);
    }

    private static void show() {
        List<DomainCatalog.Entry> entries = DomainCatalog.all();

        DefaultListModel<DomainCatalog.Entry> model = new DefaultListModel<>();
        entries.forEach(model::addElement);
        JList<DomainCatalog.Entry> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!entries.isEmpty()) {
            list.setSelectedIndex(0);
        }

        JLabel status = new JLabel(entries.size() + " domain(s)");
        JButton open = new JButton("Open in Transform Workbench");

        Runnable openSelected = () -> {
            DomainCatalog.Entry e = list.getSelectedValue();
            if (e == null) {
                return;
            }
            open.setEnabled(false);
            status.setText("Loading \"" + e.name() + "\"…");
            new SwingWorker<DomainModel, Void>() {
                @Override protected DomainModel doInBackground() throws Exception {
                    return e.opener().open();
                }
                @Override protected void done() {
                    try {
                        TransformWorkbenchPanel.launch(get(), e.name());
                        status.setText(entries.size() + " domain(s)");
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        status.setText("Failed: " + cause.getMessage());
                        JOptionPane.showMessageDialog(null,
                                "Could not open \"" + e.name() + "\":\n" + cause,
                                "Load failed", JOptionPane.ERROR_MESSAGE);
                    } finally {
                        open.setEnabled(true);
                    }
                }
            }.execute();
        };

        open.addActionListener(ev -> openSelected.run());
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent me) {
                if (me.getClickCount() == 2) {
                    openSelected.run();
                }
            }
        });

        JPanel south = new JPanel(new BorderLayout(8, 4));
        south.add(status, BorderLayout.WEST);
        south.add(open, BorderLayout.EAST);
        south.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JFrame frame = new JFrame("Domains");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(list), BorderLayout.CENTER);
        frame.add(south, BorderLayout.SOUTH);
        frame.setSize(560, 480);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
