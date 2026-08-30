package workbench;

import wikidata.WikidataIds;

import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.logical.ExploreEntityQuery;
import wikidata.explore.query.logical.RelationMembersQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import wikidata.ui.WikidataLinks;

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
    private BiConsumer<String, String> onUseProperty = (pid, label) -> {};

    private final JTextField searchField = new JTextField(22);
    private final JTextField qidField = new JTextField(8);
    private final JButton openQidButton = new JButton("Open QID");
    private final JButton searchButton = new JButton("Search");
    private final EntityResultPanel candidates =
            new EntityResultPanel(List.of("QID", "Label", "Description"), 0, true);

    private final JButton exploreButton = new JButton("Explore entity relations");
    private final JButton useSourceButton = new JButton("Use as class type (P31)");
    private final JPanel selectionsButtonHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private WorkbenchSelections workbenchSelections;

    // Relations render as Viewables through objectview's SearchableView, so they get the
    // same search / per-field sort / filter (incl. by Kind) as every other card view — no
    // bespoke table. The holder swaps in a fresh card view each time the relation set changes.
    private final JPanel relationHolder = new JPanel(new BorderLayout());
    private List<RelationView> relations = List.of();
    private List<RelationView> selectedRelationViews = List.of();
    private RelationView selectedRelationView;
    private final JButton showMembersButton = new JButton("Show members");
    private final JButton addTargetsButton = new JButton("Add as relation targets");
    private final JButton addSeedsButton = new JButton("Add as Seed QIDs");
    private final JButton usePropertyButton = new JButton("Use selected property");
    private final JLabel hint = new JLabel(
            "<html>Search → pick an entity → Explore relations → pick a "
                    + "relation: <b>Show members</b> (then double-click a member to "
                    + "<b>follow</b> it and explore further), or <b>add members as "
                    + "Seed QIDs</b>. <b>◀ Back</b> retraces your path.</html>");
    private boolean explorationOnly;
    // Property-picker mode explores only the seed's OWN properties (outgoing); the incoming
    // scan is slow and irrelevant when the goal is to pick a field's Wikidata property.
    private boolean outgoingOnly;
    // The entity whose relations are shown — used to fetch a relation's members.
    private String exploredQid = "";
    private String exploredLabel = "";

    // Graph navigation: a header showing the current entity, a Back button, and a
    // history stack — following a relation's member makes it the next explored
    // entity, so the user walks the Wikidata graph live.
    private final JLabel currentLabel = new JLabel(" ");
    // The current subject must travel with the relation results. currentLabel lives
    // on the Entity tab, so it disappeared precisely when the UI switched to Relations.
    // This second view keeps the QID visible (and clickable) above the relation set.
    private final JEditorPane relationSubject = WikidataLinks.pane("");
    private final JButton backButton = new JButton("◀ Back");
    private final java.util.Deque<String[]> history = new java.util.ArrayDeque<>();
    // Set when following a member (vs exploring a fresh candidate from search).
    private String pendingQid = "";
    private String pendingLabel = "";

    private final JLabel status = new JLabel(" ");
    // Entity search and the relation list are TABS (like the identity resolver's
    // Confident/Ambiguous/Unresolved), not a vertical split — so each gets the full height
    // and the relations always have room to render and scroll.
    private JTabbedPane tabs;
    private JComponent entityPanel;
    private JComponent relationPanel;

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

    public void onUseProperty(BiConsumer<String, String> handler) {
        this.onUseProperty = handler == null ? (p, l) -> {} : handler;
    }

    /** Share explicit, reusable entity/property selections with the surrounding workbench. */
    public void selections(WorkbenchSelections selections) {
        this.workbenchSelections = selections;
        selectionsButtonHolder.removeAll();
        if (selections != null) selectionsButtonHolder.add(
                new SelectionsButton(selections)
                        .addEntities("Add selected entities",
                                this::canSetSelectedEntity, this::setSelectedEntity)
                        .addProperties("Add selected properties",
                                () -> !selectedRelationViews.isEmpty(),
                                this::setSelectedProperty));
        selectionsButtonHolder.revalidate();
        selectionsButtonHolder.repaint();
        updateButtons();
    }

    /** Configure the panel to PICK A PROPERTY of a given entity (mode 2 of Explore): its
     *  relations are shown, the entity-mutating actions are hidden, and choosing a relation
     *  returns its (pid, label) via {@link #onUseProperty}. The caller supplies the entity
     *  (via {@link #presentSeed}) — Explore does not sample. */
    public void propertyPicker(String useButtonLabel) {
        outgoingOnly = true;
        useSourceButton.setVisible(false);
        addTargetsButton.setVisible(false);
        addSeedsButton.setVisible(false);
        showMembersButton.setVisible(false);
        usePropertyButton.setText(useButtonLabel == null || useButtonLabel.isBlank()
                ? "Use selected property" : useButtonLabel);
        usePropertyButton.setVisible(true);
        hint.setText("<html>Pick a relation (property) in the list → <b>"
                + usePropertyButton.getText() + "</b>.</html>");
        showRelationPanel(true);
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

    /** Reuse the graph browser as a one-shot entity PICKER: search (or explore relations
     * to verify) to find the entity, pick a candidate, then the single action emits it via
     * {@link #onUseAsSourceQid}. Model-mutating actions are hidden; the use button is
     * relabeled. This is the inverse of {@link #explorationOnly()}. */
    public void entityPicker(String useButtonLabel) {
        entityPicker(useButtonLabel, true);
    }

    /** Configure the same entity picker with optional graph/relation inspection. Identity
     * curation needs only entity search and selection, while ModelBuilder can retain the
     * relation browser for verifying and navigating graph structure. */
    public void entityPicker(String useButtonLabel, boolean relationPanelNeeded) {
        String label = useButtonLabel == null || useButtonLabel.isBlank()
                ? "Use selected entity" : useButtonLabel;
        useSourceButton.setText(label);
        useSourceButton.setToolTipText("Assign the selected entity");
        addTargetsButton.setVisible(false);
        addSeedsButton.setVisible(false);
        if (relationPanelNeeded) {
            hint.setText("<html>Search (or Explore relations to verify) → pick an entity in the "
                    + "top list → <b>" + label + "</b>. Double-click a member to follow it; "
                    + "<b>◀ Back</b> retraces.</html>");
        }
        showRelationPanel(relationPanelNeeded);
    }

    private void showRelationPanel(boolean visible) {
        exploreButton.setVisible(visible);
        if (tabs == null) return;
        int idx = tabs.indexOfComponent(relationPanel);
        if (visible && idx < 0) {
            tabs.addTab("Relations", relationPanel);
        } else if (!visible && idx >= 0) {
            tabs.removeTabAt(idx);
        }
        revalidate();
        repaint();
    }

    private void selectTab(JComponent tab) {
        if (tabs == null) {
            return;
        }
        int idx = tabs.indexOfComponent(tab);
        if (idx >= 0) {
            tabs.setSelectedIndex(idx);
        }
    }

    /** Pre-seed the search box and run it — e.g. the instance name when opening the picker.
     *  Requires {@link #setQueryRunner} to have wired the search button first. */
    public void searchFor(String term) {
        if (term == null || term.isBlank()) {
            return;
        }
        searchField.setText(term);
        searchButton.doClick();
    }

    /** Open the explorer as a modal entity picker; the chosen (qid, label) is delivered to
     *  {@code onSelected} and the dialog closes. The single reusable "find a Wikidata entity"
     *  surface — search or browse relations, then use the selected one. */
    public static void showPicker(
            Component parent, SwingQueryRunner runner, String initialName,
            BiConsumer<String, String> onSelected) {
        showPicker(parent, runner, initialName, true, onSelected);
    }

    /** Explore mode 2 — find a PROPERTY of the caller-supplied entity and RETURN the
     *  selection. The caller provides {@code seedQid} (the entity to inspect); Explore
     *  presents it ready and the user clicks Explore to load its relations, picks one,
     *  and (pid, label) is delivered to {@code onSelected}. Explore does not sample, and
     *  fires no query until the user asks. */
    public static void findProperty(
            Component parent, SwingQueryRunner runner,
            String seedQid, String seedLabel,
            BiConsumer<String, String> onSelected) {
        Window owner = quiz.ui.Dialogs.owner(parent);
        JDialog dialog = new JDialog(owner, "Find Wikidata property",
                Dialog.ModalityType.APPLICATION_MODAL);
        quiz.ui.Dialogs.raiseOnOpen(dialog);
        ExploreByExamplePanel explorer = new ExploreByExamplePanel();
        explorer.setQueryRunner(runner);
        explorer.propertyPicker("Use selected property");
        explorer.onUseProperty((pid, label) -> {
            dialog.dispose();
            if (onSelected != null) onSelected.accept(pid, label);
        });
        dialog.add(explorer);
        dialog.setMinimumSize(new Dimension(840, 640));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        if (seedQid != null && !seedQid.isBlank()) {
            SwingUtilities.invokeLater(() -> explorer.presentSeed(seedQid, seedLabel));
        }
        dialog.setVisible(true);
    }

    public static void showPicker(
            Component parent, SwingQueryRunner runner, String initialName,
            boolean relationPanelNeeded,
            BiConsumer<String, String> onSelected) {
        Window owner = quiz.ui.Dialogs.owner(parent);
        JDialog dialog = new JDialog(owner, "Find Wikidata entity",
                Dialog.ModalityType.APPLICATION_MODAL);
        quiz.ui.Dialogs.raiseOnOpen(dialog);
        ExploreByExamplePanel explorer = new ExploreByExamplePanel();
        explorer.setQueryRunner(runner);
        explorer.entityPicker("Use selected entity", relationPanelNeeded);
        explorer.onUseAsSourceQid((qid, label) -> {
            // Close immediately: the consumer may persist curation and rebuild a large
            // instance panel. Keeping the modal picker visible during that work makes a
            // successful click look unresponsive.
            dialog.dispose();
            if (onSelected != null) onSelected.accept(qid, label);
        });
        dialog.add(explorer);
        dialog.setMinimumSize(new Dimension(840, relationPanelNeeded ? 640 : 430));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        if (initialName != null && !initialName.isBlank()) {
            SwingUtilities.invokeLater(() -> explorer.searchFor(initialName));
        }
        dialog.setVisible(true);
    }

    private void buildUi() {
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        searchRow.add(new JLabel("Describe / name it:"));
        searchRow.add(searchField);
        searchRow.add(searchButton);
        searchRow.add(new JLabel("or QID:"));
        searchRow.add(qidField);
        searchRow.add(openQidButton);

        candidates.setColumnWidths(70, 180, 420);

        JPanel midRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        midRow.add(exploreButton);
        midRow.add(useSourceButton);
        midRow.add(selectionsButtonHolder);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        actionRow.add(showMembersButton);
        addTargetsButton.setToolTipText("Add the relation's members to the selected "
                + "class's \"Also include types\" (membership relation targets)");
        actionRow.add(addTargetsButton);
        actionRow.add(addSeedsButton);
        // The workbench takes a property through the typed selection register: pick it,
        // "Set selected property", then use it where a property is wanted. This button
        // is the PICKER dialog's return channel and propertyPicker() reveals it there;
        // in the ordinary Explore tab nothing listens, so it would do nothing.
        usePropertyButton.setVisible(false);
        actionRow.add(usePropertyButton);
        actionRow.add(status);

        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));

        useSourceButton.setToolTipText("Make the selected entity the selected "
                + "class's membership type (instance-of / P31).");

        // Entity tab: search / pick the entity, then Explore. currentLabel shows what will be
        // (or is being) explored, right next to the Explore button.
        JPanel entitySouth = new JPanel(new BorderLayout(4, 2));
        entitySouth.add(midRow, BorderLayout.NORTH);
        entitySouth.add(currentLabel, BorderLayout.SOUTH);
        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.add(searchRow, BorderLayout.NORTH);
        top.add(candidates, BorderLayout.CENTER);
        top.add(entitySouth, BorderLayout.SOUTH);
        entityPanel = top;

        // Relations tab: Back navigation, the relation cards (own scroll), actions + hint.
        JPanel navRow = new JPanel(new BorderLayout(8, 2));
        backButton.setEnabled(false);
        backButton.setToolTipText("Back to the previously explored entity");
        navRow.add(backButton, BorderLayout.WEST);
        navRow.add(relationSubject, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(4, 2));
        bottom.add(navRow, BorderLayout.NORTH);
        bottom.add(relationHolder, BorderLayout.CENTER);
        JPanel bottomSouth = new JPanel(new BorderLayout());
        bottomSouth.add(actionRow, BorderLayout.NORTH);
        bottomSouth.add(hint, BorderLayout.SOUTH);
        bottom.add(bottomSouth, BorderLayout.SOUTH);
        relationPanel = bottom;

        tabs = new JTabbedPane();
        tabs.addTab("Entity", entityPanel);
        tabs.addTab("Relations", relationPanel);
        add(tabs, BorderLayout.CENTER);

        searchField.addActionListener(e -> searchButton.doClick());
        candidates.onSelectionChanged(this::updateButtons);
        showRelations(List.of());

        exploreButton.addActionListener(e -> { /* wired via queryRunner */ });
        backButton.addActionListener(e -> {
            if (!history.isEmpty()) {
                String[] prev = history.pop();
                exploreEntity(prev[0], prev[1], false);
            }
        });
        useSourceButton.addActionListener(e -> {
            String[] picked = pickEntity(candidates.hasSelection(),
                    candidates.firstSelected(0), candidates.firstSelected(1),
                    exploredQid, exploredLabel);
            if (picked != null) {
                onUseAsSourceQid.accept(picked[0], picked[1]);
                status.setText("Used " + picked[0] + ".");
            }
        });
        usePropertyButton.setVisible(false);
        usePropertyButton.addActionListener(e -> {
            RelationView rel = selectedRelationView;
            if (rel != null) {
                onUseProperty.accept(rel.pid(), rel.relationLabel());
                status.setText("Used " + rel.pid() + ".");
            }
        });
        openQidButton.addActionListener(e -> openQid());
        // addSeedsButton is wired via the queryRunner (it fetches the relation's
        // members on demand, then onAddSeedQids — see buildMembersQuery /
        // acceptMembers).

        updateButtons();
    }

    /** Open a pasted QID: fetch its real label (via the API) so it isn't shown/emitted as
     *  its own label, then explore it. Falls back to the bare QID if there's no runner. */
    private void openQid() {
        String qid = qidField.getText().trim().toUpperCase(java.util.Locale.ROOT);
        if (!WikidataIds.isQid(qid)) {
            status.setText("Enter a QID such as Q42.");
            return;
        }
        // Open-QID is a new explicit choice. An old highlighted search row must not
        // continue to win in pickEntity while this lookup is in progress.
        candidates.setRows(List.of());
        exploredQid = "";
        exploredLabel = "";
        WikidataLinks.setHtml(relationSubject, "");
        updateButtons();
        if (queryRunner == null) {
            status.setText("A query connection is required to verify " + qid + ".");
            return;
        }
        status.setText("Opening " + qid + "…");
        queryRunner.runQuiet(new quiz.enrichment.WikimediaEntityLookup().byQid(qid),
                entity -> SwingUtilities.invokeLater(() -> {
                    boolean found = entity != null
                            && (!(entity.label() == null || entity.label().isBlank())
                            || !(entity.description() == null || entity.description().isBlank())
                            || !entity.claims().isEmpty());
                    if (found) {
                        exploreQid(qid, entity.label() == null || entity.label().isBlank()
                                ? qid : entity.label());
                    } else {
                        status.setText(qid + " was not found.");
                        updateButtons();
                    }
                }),
                ex -> SwingUtilities.invokeLater(() -> {
                    status.setText("Could not verify " + qid + ": " + ex.getMessage());
                    updateButtons();
                }));
    }

    /** The entity the "use" action emits. A visibly-selected candidate ALWAYS wins; the
     *  explored/opened entity is the fallback only for the graph-follow / Open-QID paths,
     *  where there is no candidate row. (Preferring exploredQid unconditionally emitted the
     *  wrong entity after exploring A then re-selecting B in the top list.) */
    static String[] pickEntity(boolean hasCandidate, String candidateQid, String candidateLabel,
                               String exploredQid, String exploredLabel) {
        if (hasCandidate && candidateQid != null && WikidataIds.isQid(candidateQid)) {
            return new String[]{candidateQid, candidateLabel};
        }
        if (exploredQid != null && WikidataIds.isQid(exploredQid)) {
            return new String[]{exploredQid, exploredLabel};
        }
        return null;
    }

    /** Render {@code rels} as cards through objectview's SearchableView — search, sort,
     *  filter (incl. by Kind) come from the shared card view. Reusable selection accepts
     *  the whole selected set; relation-specific actions require exactly one. */
    private void showRelations(List<RelationView> rels) {
        relations = rels == null ? List.of() : rels;
        selectedRelationViews = List.of();
        selectedRelationView = null;
        relationHolder.removeAll();
        if (relations.isEmpty()) {
            relationHolder.add(new JLabel("   No relations."), BorderLayout.NORTH);
        } else {
            // Table-style rendering (trialling the new objectview table view): one relation
            // per row, one field per column, same search/sort/selection as the card view.
            JComponent table = objectview.view.SearchableView.builder(relations)
                    .mode(objectview.render.RenderingMode.TABLE)
                    .sample(relations.get(0))
                    .valueLinker(WikidataLinks.valueLinker())
                    .selectionSetListener(selected -> {
                        selectedRelationViews = selected.stream()
                                .filter(RelationView.class::isInstance)
                                .map(RelationView.class::cast).toList();
                        selectedRelationView = selectedRelationViews.size() == 1
                                ? selectedRelationViews.getFirst() : null;
                        updateButtons();
                    })
                    .build();
            relationHolder.add(table, BorderLayout.CENTER);
        }
        relationHolder.revalidate();
        relationHolder.repaint();
        updateButtons();
    }

    private void updateButtons() {
        boolean haveCandidate = candidates.hasSelection();
        boolean haveOneCandidate = candidates.selectionCount() == 1;
        boolean haveProbe = selectedRelationView != null;
        // A pre-loaded seed (presentSeed) enables Explore even without a search candidate.
        exploreButton.setEnabled(
                (haveOneCandidate || WikidataIds.isQid(pendingQid)) && queryRunner != null);
        useSourceButton.setEnabled(haveOneCandidate
                || !haveCandidate && WikidataIds.isQid(exploredQid));
        showMembersButton.setEnabled(haveProbe);
        addTargetsButton.setEnabled(haveProbe);
        addSeedsButton.setEnabled(haveProbe);
        usePropertyButton.setEnabled(haveProbe);
    }

    private boolean canSetSelectedEntity() {
        return workbenchSelections != null
                && (candidates.hasSelection() || WikidataIds.isQid(exploredQid));
    }

    private void setSelectedEntity() {
        if (workbenchSelections == null) return;
        List<List<Object>> rows = candidates.selectedRows();
        int added = 0;
        for (List<Object> row : rows) {
            String qid = row.isEmpty() || row.get(0) == null ? "" : row.get(0).toString();
            String label = row.size() < 2 || row.get(1) == null ? qid : row.get(1).toString();
            String description = row.size() < 3 || row.get(2) == null
                    ? "" : row.get(2).toString();
            if (WikidataIds.isQid(qid)) {
                workbenchSelections.entity(qid, label, description);
                added++;
            }
        }
        if (added == 0 && WikidataIds.isQid(exploredQid)) {
            workbenchSelections.entity(exploredQid, exploredLabel);
            added = 1;
        }
        if (added > 0) status.setText("Added " + added
                + " selected entity value(s) to reusable selections.");
    }

    private void setSelectedProperty() {
        if (workbenchSelections == null) return;
        for (RelationView relation : selectedRelationViews) {
            workbenchSelections.property(relation.pid(), relation.relationLabel());
        }
        if (!selectedRelationViews.isEmpty()) status.setText("Added "
                + selectedRelationViews.size()
                + " selected property value(s) to reusable selections.");
    }

    private ClassSearchQuery buildSearchQuery() {
        String text = searchField.getText() == null ? "" : searchField.getText().trim();
        if (text.isBlank()) {
            return null;
        }
        status.setText("Searching…");
        candidates.setRows(List.of());
        showRelations(List.of());
        return new ClassSearchQuery(text, ClassSearchQuery.Mode.API, 20);
    }

    private void acceptSearch(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        SwingUtilities.invokeLater(() -> {
            exploredQid = "";
            exploredLabel = "";
            currentLabel.setText(" ");
            WikidataLinks.setHtml(relationSubject, "");
            candidates.setRows(rows);
            status.setText(rows.size() + " candidate(s) — pick one, then Explore.");
            updateButtons();
        });
    }

    /** Explore an entity's relations from outside (e.g. a model-graph node click):
     *  a fresh exploration that resets the navigation history. */
    public void exploreQid(String qid, String label) {
        if (qid == null || !WikidataIds.isQid(qid)) {
            return;
        }
        history.clear();
        exploreEntity(qid, label, false);
    }

    /** Pre-load a seed entity for property exploration <em>without</em> firing the probe:
     *  the user reviews it and clicks Explore to run. Keeps Explore passive about its input
     *  (the counterpart to returning — rather than acting on — its result), and is the seam
     *  where a caller-supplied or user-added qid is confirmed before the query goes out. */
    public void presentSeed(String qid, String label) {
        if (qid == null || !WikidataIds.isQid(qid)) {
            return;
        }
        history.clear();
        candidates.setRows(List.of());
        exploredQid = "";
        exploredLabel = "";
        pendingQid = qid;
        pendingLabel = label == null || label.isBlank() ? qid : label;
        qidField.setText(qid);
        currentLabel.setText("Ready to explore: " + pendingLabel + " (" + qid + ")");
        WikidataLinks.setHtml(relationSubject, "");
        showRelations(List.of());
        backButton.setEnabled(false);
        status.setText("Click Explore to load properties of " + pendingLabel + ".");
        selectTab(entityPanel);   // the Explore button lives on the Entity tab
        updateButtons();
    }

    // Explore an arbitrary entity (following a relation's member), pushing the
    // current one onto the Back history. Triggers the same wired explore action.
    private void exploreEntity(String qid, String label, boolean pushHistory) {
        if (qid == null || !WikidataIds.isQid(qid)) {
            return;
        }
        if (pushHistory && WikidataIds.isQid(exploredQid)) {
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
        if (WikidataIds.isQid(pendingQid)) {
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
        if (!WikidataIds.isQid(qid)) {
            return null;
        }
        exploredQid = qid;
        exploredLabel = label == null || label.isBlank() ? qid : label;
        currentLabel.setText("Relations of: " + exploredLabel + " (" + qid + ")");
        WikidataLinks.setHtml(relationSubject,
                WikidataLinks.html("Relations of: " + exploredLabel + " (" + qid + ")"));
        backButton.setEnabled(!history.isEmpty());
        status.setText("Exploring relations of " + exploredLabel + "…");
        showRelations(List.of());
        return new ExploreEntityQuery(qid, 200, outgoingOnly);
    }

    // Rows are (Direction, PID, Relation, Count, Example, Kind) — one per relation.
    private void acceptProbes(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        List<RelationView> found = new ArrayList<>();
        for (List<Object> row : rows) {
            String dir = str(row, 0);
            String pid = str(row, 1);
            if (!WikidataIds.isPid(pid)) {
                continue;
            }
            found.add(new RelationView(
                    dir, pid, str(row, 2), num(str(row, 3)), str(row, 4), str(row, 5),
                    str(row, 6)));
        }
        SwingUtilities.invokeLater(() -> {
            showRelations(found);
            if (!found.isEmpty()) {
                selectTab(relationPanel);   // jump to the results
            }
            status.setText(found.isEmpty()
                    ? "No relations found."
                    : found.size() + " relation(s) — pick one to "
                            + (explorationOnly ? "inspect its members."
                                    : "add its members as seeds."));
        });
    }

    private RelationMembersQuery buildMembersQuery() {
        RelationView rel = selectedRelationView;
        if (rel == null || !WikidataIds.isQid(exploredQid)) {
            return null;
        }
        status.setText("Fetching members of " + rel.pid() + "…");
        return new RelationMembersQuery(exploredQid, rel.pid(), rel.incoming(), 5000);
    }

    private static List<String> qidsOf(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        List<String> qids = new ArrayList<>();
        for (List<Object> row : rows) {
            String qid = str(row, 0);
            if (WikidataIds.isQid(qid) && !qids.contains(qid)) {
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

}
