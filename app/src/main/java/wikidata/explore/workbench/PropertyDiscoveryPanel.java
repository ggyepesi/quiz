package wikidata.explore.workbench;

import wikidata.WikidataIds;

import wikidata.explore.query.logical.DiscoverClassPropertiesQuery;
import wikidata.explore.query.logical.EntityLabelQuery;
import wikidata.explore.query.result.DiscoveredProperty;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.rule.RuleNode;

import objectview.render.RenderingMode;
import objectview.view.SearchableView;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import wikidata.ui.WikidataLinks;

/**
 * Unified class-level property discovery panel (replaces NodePropertyDiscoveryPanel + WikidataItemAttributePanel).
 *
 * Always queries at class level by sampling N instances of a class, then profiling
 * which properties they have (outgoing) or which properties reference them (incoming).
 *
 * The class to sample comes from the current tree node (via nodeSupplier) by default.
 * The user may override with a custom class QID.
 */
public class PropertyDiscoveryPanel extends JPanel {

    private static final int DEFAULT_SAMPLE = 10;

    // --- External wiring ---
    private SwingQueryRunner queryRunner;
    private Consumer<String> log = s -> {};
    private Supplier<RuleNode> nodeSupplier = () -> null;
    // Read-only title source. Must NOT be the (edit-applying) nodeSupplier:
    // refreshNodeTitle() is a display update and may run during a tree
    // selection, so mutating the model here re-fires selection → edit →
    // refresh and recurses until the stack overflows.
    private Supplier<String> nodeTitleSupplier = () -> null;
    private Runnable applyEdits = () -> {};
    private Consumer<DiscoveredProperty> onAddField = p -> {};
    private Consumer<String> onAddAllowedQid  = qid -> {};
    private Consumer<String> onAddExcludedQid = qid -> {};

    // --- UI ---
    private final JLabel     nodeLabel       = new JLabel("No node selected");
    private final JTextField customQidField  = new JTextField(8);
    private final JLabel     customQidLabel  = new JLabel(" ");
    private final JSpinner   sampleSpinner   = new JSpinner(new SpinnerNumberModel(DEFAULT_SAMPLE, 1, 50, 5));
    private final JButton    runButton       = new JButton("Run");
    private final JButton    byTargetButton  = new JButton("By target…");
    private final JButton    byTypeButton    = new JButton("By type…");
    private final JButton    qualifiersButton = new JButton("Qualifiers…");
    private final JButton    cancelButton    = new JButton("Cancel");
    private final JLabel     statusLabel     = new JLabel(" ");

    // --- Results ---
    private final List<DiscoveredProperty>     properties = new ArrayList<>();

    // The results go through the SAME searchable view as every other list in the app —
    // the bespoke JTable this replaces is why Discover had no search box. TABLE mode is
    // that view's columnar LAYOUT of the card path, not a table of its own: a row per
    // property, so a selected row is visible as such, and search/sort/field config come
    // with it. Row actions are buttons over the SELECTED row — per-row buttons are what
    // a bespoke table was bought for, and they stop reading at any size.
    private final JPanel resultHolder = new JPanel(new BorderLayout());
    private final JButton addFieldButton = new JButton("Add field");
    private final JButton allowButton = new JButton("Allow example");
    private final JButton excludeButton = new JButton("Exclude example");
    private DiscoveredProperty selectedProperty;
    // Each run rebuilds the view over new rows; the columns/search/sort the user chose
    // are about the SHAPE of a discovered property, which does not change between runs,
    // so they are carried across instead of resetting to the default on every Run.
    private objectview.search.SearchPanel.ConfigState resultConfig;

    private Timer qidResolveTimer;

    public PropertyDiscoveryPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    // --- Wiring setters ---

