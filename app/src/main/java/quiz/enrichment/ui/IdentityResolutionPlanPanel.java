package quiz.enrichment.ui;

import objectview.Viewable;
import objectview.view.SearchableView;
import quiz.enrichment.IdentityResolutionPlan;
import wikidata.explore.workbench.IdentityChip;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/** Virtualized card preview shown before identity searches begin. */
public final class IdentityResolutionPlanPanel extends JPanel {

    private IdentityResolutionPlanPanel(
            IdentityResolutionPlan plan, Consumer<List<Viewable>> execute,
            int staged, Runnable applyStaged, Runnable discardStaged) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        boolean hasResults = staged > 0;
        String summary = hasResults
                ? staged + " reviewed identity assignment(s) are staged. Inspect them below, "
                        + "then Apply or Forget. " + plan.unresolved().size()
                        + " item(s) remain unresolved."
                : plan.identified().size() + " instance(s) already have a Wikidata identity; "
                        + plan.unresolved().size() + " unresolved instance(s) will be searched. "
                        + "Existing identities are shown for inspection and will not be searched or changed.";
        add(new JLabel("<html><b>" + (hasResults ? "Results ready to apply" : "What will happen")
                + "</b><br>" + summary + "</html>"), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Existing (" + plan.identified().size() + ")",
                cards(plan.identified(), true));
        tabs.addTab("Will search (" + plan.unresolved().size() + ")",
                cards(plan.unresolved(), false));
        tabs.setSelectedIndex(plan.unresolved().isEmpty() ? 0 : 1);
        add(tabs, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        JButton run = new JButton("Execute " + plan.unresolved().size() + " search(es)");
        run.setEnabled(!plan.unresolved().isEmpty());
        JButton discard = new JButton("Forget staged");
        discard.setEnabled(staged > 0);
        JButton apply = new JButton("Apply staged identities (" + staged + ")");
        apply.setEnabled(staged > 0);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(discard); buttons.add(close);
        buttons.add(apply);
        buttons.add(run);
        add(buttons, BorderLayout.SOUTH);
        close.addActionListener(e -> SwingUtilities.getWindowAncestor(this).dispose());
        discard.addActionListener(e -> {
            discardStaged.run();
            SwingUtilities.getWindowAncestor(this).dispose();
        });
        apply.addActionListener(e -> {
            applyStaged.run();
            SwingUtilities.getWindowAncestor(this).dispose();
        });
        run.addActionListener(e -> {
            SwingUtilities.getWindowAncestor(this).dispose();
            execute.accept(plan.unresolved().stream().map(IdentityResolutionPlan.Entry::member).toList());
        });
    }

    private static JComponent cards(List<IdentityResolutionPlan.Entry> entries, boolean existing) {
        if (entries.isEmpty()) return new JLabel("  (none)");
        List<Viewable> members = entries.stream().map(IdentityResolutionPlan.Entry::member).toList();
        java.util.Map<String, String> qids = new java.util.HashMap<>();
        entries.forEach(e -> qids.put(key(e.member()), e.currentQid()));
        return SearchableView.builder(members)
                .sample(members.get(0))
                .mode(objectview.render.RenderingMode.CARD)
                .collapsible(false)
                .columns(2)
                .cardDecorator(member -> existing ? IdentityChip.of(qids.get(key(member))) : null)
                .build();
    }

    private static String key(Viewable member) {
        return member.typeName() + '\0' + member.getIdentifier();
    }

    public static JDialog showModeless(
            Component owner, IdentityResolutionPlan plan, Consumer<List<Viewable>> execute,
            int staged, Runnable applyStaged, Runnable discardStaged) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner),
                staged > 0 ? "Wikidata identities — results"
                        : "Wikidata identities — plan",
                Dialog.ModalityType.MODELESS);
        dialog.setContentPane(new IdentityResolutionPlanPanel(
                plan, execute, staged,
                applyStaged == null ? () -> {} : applyStaged,
                discardStaged == null ? () -> {} : discardStaged));
        dialog.setSize(new Dimension(900, 680));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return dialog;
    }
}
