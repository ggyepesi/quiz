package domain.workbench.execution.ui;

import domain.build.GenerationRun;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.function.IntConsumer;

/**
 * Generation, reuse and inspection controls for an open project. The host owns the
 * selected class, run and workflow lifecycle; this panel presents that state and
 * delegates each explicit action to the host.
 */
public final class RunSection extends JPanel {
    /** A null Build project, Show instances or Statements action leaves its button out:
     *  a host whose own actions already offer it must not show a second route to it. */
    public record Actions(Runnable generateClass, Runnable generateDomain, Runnable buildProject,
                          Runnable remap, Runnable enrich, Runnable showInstances,
                          Runnable showRunReport, Runnable showStatements) { }

    private final JButton runReportButton =
            new JButton("Run report");

    private final JButton generateButton =
            new JButton("Generate class");

    // Generates EVERY class in the domain into one snapshot (each served as its
    // own type), vs "Generate class" which does the selected class only.
    private final JButton generateDomainButton =
            new JButton("Generate domain");

    // Runs the project's saved build (the project overview's Build list) — the same
    // coordinator the command line runs, reusing every current output.
    private final JButton buildProjectButton =
            new JButton("Build project");

    // Re-materialize the LAST download with the current model (no re-fetch) — picks
    // up canonicalization / display-name / mapping edits fast.
    private final JButton remapButton =
            new JButton("Remap (no download)");

    // Fetch what a newly DECLARED field needs, over the entities already downloaded —
    // additive, so it costs a property sweep rather than a re-extraction.
    private final JButton enrichButton =
            new JButton("Enrich (declared fields)");

    private final JButton showInstancesButton =
            new JButton("Show instances");

    private final JButton showStatementsButton =
            new JButton("Statements…");

    // Default 1, not 0: depth 0 silently drops every child-object edge (e.g. a
    // constellation's stars), which is a recurring "why are there no stars?"
    // trap. One level of children is the common intent; deeper is opt-in.
    private final JSpinner depthSpinner =
            new JSpinner(new SpinnerNumberModel(1, 0, 5, 1));
    // True while syncing the spinner to the selected class (so the change
    // listener doesn't write the value straight back).
    private boolean syncingDepth = false;

    public RunSection(Actions actions, IntConsumer depthChanged) {
        super(new GridLayout(0, 1, 0, 0));
        JLabel depthLabel = new JLabel("Depth:");
        String depthTip = "<html>How many levels of <b>child-object</b> reference "
                + "edges to follow when generating.<br>"
                + "0 = just the root class's instances (with scalar and inlined "
                + "list fields).<br>"
                + "1+ = also fetch the referenced objects' own fields, "
                + "recursively, that many levels deep.</html>";
        depthLabel.setToolTipText(depthTip);
        depthSpinner.setToolTipText(depthTip);

        // Keep generation and depth above reuse and inspection in the narrow pane.
        generateButton.setToolTipText("Generate the SELECTED class only.");
        generateDomainButton.setToolTipText("Generate EVERY class in the project "
                                                    + "into one local snapshot.");
        JPanel runRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        runRow1.add(generateButton);
        runRow1.add(generateDomainButton);
        buildProjectButton.setToolTipText("Run the saved build from the project overview: "
                + "reuse every current output, run the rest, save, and say why each step ran.");
        if (actions.buildProject() != null) runRow1.add(buildProjectButton);
        runRow1.add(depthLabel);
        runRow1.add(depthSpinner);

        // Reusing what was already downloaded: its own row, and one that stays short.
        // A FlowLayout row inside this fixed-height GridLayout WRAPS out of sight
        // rather than growing, so a row that outgrows a narrow window loses buttons.
        JPanel runRowReuse = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        remapButton.setToolTipText("Re-materialize the last download with the "
                                           + "current model — applies display-name / canonicalization / mapping "
                                           + "edits without re-fetching from Wikidata.");
        runRowReuse.add(remapButton);
        enrichButton.setToolTipText("Load fields declared since the last download — "
                                           + "fetches only those properties, for the entities already in the pool. "
                                           + "Remap cannot show a property nobody fetched.");
        runRowReuse.add(enrichButton);

        JPanel runRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        if (actions.showInstances() != null) runRow2.add(showInstancesButton);
        runReportButton.setToolTipText(
                "Inspect the kept and dropped self-reference decisions from the last run");
        runRow2.add(runReportButton);
        showStatementsButton.setToolTipText("Sample the selected class and show its "
                                                    + "instances' statements — property → values with qualifiers nested, "
                                                    + "plus coverage badges (example-first field discovery, #91).");
        if (actions.showStatements() != null) runRow2.add(showStatementsButton);

        add(runRow1);
        add(runRowReuse);
        add(runRow2);

        updateRunReport(null);
        generateButton.addActionListener(event -> actions.generateClass().run());
        generateDomainButton.addActionListener(event -> actions.generateDomain().run());
        listen(buildProjectButton, actions.buildProject());
        remapButton.addActionListener(event -> actions.remap().run());
        enrichButton.addActionListener(event -> actions.enrich().run());
        listen(showInstancesButton, actions.showInstances());
        runReportButton.addActionListener(event -> actions.showRunReport().run());
        listen(showStatementsButton, actions.showStatements());
        depthSpinner.addChangeListener(event -> {
            if (!syncingDepth) depthChanged.accept(depth());
        });
    }

    private static void listen(JButton button, Runnable action) {
        if (action != null) button.addActionListener(event -> action.run());
    }

    public int depth() {
        return ((Number) depthSpinner.getValue()).intValue();
    }

    /** Reflects the selected class's saved depth without changing the model. */
    public void syncDepth(int depth) {
        syncingDepth = true;
        try {
            depthSpinner.setValue(depth);
        } finally {
            syncingDepth = false;
        }
    }

    /** Same action and enabled-state check as pressing Generate domain, from graph review. */
    public void requestDomainGeneration() {
        generateDomainButton.doClick();
    }

    public void showProject(boolean model, boolean runnable) {
        generateDomainButton.setText(model ? "Generate model instances" : "Generate domain");
        setVisible(runnable);
    }

    public void updateRunReport(GenerationRun run) {
        int decisions = run == null ? 0 : run.selfReferenceAudit().ledger().entries().size();
        runReportButton.setText(decisions == 0
                ? "Run report" : "Self-reference decisions (" + decisions + ")");
        runReportButton.setEnabled(run != null);
    }

    /** Only mutating controls lock; run inspection stays available. */
    public void setRunLocked(boolean locked, boolean hasRun) {
        depthSpinner.setEnabled(!locked);
        generateButton.setEnabled(!locked);
        generateDomainButton.setEnabled(!locked);
        buildProjectButton.setEnabled(!locked);
        remapButton.setEnabled(!locked && hasRun);
        enrichButton.setEnabled(!locked && hasRun);
    }
}