    /**
     * One-shot: the run button's action listener binds to the first
     * runner's workflow, so later calls are ignored.
     */
    public void setQueryRunner(SwingQueryRunner queryRunner) {
        if (this.queryRunner != null || queryRunner == null) {
            return;
        }

        this.queryRunner = queryRunner;

        queryRunner.registerCancelButton(cancelButton);

        queryRunner.wireButton(
                runButton,
                this::acceptDiscovery,
                this::buildDiscoveryQuery,
                this::showDiscoveryError);

        queryRunner.wireButton(
                byTargetButton,
                this::acceptByTarget,
                this::buildByTargetQuery,
                ex -> statusLabel.setText("Error: " + ex.getMessage()));

        queryRunner.wireButton(
                byTypeButton,
                this::acceptByType,
                this::buildByTypeQuery,
                ex -> statusLabel.setText("Error: " + ex.getMessage()));

        queryRunner.wireButton(
                qualifiersButton,
                this::acceptQualifiers,
                this::buildQualifiersQuery,
                ex -> statusLabel.setText("Error: " + ex.getMessage()));
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public void setNodeSupplier(Supplier<RuleNode> supplier) {
        this.nodeSupplier = supplier == null ? () -> null : supplier;
    }

    public void setApplyEdits(Runnable applyEdits) {
        this.applyEdits = applyEdits == null ? () -> {} : applyEdits;
    }

    public void setNodeTitleSupplier(Supplier<String> supplier) {
        this.nodeTitleSupplier = supplier == null ? () -> null : supplier;
    }

    public void refreshNodeTitle() {
        String title = nodeTitleSupplier.get();
        nodeLabel.setText(title == null || title.isBlank()
                ? "No node selected"
                : title);
    }

    public void onAddField(Consumer<DiscoveredProperty> handler) {
        this.onAddField = handler == null ? p -> {} : handler;
    }

    public void onAddAllowedQid(Consumer<String> handler) {
        this.onAddAllowedQid = handler == null ? qid -> {} : handler;
    }

    public void onAddExcludedQid(Consumer<String> handler) {
        this.onAddExcludedQid = handler == null ? qid -> {} : handler;
    }

    // --- UI build ---

    private void buildUi() {
        nodeLabel.setFont(nodeLabel.getFont().deriveFont(Font.BOLD));
        customQidField.setToolTipText("Leave empty to use the current tree node; or enter a class QID to override");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Class:"));
        controls.add(nodeLabel);
        controls.add(new JLabel("  Override QID:"));
        controls.add(customQidField);
        controls.add(customQidLabel);
        controls.add(new JLabel("N:"));
        controls.add(sampleSpinner);
        controls.add(runButton);
        byTargetButton.setToolTipText("For a multi-target class (e.g. P1411 → "
                + "many award categories): one instance per target, profiled "
                + "per target — reveals the subclass structure");
        controls.add(byTargetButton);
        byTypeButton.setToolTipText("Group the targets by their recipients' type "
                + "(P31): the discovered value (human / film) IS the subclass, "
                + "keyed by a real QID");
        controls.add(byTypeButton);
        qualifiersButton.setToolTipText("For a relational membership (e.g. P1411 "
                + "→ categories): discover the qualifiers on that relation's "
                + "statements (for-work, year, nominee…) + a generated "
                + "qualifier-load config — generic, not domain-specific");
        controls.add(qualifiersButton);
        controls.add(cancelButton);
        controls.add(statusLabel);

        JLabel hint = new JLabel(
                "<html>Profiles the selected class's members: which properties they "
                + "<b>have</b> (outgoing) or which <b>reference</b> them (incoming), "
                + "ranked by coverage — select a row, then Add field. "
                + "<b>Override QID</b> profiles a class the tree node doesn't supply "
                + "(a referenced-only class has none). <b>By target…</b> profiles one "
                + "instance per membership target; <b>By type…</b> groups targets by "
                + "recipient P31; <b>Qualifiers…</b> finds the relation's qualifiers."
                + "</html>");
        hint.setFont(hint.getFont().deriveFont(java.awt.Font.ITALIC));

        // The Discover tab is narrow, so this long control row would wrap and
        // clip its trailing fields/buttons. Keep them reachable via a
        // horizontal scrollbar.
        JPanel north = new JPanel(new BorderLayout(4, 2));
        north.add(hint, BorderLayout.NORTH);
        north.add(objectview.utils.swing.ScrollPaneUtils.horizontalOnly(controls),
                BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        addFieldButton.setToolTipText("Turn the selected property into a field of this class.");
        allowButton.setToolTipText("Add the selected row's example entity to the "
                + "class's allowed QIDs.");
        excludeButton.setToolTipText("Add the selected row's example entity to the "
                + "class's excluded QIDs.");
        actions.add(addFieldButton);
        actions.add(allowButton);
        actions.add(excludeButton);
        addFieldButton.addActionListener(e -> withSelected(onAddField::accept));
        allowButton.addActionListener(e -> withSelected(
                p -> { if (!p.exampleQid().isBlank()) onAddAllowedQid.accept(p.exampleQid()); }));
        excludeButton.addActionListener(e -> withSelected(
                p -> { if (!p.exampleQid().isBlank()) onAddExcludedQid.accept(p.exampleQid()); }));
        selectProperty(null);

        JPanel results = new JPanel(new BorderLayout(4, 4));
        results.add(resultHolder, BorderLayout.CENTER);
        results.add(actions, BorderLayout.SOUTH);
        add(results, BorderLayout.CENTER);
        showResults();

        cancelButton.setEnabled(false);

        customQidField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { scheduleQidResolve(); }
            public void removeUpdate(DocumentEvent e)  { scheduleQidResolve(); }
            public void changedUpdate(DocumentEvent e) {}
        });
    }

