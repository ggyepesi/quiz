package quiz.enrichment.ui;

import quiz.enrichment.ResolveIdentitiesDecision;
import quiz.enrichment.ResolveIdentitiesReviewRequest.IdentityMatch;
import quiz.enrichment.ResolveIdentitiesReviewRequest.InstanceIdentity;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One review over identity candidates: a row per instance with its name, current identity
 * (if any), a dropdown of the Wikidata matches (top one pre-selected), and an accept box.
 * The whole scope is confirmed in one screen — the identity analogue of the Find Data batch
 * review. Instances with no match are shown disabled (resolve them individually later).
 */
public final class ResolveIdentitiesReviewPanel extends JPanel {

    public static void showDialog(
            Component owner,
            String title,
            String prompt,
            List<InstanceIdentity> instances,
            Consumer<ResolveIdentitiesDecision> onDone) {
        Window window = SwingUtilities.getWindowAncestor(owner);
        JDialog dialog =
                new JDialog(window, title, Dialog.ModalityType.APPLICATION_MODAL);
        Consumer<ResolveIdentitiesDecision> handler = onDone == null ? ignored -> { } : onDone;
        ResolveIdentitiesReviewPanel panel =
                new ResolveIdentitiesReviewPanel(prompt, instances, decision -> {
                    handler.accept(decision);
                    dialog.dispose();
                });
        dialog.add(panel);
        dialog.setSize(760, 600);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private record Row(
            InstanceIdentity instance, JCheckBox accept, JComboBox<IdentityMatch> combo) { }

    private final List<Row> rows = new ArrayList<>();

    private ResolveIdentitiesReviewPanel(
            String prompt,
            List<InstanceIdentity> instances,
            Consumer<ResolveIdentitiesDecision> onApprove) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        long withMatch = instances.stream().filter(i -> !i.candidates().isEmpty()).count();
        add(new JLabel("<html>" + html(prompt) + " &nbsp;<i>(" + withMatch + " of "
                + instances.size() + " matched)</i></html>"), BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (InstanceIdentity instance : instances) {
            list.add(row(instance));
        }
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(buttons(onApprove), BorderLayout.SOUTH);
    }

    private JComponent row(InstanceIdentity instance) {
        boolean hasMatch = !instance.candidates().isEmpty();
        JCheckBox accept = new JCheckBox("", hasMatch);
        accept.setEnabled(hasMatch);

        String now = instance.currentQid() == null || instance.currentQid().isBlank()
                ? "" : "  (now " + instance.currentQid() + ")";
        JLabel name = new JLabel("<html><b>" + html(instance.name()) + "</b>"
                + "<font color=gray>" + html(now) + "</font></html>");
        name.setPreferredSize(new Dimension(180, name.getPreferredSize().height));

        JComboBox<IdentityMatch> combo = new JComboBox<>(
                instance.candidates().toArray(new IdentityMatch[0]));
        combo.setRenderer(new MatchRenderer());
        combo.setEnabled(hasMatch);
        combo.setPreferredSize(new Dimension(180, combo.getPreferredSize().height));
        if (combo.getItemCount() == 0) {
            combo.addItem(null);   // renders as "(no match)"
        } else {
            combo.setSelectedIndex(0);
        }

        // The chosen candidate's Wikidata description, shown prominently beside the combo —
        // almost always the type ("country in the Caucasus" vs "state of the United States"),
        // the cheap disambiguator already fetched by the search (no extra P31 call).
        JLabel description = new JLabel();
        description.setForeground(Color.GRAY);
        description.setPreferredSize(new Dimension(300, description.getPreferredSize().height));
        Runnable showDescription = () -> {
            Object selected = combo.getSelectedItem();
            description.setText(selected instanceof IdentityMatch match ? match.description() : "");
        };
        combo.addActionListener(e -> showDescription.run());
        showDescription.run();

        rows.add(new Row(instance, accept, combo));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.add(accept);
        row.add(name);
        row.add(combo);
        row.add(description);
        return row;
    }

    private JComponent buttons(Consumer<ResolveIdentitiesDecision> onApprove) {
        JButton all = new JButton("Select all");
        all.addActionListener(e -> rows.forEach(
                r -> { if (r.combo().isEnabled()) r.accept().setSelected(true); }));
        JButton none = new JButton("Select none");
        none.addActionListener(e -> rows.forEach(r -> r.accept().setSelected(false)));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(
                e -> onApprove.accept(new ResolveIdentitiesDecision(List.of())));
        JButton apply = new JButton("Apply selected");
        apply.addActionListener(e -> onApprove.accept(collect()));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panel.add(all);
        panel.add(none);
        panel.add(Box.createHorizontalStrut(12));
        panel.add(cancel);
        panel.add(apply);
        return panel;
    }

    private ResolveIdentitiesDecision collect() {
        List<ResolveIdentitiesDecision.Resolved> resolved = new ArrayList<>();
        for (Row row : rows) {
            Object selected = row.combo().getSelectedItem();
            if (row.accept().isSelected() && selected instanceof IdentityMatch match) {
                resolved.add(new ResolveIdentitiesDecision.Resolved(
                        row.instance().targetId(), match.qid(), match.label()));
            }
        }
        return new ResolveIdentitiesDecision(resolved);
    }

    private static String html(String value) {
        return value == null ? ""
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class MatchRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof IdentityMatch match) {
                // Dropdown list items (index >= 0) carry the description too, so you can
                // choose among alternatives; the collapsed selected display (index -1) stays
                // compact since the description shows in the dedicated label beside the combo.
                String desc = index >= 0 && match.description() != null
                        && !match.description().isBlank()
                        ? "  <font color=gray>— " + html(match.description()) + "</font>" : "";
                setText("<html><b>" + html(match.label()) + "</b> <font color=gray>("
                        + html(match.qid()) + ")</font>" + desc + "</html>");
            } else {
                setText("(no match)");
            }
            return this;
        }
    }
}
