package quiz.transform.ui;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import domain.DomainModel;

/**
 * First-navigation screen for the transform workbench: lists the given
 * {@link DomainEntry} catalog and opens the chosen domain in a
 * {@link TransformWorkbenchPanel} (loading off the EDT so the picker stays
 * responsive). Backing-agnostic — the caller supplies the entries and the
 * {@link DomainWriter}.
 */
public final class DomainNavigator {

    private DomainNavigator() {}

    public static void show(Supplier<List<DomainEntry>> entries, DomainWriter writer) {
        SwingUtilities.invokeLater(() -> build(entries, writer));
    }

    private static void build(
            Supplier<List<DomainEntry>> entriesSupplier, DomainWriter writer) {
        DefaultListModel<DomainEntry> model = new DefaultListModel<>();
        JList<DomainEntry> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JLabel status = new JLabel();
        // Re-read the catalog on demand so a domain saved from a workbench shows up
        // without a restart; keep the current selection by name across the refresh.
        Runnable reload = () -> {
            DomainEntry selected = list.getSelectedValue();
            String selectedName = selected == null ? null : selected.name();
            model.clear();
            entriesSupplier.get().forEach(model::addElement);
            status.setText(model.size() + " domain(s)");
            int restore = -1;
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).name().equals(selectedName)) {
                    restore = i;
                    break;
                }
            }
            if (model.size() > 0) {
                list.setSelectedIndex(restore >= 0 ? restore : 0);
            }
        };
        reload.run();
        JButton open = new JButton("Open in Transform Workbench");

        // Reuse an already-open workbench instead of reloading the domain (which can be
        // slow — Nobel re-parses + re-reads ~1000 portraits). Keyed by domain name.
        Map<String, JFrame> openFrames = new HashMap<>();

        Runnable openSelected = () -> {
            DomainEntry e = list.getSelectedValue();
            if (e == null) {
                return;
            }
            JFrame existing = openFrames.get(e.name());
            if (existing != null && existing.isDisplayable()) {
                existing.setExtendedState(Frame.NORMAL);   // un-minimize
                existing.toFront();
                existing.requestFocus();
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
                        JFrame f = TransformWorkbenchPanel.openFrame(get(), e.name(), writer);
                        openFrames.put(e.name(), f);
                        f.addWindowListener(new java.awt.event.WindowAdapter() {
                            @Override public void windowClosed(java.awt.event.WindowEvent we) {
                                openFrames.remove(e.name());
                            }
                        });
                        status.setText(model.size() + " domain(s)");
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
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowActivated(java.awt.event.WindowEvent e) {
                reload.run();   // pick up a domain saved while a workbench had focus
            }
        });
        frame.setSize(560, 480);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