    private void scheduleQidResolve() {
        customQidLabel.setText(" ");
        if (qidResolveTimer != null) qidResolveTimer.stop();
        String raw = customQidField.getText().trim();
        if (!WikidataIds.isQid(raw)) return;
        qidResolveTimer = new Timer(600, e -> resolveQidLabel(raw));
        qidResolveTimer.setRepeats(false);
        qidResolveTimer.start();
    }

    private void resolveQidLabel(String qid) {
        if (queryRunner == null) return;

        queryRunner.runQuiet(
                new EntityLabelQuery(qid),
                label -> SwingUtilities.invokeLater(() ->
                        customQidLabel.setText(label != null
                                ? qid + " · " + label
                                : qid + " (no label)")),
                ignored -> {});
    }

    // --- Discovery ---

    private DiscoverClassPropertiesQuery buildDiscoveryQuery() {
        applyEdits.run();

        String customQid = customQidField.getText().trim();
        boolean useCustom = WikidataIds.isQid(customQid);
        int     n         = (int) sampleSpinner.getValue();

        RuleNode node = nodeSupplier.get();
        if (node == null && !useCustom) {
            statusLabel.setText("Select a tree node or enter a class QID.");
            return null;
        }

        if (!useCustom) {
            String name = node.name() != null ? node.name() : "?";
            String qid  = node.sourceQid();
            nodeLabel.setText(name + (qid != null && !qid.isBlank() ? " (" + qid + ")" : ""));
        }

        properties.clear();
        showDiscoveryProgress();
        statusLabel.setText("Discovering properties...");

        return new DiscoverClassPropertiesQuery(
                node,
                useCustom ? customQid : "",
                n);
    }

    private void showDiscoveryProgress() {
        selectProperty(null);
        JPanel progress = new JPanel(new BorderLayout(8, 8));
        progress.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JLabel explanation = new JLabel(
                "<html><b>Discovering properties…</b><br>Sampling instances, then "
                        + "profiling outgoing and incoming properties in parallel.<br>"
                        + "Results appear when both profiles have completed; Query Log "
                        + "shows which profile is still running.</html>");
        progress.add(explanation, BorderLayout.NORTH);
        progress.add(bar, BorderLayout.CENTER);
        resultHolder.removeAll();
        resultHolder.add(progress, BorderLayout.NORTH);
        resultHolder.revalidate();
        resultHolder.repaint();
    }

    private void showDiscoveryError(Exception failure) {
        String message = failure == null || failure.getMessage() == null
                || failure.getMessage().isBlank()
                ? "Discovery failed." : failure.getMessage();
        statusLabel.setText("Error: " + message);
        resultHolder.removeAll();
        resultHolder.add(new JLabel("   Discovery failed: " + message),
                BorderLayout.NORTH);
        resultHolder.revalidate();
        resultHolder.repaint();
    }

