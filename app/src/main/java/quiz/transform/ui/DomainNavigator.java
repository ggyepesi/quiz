package quiz.transform.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * First-navigation screen for the transform workbench: lists the given
 * {@link DomainEntry} catalog and opens the chosen domain in a
 * {@link TransformWorkbenchPanel} (loading off the EDT so the picker stays
 * responsive). Backing-agnostic — the caller supplies the entries and the
 * {@link DomainWriter}.
 */
public final class DomainNavigator {

    private DomainNavigator() {}

    public static void show(List<DomainEntry> entries, DomainWriter writer) {
        SwingUtilities.invokeLater(() -> build(entries, writer));
    }

    private static void build(List<DomainEntry> entries, DomainWriter writer) {
        DefaultListModel<DomainEntry> model = new DefaultListModel<>();
        entries.forEach(model::addElement);
        JList<DomainEntry> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!entries.isEmpty()) {
            list.setSelectedIndex(0);
        }

        JLabel status = new JLabel(entries.size() + " domain(s)");
        JButton open = new JButton("Open in Transform Workbench");

        Runnable openSelected = () -> {
            DomainEntry e = list.getSelectedValue();
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
                        TransformWorkbenchPanel.launch(get(), e.name(), writer);
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
