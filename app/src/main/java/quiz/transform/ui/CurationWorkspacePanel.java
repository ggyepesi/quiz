package quiz.transform.ui;

import objectview.Viewable;
import quiz.curation.CurationPlan;
import quiz.curation.CurationTask;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Executes a curation plan. The task list is coordination only; the detail is always
 * the shared ValidationPanel, so validation, identity, manual edits and Find Data cannot
 * drift into separate implementations. */
public final class CurationWorkspacePanel extends JPanel {
    private final DomainModel domain;
    private final CurationPlan plan;
    private final SwingQueryRunner runner;
    private final Runnable onCurated;
    private final IdentityResolutionLauncher identityResolver;
    private final JList<CurationTask> tasks;
    private final JPanel detail = new JPanel(new BorderLayout());
    // The scope captured at setup time, kept as IDENTIFIERS: on each task open it is
    // re-resolved against the live domain, so a render/regeneration (fresh instance
    // objects) or an applied correction is reflected rather than a stale snapshot.
    private final Set<ScopeKey> scopeKeys;

    public CurationWorkspacePanel(
            DomainModel domain, CurationPlan plan, SwingQueryRunner runner,
            Runnable onCurated, IdentityResolutionLauncher identityResolver) {
        super(new BorderLayout(8, 8));
        this.domain = domain;
        this.plan = plan;
        this.scopeKeys = new LinkedHashSet<>();
        for (Viewable v : plan.instances()) {
            if (v != null && v.getIdentifier() != null && !v.getIdentifier().isBlank()) {
                scopeKeys.add(key(v));
            }
        }
        this.runner = runner;
        this.onCurated = onCurated;
        this.identityResolver = identityResolver;
        this.tasks = new JList<>(plan.tasks().toArray(CurationTask[]::new));
        tasks.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tasks.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showTask(tasks.getSelectedValue());
        });
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(new JLabel(plan.label() + " · " + plan.instances().size() + " instance(s)"),
                BorderLayout.NORTH);
        left.add(new JScrollPane(tasks), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, detail);
        split.setResizeWeight(0.18);
        add(split, BorderLayout.CENTER);
        if (!plan.tasks().isEmpty()) tasks.setSelectedIndex(0);
    }

    /** Re-resolve the captured scope against the CURRENT domain instances, so edits and
     *  regenerations are reflected; falls back to the captured list if nothing resolves. */
    private List<Viewable> liveScope() {
        List<Viewable> live = new ArrayList<>();
        for (Viewable v : domain.instances()) {
            if (v != null && scopeKeys.contains(key(v))) {
                live.add(v);
            }
        }
        return List.copyOf(live);
    }

    private ScopeKey key(Viewable value) {
        String concreteType = domain.mostSpecificClass(value);
        return new ScopeKey(concreteType == null ? value.typeName() : concreteType,
                value.getIdentifier());
    }

    private record ScopeKey(String type, String identifier) { }

    private void showTask(CurationTask task) {
        if (task == null) return;
        ValidationPanel panel = new ValidationPanel(domain, liveScope(), runner,
                onCurated, identityResolver);
        detail.removeAll();
        detail.add(panel, BorderLayout.CENTER);
        detail.revalidate();
        detail.repaint();
        SwingUtilities.invokeLater(() -> panel.openTask(task));
    }
}