    /** Rebuilds the results as one searchable view of Viewables, laid out as a TABLE:
     *  a row per property, where a selected row reads as selected (a card's selection is
     *  hard to see, and these rows are compared column by column anyway). The PIDs and
     *  example QIDs stay bare in the data and become links through the value linker, so
     *  no column exists only to carry a URL. */
    private void showResults() {
        List<DiscoveredPropertyViewable> rows = properties.stream()
                .map(DiscoveredPropertyViewable::new).toList();

        SearchableView view = SearchableView.builder(rows)
                .type(DiscoveredPropertyViewable.class)
                .mode(RenderingMode.TABLE)
                .valueLinker(WikidataLinks.valueLinker())
                .hiddenFields(inapplicableColumns(rows))
                .emptyMessage("No properties discovered yet — pick a class and Run.")
                .configState(resultConfig)
                .configListener(state -> resultConfig = state)
                .selectionListener(selected -> selectProperty(
                        selected instanceof DiscoveredPropertyViewable row
                                ? row.property() : null))
                .build();

        resultHolder.removeAll();
        resultHolder.add(view, BorderLayout.CENTER);
        resultHolder.revalidate();
        resultHolder.repaint();
        selectProperty(null);
    }

    /** Which example columns this run cannot fill. A class with no image property has no
     *  image to show, and a profile whose examples are all literals denotes no entity —
     *  a column standing empty down every row only reads as something missing. */
    private static java.util.Set<String> inapplicableColumns(
            List<DiscoveredPropertyViewable> rows) {
        java.util.Set<String> hidden = new java.util.LinkedHashSet<>();
        if (rows.stream().noneMatch(DiscoveredPropertyViewable::hasImage)) {
            hidden.add("exampleImage");
        }
        if (rows.stream().noneMatch(DiscoveredPropertyViewable::hasExampleQid)) {
            hidden.add("exampleQid");
        }
        return hidden;
    }

    /** The actions act on the selection, so they are disabled until there is one, and
     *  the two example actions until that row HAS an example entity to act on. */
    private void selectProperty(DiscoveredProperty property) {
        selectedProperty = property;
        addFieldButton.setEnabled(property != null);
        boolean hasExample = property != null && !property.exampleQid().isBlank();
        allowButton.setEnabled(hasExample);
        excludeButton.setEnabled(hasExample);
        if (property != null) {
            statusLabel.setText("Selected " + property.pid()
                    + (property.label() == null || property.label().isBlank()
                            ? "" : " (" + property.label() + ")"));
        }
    }

    private void withSelected(Consumer<DiscoveredProperty> action) {
        if (selectedProperty == null) {
            JOptionPane.showMessageDialog(this,
                    "Select a discovered property first.",
                    "No selection", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        action.accept(selectedProperty);
    }

    private void acceptDiscovery(List<DiscoveredProperty> result) {
        List<DiscoveredProperty> rows =
                result == null ? List.of() : result;

        SwingUtilities.invokeLater(() -> {
            properties.clear();
            properties.addAll(rows);
            showResults();
            statusLabel.setText(rows.isEmpty()
                    ? "No properties found."
                    : rows.size() + " properties found.");
        });
    }

    // --- Properties by target (subclass-structure view) ---

    private wikidata.explore.query.logical.DiscoverPropertiesByTargetQuery
            buildByTargetQuery() {
        applyEdits.run();
        RuleNode node = nodeSupplier.get();
        if (node == null) {
            statusLabel.setText("Select a class node first.");
            return null;
        }
        if (node.allSourceQids().size() < 2) {
            statusLabel.setText("\"By target\" needs a multi-target class "
                    + "(several membership targets, e.g. P1411 → categories).");
            return null;
        }
        statusLabel.setText("Profiling per target…");
        return new wikidata.explore.query.logical.DiscoverPropertiesByTargetQuery(
                node, (int) sampleSpinner.getValue());
    }

    // --- Recipient type by target (the discovered-P31 subclass split) ---

    private wikidata.explore.query.logical.DiscoverRecipientTypeQuery
            buildByTypeQuery() {
        applyEdits.run();
        RuleNode node = nodeSupplier.get();
        if (node == null) {
            statusLabel.setText("Select a class node first.");
            return null;
        }
        if (node.allSourceQids().size() < 2) {
            statusLabel.setText("\"By type\" needs a multi-target class "
                    + "(several membership targets, e.g. P1411 → categories).");
            return null;
        }
        statusLabel.setText("Discovering recipient type per target…");
        return new wikidata.explore.query.logical.DiscoverRecipientTypeQuery(
                node, (int) sampleSpinner.getValue());
    }

    // Each target's recipients resolve to a P31 distribution. CAVEAT (verified on
    // Oscars): a membership predicate like P1411 links BOTH the person and the
    // work to a category, so the set is mixed (Best Actor: film 435 / human 251) —
    // a single "dominant type" mislabels person categories. So this view shows the
    // FULL breakdown per target and a type→targets index, not a winner-take-all
    // subclass. Rows: [Target, TargetQid, Type, TypeQid, Count].
    private void acceptByType(
            wikidata.explore.query.result.TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();

        record TypeCount(WikidataLinks.Linked type, int n) {}
        java.util.Map<String, WikidataLinks.Linked> targetById =
                new java.util.LinkedHashMap<>();
        // target qid -> its type distribution (desc by count, query already orders).
        java.util.Map<String, java.util.List<TypeCount>> typesByTarget =
                new java.util.LinkedHashMap<>();
        // type qid -> the targets that have any recipient of that type (inverse).
        java.util.Map<String, WikidataLinks.Linked> typeById =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.List<WikidataLinks.Linked>> targetsByType =
                new java.util.LinkedHashMap<>();
        for (List<Object> r : rows) {
            String targetQid = str(r, 1);
            String targetLabel = str(r, 0);
            String typeQid = str(r, 3);
            String typeLabel = str(r, 2);
            int n;
            try { n = Integer.parseInt(str(r, 4)); } catch (Exception e) { n = 0; }
            WikidataLinks.Linked target =
                    targetById.computeIfAbsent(targetQid,
                            k -> WikidataLinks.of(targetQid, targetLabel));
            WikidataLinks.Linked type = WikidataLinks.of(typeQid, typeLabel);
            typesByTarget.computeIfAbsent(targetQid, k -> new ArrayList<>())
                    .add(new TypeCount(type, n));
            typeById.putIfAbsent(typeQid, type);
            targetsByType.computeIfAbsent(typeQid, k -> new ArrayList<>()).add(target);
        }

        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(typeById.size()
                    + " recipient type(s) across " + targetById.size() + " targets.");
            // Left tree: each target -> its type distribution (the honest signal).
            javax.swing.tree.DefaultMutableTreeNode root =
                    new javax.swing.tree.DefaultMutableTreeNode(
                            "Recipient types by target (" + targetById.size() + ")");
            typesByTarget.forEach((targetQid, types) -> {
                javax.swing.tree.DefaultMutableTreeNode tn =
                        new javax.swing.tree.DefaultMutableTreeNode(
                                targetById.get(targetQid).label() + "  — " + types.size());
                for (TypeCount tc : types) {
                    tn.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new TypeLeaf(tc.type(), tc.n())));
                }
                root.add(tn);
            });
            JTree tree = new JTree(root);

            // Right: clicking a type shows which categories have recipients of it.
            javax.swing.JEditorPane detail = WikidataLinks.pane(
                    "Click a recipient type to see which categories have it.");
            tree.addTreeSelectionListener(e -> {
                Object n = tree.getLastSelectedPathComponent();
                if (n instanceof javax.swing.tree.DefaultMutableTreeNode dn
                        && dn.getUserObject() instanceof TypeLeaf leaf) {
                    java.util.List<WikidataLinks.Linked> ts =
                            targetsByType.getOrDefault(leaf.type().id(), List.of());
                    StringBuilder html = new StringBuilder();
                    html.append(leaf.type().html()).append(" (")
                        .append(WikidataLinks.linkHtml(leaf.type().id(), leaf.type().id()))
                        .append(") — ").append(ts.size()).append(" categor(ies):\n");
                    for (WikidataLinks.Linked t : ts) {
                        html.append("\n  • ").append(t.html());
                    }
                    WikidataLinks.setHtml(detail, html.toString());
                }
            });

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    new JScrollPane(tree), new JScrollPane(detail));
            split.setResizeWeight(0.55);
            split.setPreferredSize(new java.awt.Dimension(760, 480));

            JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                    "Recipient type by target",
                    java.awt.Dialog.ModalityType.MODELESS);
            dialog.setContentPane(split);
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        });
    }

    // A recipient-type leaf: the type kept WITH its qid + how many recipients.
    private record TypeLeaf(WikidataLinks.Linked type, int count) {
        @Override public String toString() {
            return type.label() + "  (" + type.id() + ", " + count + ")";
        }
    }

    // --- Discover the membership relation's qualifiers (generic) ---

    private wikidata.explore.query.logical.DiscoverQualifiersQuery
            buildQualifiersQuery() {
        applyEdits.run();
        RuleNode node = nodeSupplier.get();
        if (node == null) {
            statusLabel.setText("Select a class node first.");
            return null;
        }
        String pid = RuleNode.cleanPid(node.propertyPid());
        if (!WikidataIds.isPid(pid) || pid.equals("P31")) {
            statusLabel.setText("\"Qualifiers\" needs a relational membership "
                    + "(a non-P31 relation, e.g. P1411 → categories).");
            return null;
        }
        statusLabel.setText("Discovering qualifiers…");
        return new wikidata.explore.query.logical.DiscoverQualifiersQuery(
                node, Math.max(100, (int) sampleSpinner.getValue() * 20));
    }

    // Rows: [Qualifier, PID, Kind, Count]. Show the qualifiers (linked) + a
    // GENERATED qualifier-load config (noise dropped) the user can run as a
    // Transform — same shape for any domain, no hand-authoring.
    private void acceptQualifiers(
            wikidata.explore.query.result.TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        RuleNode node = nodeSupplier.get();
        String relation = node == null ? "" : RuleNode.cleanPid(node.propertyPid());
        String entityType = node == null || node.name() == null ? "Class" : node.name();

        List<wikidata.explore.transform.QualifierLoadConfigs.Discovered> discovered =
                new ArrayList<>();
        for (List<Object> r : rows) {
            String pid = str(r, 1);
            String label = str(r, 0);
            wikidata.explore.transform.QualifierLoadConfig.Kind kind;
            try {
                kind = wikidata.explore.transform.QualifierLoadConfig.Kind
                        .valueOf(str(r, 2));
            } catch (Exception e) {
                kind = wikidata.explore.transform.QualifierLoadConfig.Kind.STRING;
            }
            discovered.add(new wikidata.explore.transform.QualifierLoadConfigs
                    .Discovered(pid, label, kind));
        }

        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(rows.size() + " qualifiers discovered.");

            StringBuilder list = new StringBuilder();
            for (List<Object> r : rows) {
                list.append("\n  • ")
                    .append(WikidataLinks.linkHtml(str(r, 1), str(r, 0)))
                    .append("  (").append(WikidataLinks.linkHtml(str(r, 1), str(r, 1)))
                    .append(", ").append(str(r, 2)).append(", ")
                    .append(str(r, 3)).append(")");
            }
            javax.swing.JEditorPane listPane = WikidataLinks.pane(
                    "Qualifiers on " + relation + " statements:" + list);

            // The aid: a full proposed transform — load + canonical reify that
            // de-denormalizes the relation (subject ∨ qualifier + dedup).
            wikidata.explore.transform.TransformConfig tc =
                    wikidata.explore.transform.QualifierLoadConfigs.suggestTransform(
                            entityType, relation, "", discovered);
            String json = wikidata.explore.transform.TransformConfigStore.toJson(tc);

            JTextArea jsonArea = new JTextArea(json, 14, 48);
            jsonArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            jsonArea.setEditable(false);

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JScrollPane(listPane),
                    new JScrollPane(jsonArea));
            split.setResizeWeight(0.45);
            split.setPreferredSize(new java.awt.Dimension(640, 520));

            JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                    "Qualifiers of " + relation,
                    java.awt.Dialog.ModalityType.MODELESS);
            dialog.setLayout(new BorderLayout(0, 4));
            dialog.add(split, BorderLayout.CENTER);
            dialog.add(new JLabel("  Proposed transform: qualifier-load + a "
                    + "canonical reify (subject ∨ qualifier + dedup) that "
                    + "de-denormalizes the relation. Paste into the Transform "
                    + "dialog; rename the event type / set a value-type filter."),
                    BorderLayout.SOUTH);
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        });
    }

    // A property leaf carries its pid + label so a click can look up every target
    // that has it (the inverse index = the first step of subclass discovery).
    private record PropLeaf(String pid, String label, String coverage) {
        @Override public String toString() {
            return label + "  (" + pid + ", " + coverage + ")";
        }
    }

    // Rows: Target | Property | PID | Count → a tree grouped by target (each
    // target's property profile), plus a property→targets index: clicking a
    // property lists every target it appears in, so a property shared by all the
    // person-categories visibly clusters them — the start of subclass discovery.
    private void acceptByTarget(
            wikidata.explore.query.result.TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        java.util.Map<String, java.util.List<PropLeaf>> byTarget =
                new java.util.LinkedHashMap<>();
        // pid -> the targets (label kept WITH its qid) that carry it.
        java.util.Map<String, java.util.List<WikidataLinks.Linked>> targetsByPid =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, String> labelByPid = new java.util.HashMap<>();
        for (List<Object> r : rows) {
            String target = str(r, 0);
            String prop = str(r, 1);
            String pid = str(r, 2);
            String targetQid = str(r, 4);
            byTarget.computeIfAbsent(target, k -> new ArrayList<>())
                    .add(new PropLeaf(pid, prop, str(r, 3)));
            targetsByPid.computeIfAbsent(pid, k -> new ArrayList<>())
                    .add(WikidataLinks.of(targetQid, target));
            labelByPid.putIfAbsent(pid, prop);
        }
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(byTarget.size() + " targets profiled.");
            javax.swing.tree.DefaultMutableTreeNode root =
                    new javax.swing.tree.DefaultMutableTreeNode(
                            "Properties by target (" + byTarget.size() + ")");
            byTarget.forEach((target, props) -> {
                javax.swing.tree.DefaultMutableTreeNode t =
                        new javax.swing.tree.DefaultMutableTreeNode(
                                target + "  — " + props.size());
                for (PropLeaf p : props) {
                    t.add(new javax.swing.tree.DefaultMutableTreeNode(p));
                }
                root.add(t);
            });
            JTree tree = new JTree(root);

            // Detail shows the property + every target carrying it as LINKS — the
            // labels stay paired with their qid/pid, so each is clickable (one
            // WikidataLinks call, no free-text scanning).
            javax.swing.JEditorPane detail = WikidataLinks.pane(
                    "Click a property to see which targets have it.");

            tree.addTreeSelectionListener(e -> {
                Object n = tree.getLastSelectedPathComponent();
                if (n instanceof javax.swing.tree.DefaultMutableTreeNode dn
                        && dn.getUserObject() instanceof PropLeaf leaf) {
                    java.util.List<WikidataLinks.Linked> ts =
                            targetsByPid.getOrDefault(leaf.pid(), List.of());
                    StringBuilder html = new StringBuilder();
                    html.append(WikidataLinks.linkHtml(leaf.pid(), leaf.label()))
                        .append(" (").append(WikidataLinks.linkHtml(leaf.pid(), leaf.pid()))
                        .append(") — ").append(ts.size()).append(" target(s):\n");
                    for (WikidataLinks.Linked t : ts) {
                        html.append("\n  • ").append(t.html());
                    }
                    WikidataLinks.setHtml(detail, html.toString());
                }
            });

            JScrollPane treeScroll = new JScrollPane(tree);
            JScrollPane detailScroll = new JScrollPane(detail);
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    treeScroll, detailScroll);
            split.setResizeWeight(0.55);
            split.setPreferredSize(new java.awt.Dimension(760, 480));

            // Auto subclass detection: cluster the targets by property-profile
            // similarity (Jaccard, average linkage) into candidate `extends`
            // subclasses. Slider = how alike two categories must be to merge.
            JSlider simSlider = new JSlider(10, 90, 40);
            simSlider.setMajorTickSpacing(20);
            simSlider.setPaintTicks(true);
            simSlider.setPaintLabels(true);
            simSlider.setPreferredSize(new java.awt.Dimension(180, 44));
            JLabel simValue = new JLabel("similarity ≥ 0.40");
            simSlider.addChangeListener(e -> simValue.setText(
                    String.format("similarity ≥ %.2f", simSlider.getValue() / 100.0)));
            JButton suggest = new JButton("Suggest subclasses");
            suggest.setToolTipText("Cluster these targets by shared properties "
                    + "into candidate Person/Film subclasses");
            suggest.addActionListener(e -> showSubclassClusters(
                    rows, simSlider.getValue() / 100.0, split));

            JPanel clusterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            clusterBar.add(new JLabel("Merge when"));
            clusterBar.add(simSlider);
            clusterBar.add(simValue);
            clusterBar.add(suggest);

            JPanel content = new JPanel(new BorderLayout(4, 4));
            content.add(clusterBar, BorderLayout.NORTH);
            content.add(split, BorderLayout.CENTER);

            JDialog dialog = new JDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Properties by target", java.awt.Dialog.ModalityType.MODELESS);
            dialog.setContentPane(content);
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        });
    }

    // Auto subclass detection: cluster targets by their property profiles and show
    // each cluster as a candidate subclass — its shared (signature) properties + its
    // member targets. A 3-target person cluster vs a 2-target film cluster is the
    // Person/Film `extends` split the nominee root latently holds.
    private void showSubclassClusters(List<List<Object>> rows, double threshold,
                                      Component parent) {
        List<wikidata.explore.analysis.SubclassClustering.Cluster> clusters =
                wikidata.explore.analysis.SubclassClustering.clusterRows(
                        rows, threshold, 0.5);

        javax.swing.tree.DefaultMutableTreeNode root =
                new javax.swing.tree.DefaultMutableTreeNode(
                        "Suggested subclasses (" + clusters.size() + ", similarity ≥ "
                                + String.format("%.2f", threshold) + ")");
        int i = 1;
        for (var c : clusters) {
            String top = c.signature().stream().limit(3)
                    .map(wikidata.explore.analysis.SubclassClustering
                            .SignatureProperty::label)
                    .reduce((a, b) -> a + ", " + b).orElse("—");
            javax.swing.tree.DefaultMutableTreeNode cn =
                    new javax.swing.tree.DefaultMutableTreeNode(
                            "Subclass " + (i++) + "  (" + c.members().size()
                                    + " targets · " + top + ")");

            javax.swing.tree.DefaultMutableTreeNode props =
                    new javax.swing.tree.DefaultMutableTreeNode(
                            "shared properties (" + c.signature().size() + ")");
            for (var sp : c.signature()) {
                props.add(new javax.swing.tree.DefaultMutableTreeNode(
                        sp.label() + "  (" + sp.pid() + ", " + sp.members() + "/"
                                + sp.clusterSize() + ")"));
            }
            cn.add(props);

            javax.swing.tree.DefaultMutableTreeNode members =
                    new javax.swing.tree.DefaultMutableTreeNode(
                            "members (" + c.members().size() + ")");
            for (String m : c.members()) {
                members.add(new javax.swing.tree.DefaultMutableTreeNode(m));
            }
            cn.add(members);
            root.add(cn);
        }

        JTree tree = new JTree(root);
        for (int r = 0; r < tree.getRowCount(); r++) {
            tree.expandRow(r);
        }
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new java.awt.Dimension(520, 460));

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                "Suggested subclasses", java.awt.Dialog.ModalityType.MODELESS);
        dialog.setContentPane(scroll);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static String str(List<Object> r, int i) {
        Object v = r == null || i >= r.size() ? null : r.get(i);
        return v == null ? "" : v.toString();
    }

}
