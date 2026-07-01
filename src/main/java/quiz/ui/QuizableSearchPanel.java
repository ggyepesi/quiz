package quiz.ui;

import aux.GridBagUtils;
import quiz.*;
import quiz.ui.viewconfig.QuizablePanelConfig;
import quiz.ui.viewconfig.QuizablePanelConfigEditor;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.*;
import java.util.List;

public class QuizableSearchPanel extends JPanel
        implements QuizablePanelTargetListener {
    public static final String FIELD_PATH_PROPERTY = "quiz.fieldPath";
    public static final String FIELD_NAME_PROPERTY = "quiz.fieldName";
    public static final String FIELD_VALUE_PROPERTY = "quiz.fieldValue";

    private static final String OLD_BORDER_PROPERTY =
            "quiz.search.oldBorder";
    private static final String OLD_BACKGROUND_PROPERTY =
            "quiz.search.oldBackground";
    private static final String OLD_OPAQUE_PROPERTY =
            "quiz.search.oldOpaque";
    private static final String OLD_FOREGROUND_PROPERTY =
            "quiz.search.oldForeground";
    private static final String OLD_LABEL_TEXT_PROPERTY =
            "quiz.search.oldLabelText";
    private static final String HIDDEN_HIT_BADGE_PROPERTY =
            "quiz.search.hiddenHitBadge";

    private static final Color CARD_HIT_BACKGROUND =
            new Color(255, 248, 200);
    // The actual hit (field / text) is red-ish so it stands out clearly from
    // the card's pale-yellow tint.
    private static final Color FIELD_HIT_BACKGROUND =
            new Color(255, 188, 170);
    private static final Color TEXT_HIGHLIGHT_BACKGROUND =
            new Color(255, 150, 130);
    private static final Color HIDDEN_HIT_BADGE_COLOR =
            new Color(120, 80, 0);

    private final QuizablePanelConfigEditor searchEditor;
    private final QuizablePanelConfigEditor sortEditor;
    private final QuizablePanelConfigEditor viewEditor;

    private final JTextField searchField =
            new JTextField();

    private final JCheckBox fieldHighlightBox =
            new JCheckBox("Highlight Fields", false);

    // Returns to the spot before the last "jump to its card" navigation.
    private final JButton backButton = new JButton("← Back");
    private QuizableRenderContext renderContext;

    private final JPanel resultsPanel =
            new JPanel();

    private final List<Quizable> originalQuizables =
            new ArrayList<>();

    private final List<Component> originalTargetOrder =
            new ArrayList<>();

    private final QuizablePanelSearchAndSort searchAndSort =
            new QuizablePanelSearchAndSort();

    private final Set<JComponent> rememberedSearchComponents =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private final Set<QuizablePanel> previousMatchedCards =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private final javax.swing.Timer debounceTimer;

    private JComponent targetPanel;
    private VirtualizedCardList virtualList;   // non-null when the target is virtualized
    private JScrollPane targetScrollPane;
    private JDialog searchDialog;
    private JDialog sortDialog;
    private JDialog viewDialog;
    private JComponent currentHit;

    // Coordinated mode: this panel is one section's search ENGINE driven by a
    // shared MultiSearchBar (shared input + config), while it keeps its own
    // per-field results panel + per-panel navigation + highlighting.
    private boolean suppressSearchEvents = false;

    // The class this section searches (for the shared config's per-class tab).
    private Class<? extends Quizable> searchClass;

    // The top toolbar (search input + config buttons); hidden in coordinated
    // mode, where the shared MultiSearchBar owns the input + config and each
    // section keeps only its own per-field results/navigation.
    private JComponent topControls;

    private int cachedColumnCount = 1;

    // True once the user has applied a sort and not yet restored order, so
    // live-added cards can be slotted into the sorted order rather than
    // appended at the end.
    private boolean sorted = false;

    public void setTarget(
            JComponent targetPanel,
            JScrollPane targetScrollPane) {

        setTarget(targetPanel, targetScrollPane, true);
    }

    /** Wires the Back button to the view's navigation context, so jumping to a
     *  referenced card (a top-level reference link) can be undone. */
    public void setRenderContext(QuizableRenderContext context) {
        this.renderContext = context;
        if (context == null) {
            backButton.setEnabled(false);
            return;
        }
        context.setNavChangeListener(() -> backButton.setEnabled(context.canGoBack()));
        backButton.setEnabled(context.canGoBack());
    }

    public void setTargetAndApplyViewConfig(
            JComponent targetPanel,
            JScrollPane targetScrollPane) {

        setTarget(targetPanel, targetScrollPane, true);
    }

    private void setTarget(
            JComponent targetPanel,
            JScrollPane targetScrollPane,
            boolean applyViewConfig) {

        this.targetPanel = targetPanel;
        this.virtualList = targetPanel instanceof VirtualizedCardList vcl ? vcl : null;
        this.targetScrollPane = targetScrollPane;

        cachedColumnCount = detectColumnCount();

        rememberOriginalTargetsFromCurrentPanel();
        rebuildSearchIndex();
        clearResults();

        if (applyViewConfig) {
            // applyViewConfig(false);
        }
    }

    /**
     * Re-syncs after the controlled view added cards live: refreshes the
     * column count, extends the original-order baseline with the new cards
     * (appending rather than re-snapshotting, which would otherwise capture
     * a sorted order as the baseline and break Restore Order), rebuilds the
     * search index, re-applies the active sort so new cards land in sorted
     * position, and re-applies the active query so they get highlighted.
     */
    @Override
    public void quizablePanelsAdded(List<QuizablePanel> added) {
        if (targetPanel == null) {
            return;
        }

        cachedColumnCount = detectColumnCount();

        for (QuizablePanel qp : added) {
            if (qp == null) {
                continue;
            }
            originalQuizables.add(qp.getQuizable());
            originalTargetOrder.add(qp);
        }

        rebuildSearchIndex();

        if (sorted) {
            sortTargetPanels();
        }

        maybeRefreshSearch();
    }

    /**
     * Re-syncs after the controlled view re-rendered cards in place (their
     * backing quizables changed). The card instances are unchanged, but
     * their child components — and possibly a sort-key field's value — are
     * new, so the search index is rebuilt, an active sort is re-applied
     * (the changed value may move the card), and the active query is
     * re-run so highlights match the new content.
     */
    @Override
    public void quizablePanelsUpdated(List<QuizablePanel> updated) {
        if (targetPanel == null) {
            return;
        }

        rebuildSearchIndex();

        if (sorted) {
            sortTargetPanels();
        }

        maybeRefreshSearch();
    }

    private void sortTargetPanels() {
        if (targetPanel == null) {
            return;
        }

        List<QuizableFieldPaths.FieldPath> sortPaths =
                QuizableFieldPaths.collect(
                        sortEditor.getConfig(),
                        QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        if (sortPaths.isEmpty()) {
            return;
        }

        // Virtualized: sort the DATA (all fields of every quizable), then re-render
        // the visible window in the new order — O(N) on data + O(visible) render,
        // no component shuffle.
        if (virtualList != null) {
            // Keep your place across the resort: anchor on the highlighted search
            // hit if any, else the card currently at the top of the viewport, and
            // re-pin it at the top in the new order.
            Quizable anchor = currentHit instanceof QuizablePanel qp
                    ? qp.getQuizable()
                    : virtualList.topVisibleItem();

            List<Quizable> ordered =
                    searchAndSort.sortQuizables(virtualList.items(), sortPaths);
            virtualList.setItems(ordered);
            if (anchor != null) {
                virtualList.navigateToTop(anchor);
            }
            sorted = true;
            return;
        }

        List<QuizablePanel> panels =
                new ArrayList<>();

        for (Component c : targetPanel.getComponents()) {
            if (c instanceof QuizablePanel qp) {
                panels.add(qp);
            }
        }

        panels = searchAndSort.sortPanels(panels, sortPaths);
        applyTargetOrder(panels);
        sorted = true;

        // Don't call maybeRefreshSearch() — highlights are still valid
        // because no panels were recreated, only repositioned.
    }

    // CardStackLayout lays out in component order, so reordering for sort is just
    // re-adding the cards in the new order (cheap — the layout is O(n) arithmetic).
    private void applyTargetOrder(List<? extends Component> order) {
        if (targetPanel == null) {
            return;
        }

        List<Component> filtered = new ArrayList<>();
        for (Component c : order) {
            if (c instanceof QuizablePanel) {
                filtered.add(c);
            }
        }

        // Skip if the order hasn't changed (avoid a needless removeAll/relayout).
        Component[] current = targetPanel.getComponents();
        if (current.length == filtered.size()) {
            boolean same = true;
            for (int i = 0; i < current.length; i++) {
                if (current[i] != filtered.get(i)) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return;
            }
        }

        targetPanel.removeAll();
        for (Component c : filtered) {
            targetPanel.add(c);
        }

        targetPanel.revalidate();
        targetPanel.repaint();
    }


    public QuizableSearchPanel(Class<? extends Quizable> cls) {
        this.searchClass = cls;
        setLayout(new BorderLayout(6, 6));

        QuizablePanelConfig nameOnly =
                nameOnlyConfig(cls);

        QuizablePanelConfig viewBase =
                QuizablePanelConfig.all(cls);

        searchEditor =
                new QuizablePanelConfigEditor(nameOnly.copy(), true);

        sortEditor =
                new QuizablePanelConfigEditor(nameOnly.copy(), true);

        viewEditor =
                new QuizablePanelConfigEditor(viewBase.copy(), false);

        debounceTimer =
                new javax.swing.Timer(
                        150,
                        e -> searchSync(searchField.getText()));

        debounceTimer.setRepeats(false);

        searchField.setBorder(
                BorderFactory.createTitledBorder("Search"));
        searchField.setColumns(32);
        searchField.setPreferredSize(new Dimension(420, searchField.getPreferredSize().height));

        searchField.getDocument().addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        asyncSearch();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        asyncSearch();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        asyncSearch();
                    }
                });

        JButton viewConfigButton = new JButton("View Config...");
        JButton sortConfigButton = new JButton("Sort Config...");
        JButton sortButton = new JButton("Sort");
        JButton restoreOrderButton = new JButton("Restore Order");
        JButton searchConfigButton = new JButton("Search Config...");

        backButton.setEnabled(false);
        backButton.setToolTipText("Back to where you jumped from");
        backButton.addActionListener(e -> {
            if (renderContext != null) {
                renderContext.back();
            }
        });

        JPanel top =
                new CompactQuizableSearchTopPanel(
                        backButton,
                        searchField,
                        searchConfigButton,
                        sortConfigButton,
                        sortButton,
                        restoreOrderButton,
                        viewConfigButton,
                        fieldHighlightBox);

        // In a narrow panel (e.g. the "Generated instances" split pane) the
        // toolbar's second row of buttons would otherwise be clipped and
        // unreachable. A horizontal-only scroll pane keeps every control
        // reachable while never stealing vertical room from the results.
        topControls = aux.ScrollPaneUtils.horizontalOnly(top);
        add(topControls, BorderLayout.NORTH);

        resultsPanel.setLayout(
                new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));

        JScrollPane resultsScroll =
                new JScrollPane(resultsPanel);
        resultsScroll.setPreferredSize(new Dimension(320, 160));
        resultsScroll.getVerticalScrollBar().setUnitIncrement(16);

        add(resultsScroll, BorderLayout.CENTER);

        searchConfigButton.addActionListener(e -> openSearchDialog());
        sortConfigButton.addActionListener(e -> openSortDialog());
        viewConfigButton.addActionListener(e -> openViewDialog());
        sortButton.addActionListener(e -> sortTargetPanels());
        restoreOrderButton.addActionListener(
                e -> restoreOriginalTargetOrder());

        fieldHighlightBox.addActionListener(e -> refreshSearch());
    }

    private QuizablePanelConfig nameOnlyConfig(
            Class<? extends Quizable> cls) {

        QuizablePanelConfig cfg =
                QuizablePanelConfig.of(cls);

        cfg.setAllFields(false);
        cfg.setAddListener(false);
        cfg.setThumb(false);
        cfg.addField("name", QuizablePanelConfig.leaf());

        return cfg;
    }

    private void rememberOriginalTargetsFromCurrentPanel() {
        originalQuizables.clear();
        originalTargetOrder.clear();

        // Virtualized: the baseline is the full data list (most cards aren't built).
        if (virtualList != null) {
            originalQuizables.addAll(virtualList.items());
            return;
        }

        if (targetPanel == null) {
            return;
        }

        for (Component c : targetPanel.getComponents()) {
            if (c instanceof QuizablePanel qp) {
                originalQuizables.add(qp.getQuizable());
                originalTargetOrder.add(qp);
            }
        }
    }

    public QuizablePanelConfig getSearchConfig() {
        return searchEditor.getConfig();
    }

    public QuizablePanelConfig getSortConfig() {
        return sortEditor.getConfig();
    }

    public QuizablePanelConfig getViewConfig() {
        return viewEditor.getConfig();
    }

    private void openSearchDialog() {
        if (searchDialog == null) {
            searchDialog =
                    createDialog(
                            "Search Configuration",
                            searchEditor,
                            this::refreshSearch);
        }

        searchDialog.setVisible(true);
    }

    private void openSortDialog() {
        if (sortDialog == null) {
            sortDialog =
                    createDialog(
                            "Sort Configuration",
                            sortEditor,
                            this::sortTargetPanels);
        }

        sortDialog.setVisible(true);
    }

    private void openViewDialog() {
        if (viewDialog == null) {
            viewDialog =
                    createDialog(
                            "View Configuration",
                            viewEditor,
                            this::applyViewConfig);
        }

        viewDialog.setVisible(true);
    }

    private JDialog createDialog(
            String title,
            JComponent content,
            Runnable onApply) {

        JDialog dialog =
                new JDialog(
                        SwingUtilities.getWindowAncestor(this),
                        title,
                        Dialog.ModalityType.MODELESS);

        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(content, BorderLayout.CENTER);

        JButton apply =
                new JButton("Apply");

        apply.addActionListener(e -> {
            onApply.run();
            dialog.setVisible(false);
        });

        JPanel buttonPanel =
                new JPanel(new FlowLayout(FlowLayout.RIGHT));

        buttonPanel.add(apply);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setSize(800, 600);
        dialog.setLocationRelativeTo(this);

        return dialog;
    }

    private void asyncSearch() {
        if (suppressSearchEvents) {
            return;
        }
        debounceTimer.restart();
    }

    // ---- Coordinated (multi-section) search API ----------------------------

    /** Driven by a shared {@link MultiSearchBar}: hides this engine's own input
     *  + config toolbar (the bar owns those), while it keeps its own per-field
     *  results panel and per-panel navigation. */
    public void setCoordinated(boolean coordinated) {
        if (topControls != null) {
            topControls.setVisible(!coordinated);
        }
    }

    /** Runs the query against this section synchronously (highlighting its
     *  cards) without stealing navigation. Keeps the (hidden) field in sync so
     *  live-add re-search uses the right text. */
    public void runCoordinatedSearch(String query) {
        suppressSearchEvents = true;
        try {
            searchField.setText(query == null ? "" : query);
        } finally {
            suppressSearchEvents = false;
        }
        clearHighlights();
        rebuildSearchIndex();
        searchSync(query == null ? "" : query);
    }

    /** The class this section searches (for the shared config's per-class tab). */
    public String sectionTypeName() {
        return searchClass == null ? "" : searchClass.getSimpleName();
    }

    // Editors + apply hooks, so a single shared dialog (classes as roots) can
    // host every section's search/sort/view config and re-run on apply.
    public QuizablePanelConfigEditor searchEditor() { return searchEditor; }
    public QuizablePanelConfigEditor sortEditor() { return sortEditor; }
    public QuizablePanelConfigEditor viewEditor() { return viewEditor; }

    public void applySort() { sortTargetPanels(); }
    public void applyView() { applyViewConfig(); }

    /** Sets the field-highlight option from the shared bar and re-runs. */
    public void setFieldHighlight(boolean on) {
        if (fieldHighlightBox.isSelected() != on) {
            fieldHighlightBox.setSelected(on);
        }
    }

    private void refreshSearch() {
        clearHighlights();
        rebuildSearchIndex();
        searchSync(searchField.getText());
    }

    private void rebuildSearchIndex() {
        searchAndSort.rebuildSearchIndex(
                targetPanel,
                getSearchConfig());
    }

    private void searchSync(String query) {
        if (targetPanel == null) {
            return;
        }

        String text =
                normalize(query == null ? "" : query);

        clearHighlights();
        clearResults();

        if (text.isEmpty()) {
            return;
        }

        List<String> queryTokens =
                tokens(text);

        if (queryTokens.isEmpty()) {
            return;
        }

        // Virtualized view: only the visible cards exist as components, so search
        // the DATA and navigate hits one at a time (building each card on demand).
        if (virtualList != null) {
            searchSyncVirtual(queryTokens);
            return;
        }

        Map<String, List<QuizablePanel>> matchesByField =
                searchAndSort.search(queryTokens);

        Map<String, HitGroup> groups =
                new LinkedHashMap<>();

        for (Map.Entry<String, List<QuizablePanel>> e
                : matchesByField.entrySet()) {

            HitGroup group =
                    new HitGroup(e.getKey());

            for (QuizablePanel qp : e.getValue()) {
                if (!group.hits.contains(qp)) {
                    group.hits.add(qp);
                }

                highlightCard(qp);
            }

            groups.put(e.getKey(), group);
        }

        if (fieldHighlightBox.isSelected()) {
            addFieldHighlights(
                    matchesByField,
                    groups,
                    queryTokens);
        }

        targetPanel.revalidate();
        targetPanel.repaint();

        showSearchResults(groups);
    }

    private void addFieldHighlights(
            Map<String, List<QuizablePanel>> matchesByField,
            Map<String, HitGroup> groups,
            List<String> queryTokens) {

        List<QuizableFieldPaths.FieldPath> paths =
                QuizableFieldPaths.collect(
                        getSearchConfig(),
                        QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        Map<String, QuizableFieldPaths.FieldPath> pathByTitle =
                new LinkedHashMap<>();

        for (QuizableFieldPaths.FieldPath fp : paths) {
            pathByTitle.put(fp.title(), fp);
        }

        // Pre-pass: a match can sit inside a collapsed collection (e.g. a
        // Character's collapsed "episodes", or an Episode's "characters"). Expand
        // the collapsed collections on each matching card's matching path so the
        // rows render, then refresh those cards once — otherwise the hit can't be
        // highlighted or scrolled to.
        Set<QuizablePanel> toRefresh =
                Collections.newSetFromMap(new IdentityHashMap<>());

        for (Map.Entry<String, List<QuizablePanel>> e
                : matchesByField.entrySet()) {
            QuizableFieldPaths.FieldPath fp = pathByTitle.get(e.getKey());
            if (fp == null) {
                continue;
            }
            for (QuizablePanel qp : e.getValue()) {
                if (qp.expandCollectionsOnPath(fp.path())) {
                    toRefresh.add(qp);
                }
            }
        }
        for (QuizablePanel qp : toRefresh) {
            qp.refresh();
        }

        for (Map.Entry<String, List<QuizablePanel>> e
                : matchesByField.entrySet()) {

            QuizableFieldPaths.FieldPath fp =
                    pathByTitle.get(e.getKey());

            if (fp == null) {
                continue;
            }

            HitGroup group =
                    groups.get(e.getKey());

            if (group == null) {
                continue;
            }

            for (QuizablePanel qp : e.getValue()) {
                List<JComponent> fieldHits =
                        collectMatchingFieldRows(
                                qp,
                                fp.path(),
                                queryTokens);

                // The precise field rows ARE the hits — drop the coarse card
                // placeholder so a card with rows isn't counted (and navigated)
                // twice. If no rows were found (e.g. a text-only match), the card
                // stays as the hit.
                if (!fieldHits.isEmpty()) {
                    group.hits.remove(qp);
                }

                for (JComponent hit : fieldHits) {
                    if (!group.hits.contains(hit)) {
                        group.hits.add(hit);
                    }

                    highlightField(hit);
                }

                highlightTextRecursively(
                        qp,
                        fp.path(),
                        queryTokens);
            }
        }
    }

    private void highlightCard(QuizablePanel qp) {
        remember(qp);
        previousMatchedCards.add(qp);

        qp.setHighlightColor(CARD_HIT_BACKGROUND);
    }

    private void highlightField(JComponent c) {
        remember(c);
        c.setOpaque(true);
        c.setBackground(FIELD_HIT_BACKGROUND);
        c.repaint();
    }

    private void addHiddenHitBadge(
            QuizablePanel panel,
            String fieldTitle) {

        Object existing =
                panel.getClientProperty(HIDDEN_HIT_BADGE_PROPERTY);

        if (existing instanceof JLabel label) {
            label.setText(label.getText() + ", " + fieldTitle);
            return;
        }

        remember(panel);

        JLabel badge =
                new JLabel("hidden hit: " + fieldTitle);

        badge.setForeground(HIDDEN_HIT_BADGE_COLOR);
        badge.setFont(badge.getFont().deriveFont(Font.ITALIC));

        panel.putClientProperty(HIDDEN_HIT_BADGE_PROPERTY, badge);

        panel.add(
                badge,
                GridBagUtils.gbc(
                        0,
                        panel.getComponentCount(),
                        1.0,
                        0.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(2, 8, 2, 2)));

        panel.revalidate();
        panel.repaint();
    }

    private List<JComponent> collectMatchingFieldRows(
            Component root,
            List<String> selectedPath,
            List<String> queryTokens) {

        List<JComponent> hits =
                new ArrayList<>();

        collectMatchingFieldRows(
                root,
                selectedPath,
                queryTokens,
                hits);

        return hits;
    }

    private void collectMatchingFieldRows(
            Component root,
            List<String> selectedPath,
            List<String> queryTokens,
            List<JComponent> hits) {

        if (root instanceof QuizableTextBlock block) {
            if (block.hasMatchingRow(selectedPath, queryTokens)) {
                replaceAncestorWithDescendantIfNeeded(block, hits);
            }

            return;
        }

        if (root instanceof JComponent jc) {
            Object pathObj =
                    jc.getClientProperty(FIELD_PATH_PROPERTY);

            Object val =
                    jc.getClientProperty(FIELD_VALUE_PROPERTY);

            if (pathObj instanceof List<?> rowPath
                    && samePath(rowPath, selectedPath)
                    && val != null
                    && matchesWithTokens(val, queryTokens)) {

                replaceAncestorWithDescendantIfNeeded(jc, hits);
            }
        }

        if (root instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                collectMatchingFieldRows(
                        child,
                        selectedPath,
                        queryTokens,
                        hits);
            }
        }
    }

    private void replaceAncestorWithDescendantIfNeeded(
            JComponent candidate,
            List<JComponent> hits) {

        for (Iterator<JComponent> it = hits.iterator(); it.hasNext();) {
            JComponent existing =
                    it.next();

            if (SwingUtilities.isDescendingFrom(candidate, existing)) {
                it.remove();
                break;
            }

            if (SwingUtilities.isDescendingFrom(existing, candidate)) {
                return;
            }
        }

        hits.add(candidate);
    }

    private void showSearchResults(Map<String, HitGroup> groups) {
        resultsPanel.removeAll();

        JComponent first =
                null;

        for (HitGroup g : groups.values()) {
            if (g.hits.isEmpty()) {
                continue;
            }

            addHitGroupRow(g);

            if (first == null) {
                first = g.hits.get(0);
                g.index = 0;
                g.updateLabel();
            }
        }

        resultsPanel.revalidate();
        resultsPanel.repaint();

        if (first != null) {
            markCurrentHit(first);
            scrollTo(first);
        }
    }

    private void applyViewConfig() {
        applyViewConfig(true);
    }

    private void applyViewConfig(boolean searchAfter) {
        if (targetPanel == null) {
            return;
        }

        QuizablePanelConfig cfg =
                viewEditor.getConfig();

        List<QuizablePanel> panels =
                new ArrayList<>();

        List<Quizable> quizables =
                originalQuizables.isEmpty()
                        ? collectQuizablesFromCurrentTarget()
                        : new ArrayList<>(originalQuizables);

        QuizableRenderContext context =
                new QuizableRenderContext(quizables);

        for (Quizable q : quizables) {
            if (q == null) {
                continue;
            }

            QuizablePanelConfig viewCfg =
                    cfg.copy();

            if (viewCfg.getCls() == null) {
                viewCfg.setCls(q.getClass());
            }

            context.putClassConfig(q.getClass(), viewCfg);

            QuizablePanel panel =
                    new QuizablePanel(q, viewCfg, context, false);

            context.registerTopLevel(q, panel);

            if (panel.hasRenderedConfiguredContent()) {
                panels.add(panel);
            }
        }

        originalQuizables.clear();
        originalQuizables.addAll(quizables);

        originalTargetOrder.clear();
        originalTargetOrder.addAll(panels);

        applyTargetOrder(panels);
        rebuildSearchIndex();

        // EDGE CASE: this recreates the cards in original order, visually
        // un-sorting, but deliberately leaves the `sorted` flag untouched.
        // Consequence: if a sort was active, the next live add
        // (quizablePanelsAdded) will re-impose it. That re-sort is the
        // intended behavior; if you ever want View Config to clear the
        // sort instead, set `sorted = false` here — but then a live add
        // after a view change won't restore the user's sort. Decide
        // explicitly rather than letting it drift.
        if (searchAfter) {
            maybeRefreshSearch();
        }
    }

    private List<Quizable> collectQuizablesFromCurrentTarget() {
        List<Quizable> out =
                new ArrayList<>();

        if (targetPanel == null) {
            return out;
        }

        for (Component c : targetPanel.getComponents()) {
            if (c instanceof QuizablePanel qp) {
                out.add(qp.getQuizable());
            }
        }

        return out;
    }

    private void restoreOriginalTargetOrder() {
        sorted = false;

        // Virtualized: restore the original data order (a data swap, not a
        // component shuffle).
        if (virtualList != null) {
            virtualList.setItems(originalQuizables);
            maybeRefreshSearch();
            return;
        }

        applyTargetOrder(originalTargetOrder);
        maybeRefreshSearch();
    }

    private void maybeRefreshSearch() {
        if (!searchField.getText().isBlank()) {
            asyncSearch();
        }
    }

    private int detectColumnCount() {
        return targetPanel != null
                && targetPanel.getLayout() instanceof CardStackLayout csl
                ? csl.columns()
                : 1;
    }

    private boolean matchesWithTokens(
            Object value,
            List<String> tokens) {

        if (value == null) {
            return false;
        }

        if (value instanceof Collection<?> c) {
            for (Object item : c) {
                if (matchesWithTokens(item, tokens)) {
                    return true;
                }
            }

            return false;
        }

        if (value instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                if (matchesWithTokens(item, tokens)) {
                    return true;
                }
            }

            return false;
        }

        String s =
                normalize(value instanceof Quizable q
                        ? q.getName()
                        : value.toString());

        for (String tok : tokens) {
            if (!s.contains(tok)) {
                return false;
            }
        }

        return true;
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }

    private List<String> tokens(String t) {
        if (t == null) {
            return List.of();
        }

        String[] arr =
                t.toLowerCase().trim().split("\\s+");

        List<String> out =
                new ArrayList<>();

        for (String p : arr) {
            if (!p.isBlank()) {
                out.add(p);
            }
        }

        return out;
    }

    private void highlightTextRecursively(
            Component root,
            List<String> selectedPath,
            List<String> queryTokens) {

        if (root instanceof QuizableTextBlock block) {
            if (block.hasMatchingRow(selectedPath, queryTokens)) {
                remember(block);
                block.setHighlightTokens(selectedPath, queryTokens);
            }

            return;
        }

        if (root instanceof JComponent jc) {
            Object pathObj =
                    jc.getClientProperty(FIELD_PATH_PROPERTY);

            boolean isSelectedField =
                    pathObj instanceof List<?> rowPath
                            && samePath(rowPath, selectedPath);

            if (isSelectedField) {
                highlightLabelsUnder(jc, queryTokens);
                return;
            }
        }

        if (root instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                highlightTextRecursively(
                        child,
                        selectedPath,
                        queryTokens);
            }
        }
    }

    private void highlightLabelsUnder(
            Component root,
            List<String> queryTokens) {

        if (root instanceof QuizableTextRow row) {
            remember(row);
            row.setHighlightTokens(queryTokens);
            return;
        }

        if (root instanceof JLabel lbl) {
            highlightLabelText(lbl, queryTokens);
        }

        if (root instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                highlightLabelsUnder(child, queryTokens);
            }
        }
    }

    private boolean samePath(
            List<?> a,
            List<String> b) {

        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }

        for (int i = 0; i < a.size(); i++) {
            if (!Objects.equals(String.valueOf(a.get(i)), b.get(i))) {
                return false;
            }
        }

        return true;
    }

    private void highlightLabelText(
            JLabel lbl,
            List<String> queryTokens) {

        String restoreText =
                (String) lbl.getClientProperty(OLD_LABEL_TEXT_PROPERTY);

        if (restoreText == null) {
            restoreText = lbl.getText();
            lbl.putClientProperty(OLD_LABEL_TEXT_PROPERTY, restoreText);
        }

        String plain =
                null;

        Object value =
                lbl.getClientProperty(FIELD_VALUE_PROPERTY);

        if (value instanceof String s) {
            plain = s;
        }

        if (plain == null) {
            plain = stripHtml(lbl.getText());
        }

        if (plain == null || plain.isBlank()) {
            return;
        }

        String highlighted =
                highlightTokens(plain, queryTokens);

        if (!highlighted.equals(escapeHtml(plain))) {
            remember(lbl);
            lbl.setText("<html><span>"
                                + highlighted
                                + "</span></html>");
        }
    }

    private String stripHtml(String s) {
        if (s == null) {
            return "";
        }

        if (!s.trim().toLowerCase().startsWith("<html")) {
            return s;
        }

        StringBuilder sb =
                new StringBuilder(s.length());

        boolean inTag =
                false;

        for (int i = 0; i < s.length(); i++) {
            char ch =
                    s.charAt(i);

            if (ch == '<') {
                inTag = true;
            } else if (ch == '>') {
                inTag = false;
            } else if (!inTag) {
                sb.append(ch);
            }
        }

        return sb.toString()
                 .replace("&lt;", "<")
                 .replace("&gt;", ">")
                 .replace("&amp;", "&")
                 .replace("&quot;", "\"")
                 .trim();
    }

    private String highlightTokens(
            String text,
            List<String> toks) {

        if (toks.isEmpty() || text.isEmpty()) {
            return escapeHtml(text);
        }

        String lower =
                text.toLowerCase();

        boolean[] mark =
                new boolean[text.length()];

        for (String tok : toks) {
            int idx =
                    0;

            while ((idx = lower.indexOf(tok, idx)) >= 0) {
                for (int i = idx;
                     i < idx + tok.length() && i < mark.length;
                     i++) {

                    mark[i] = true;
                }

                idx += Math.max(1, tok.length());
            }
        }

        StringBuilder sb =
                new StringBuilder();

        String color =
                String.format(
                        "rgb(%d,%d,%d)",
                        TEXT_HIGHLIGHT_BACKGROUND.getRed(),
                        TEXT_HIGHLIGHT_BACKGROUND.getGreen(),
                        TEXT_HIGHLIGHT_BACKGROUND.getBlue());

        boolean open =
                false;

        for (int i = 0; i < text.length(); i++) {
            if (mark[i] && !open) {
                sb.append("<span style='background-color:")
                  .append(color)
                  .append(";'>");

                open = true;
            } else if (!mark[i] && open) {
                sb.append("</span>");
                open = false;
            }

            sb.append(escapeChar(text.charAt(i)));
        }

        if (open) {
            sb.append("</span>");
        }

        return sb.toString();
    }

    private String escapeHtml(String s) {
        if (s == null) {
            return "";
        }

        StringBuilder sb =
                new StringBuilder();

        for (char c : s.toCharArray()) {
            sb.append(escapeChar(c));
        }

        return sb.toString();
    }

    private String escapeChar(char c) {
        return switch (c) {
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '&' -> "&amp;";
            case '"' -> "&quot;";
            default -> String.valueOf(c);
        };
    }

    private void remember(JComponent c) {
        if (Boolean.TRUE.equals(
                c.getClientProperty("quiz.search.remembered"))) {
            return;
        }

        rememberedSearchComponents.add(c);

        c.putClientProperty("quiz.search.remembered", true);
        c.putClientProperty(OLD_BORDER_PROPERTY, c.getBorder());
        c.putClientProperty(OLD_BACKGROUND_PROPERTY, c.getBackground());
        c.putClientProperty(OLD_OPAQUE_PROPERTY, c.isOpaque());

        if (c instanceof JLabel l) {
            c.putClientProperty(OLD_FOREGROUND_PROPERTY, l.getForeground());
        }
    }

    private void clearHighlights() {
        currentHit =
                null;

        if (rememberedSearchComponents.isEmpty()
                && previousMatchedCards.isEmpty()) {
            return;
        }

        for (JComponent component
                : new ArrayList<>(rememberedSearchComponents)) {

            restoreRememberedComponent(component);
        }

        rememberedSearchComponents.clear();

        for (QuizablePanel panel
                : new ArrayList<>(previousMatchedCards)) {

            restoreRememberedComponent(panel);
        }

        previousMatchedCards.clear();

        if (targetPanel != null) {
            targetPanel.revalidate();
            targetPanel.repaint();
        }
    }

    private void restoreRememberedComponent(JComponent jc) {
        if (jc instanceof QuizableTextRow row) {
            row.clearHighlight();
        }

        if (jc instanceof QuizableTextBlock block) {
            block.clearHighlight();
        }

        Object badge =
                jc.getClientProperty(HIDDEN_HIT_BADGE_PROPERTY);

        if (badge instanceof JLabel label) {
            jc.remove(label);
            jc.putClientProperty(HIDDEN_HIT_BADGE_PROPERTY, null);
            jc.revalidate();
        }

        if (!Boolean.TRUE.equals(
                jc.getClientProperty("quiz.search.remembered"))) {
            return;
        }

        Object oldBorder =
                jc.getClientProperty(OLD_BORDER_PROPERTY);

        Object oldBackground =
                jc.getClientProperty(OLD_BACKGROUND_PROPERTY);

        Object oldOpaque =
                jc.getClientProperty(OLD_OPAQUE_PROPERTY);

        Object oldForeground =
                jc.getClientProperty(OLD_FOREGROUND_PROPERTY);

        Object oldText =
                jc.getClientProperty(OLD_LABEL_TEXT_PROPERTY);

        jc.setBorder(oldBorder instanceof Border b ? b : null);

        if (oldBackground instanceof Color color) {
            jc.setBackground(color);
        }

        if (oldOpaque instanceof Boolean opaque) {
            jc.setOpaque(opaque);
        }

        if (jc instanceof JLabel lbl) {
            if (oldForeground instanceof Color color) {
                lbl.setForeground(color);
            }

            if (oldText instanceof String s) {
                lbl.setText(s);
            }
        }

        if (jc instanceof QuizablePanel qp) {
            qp.setHighlightColor(null);
        }

        jc.putClientProperty("quiz.search.remembered", null);
        jc.putClientProperty(OLD_BORDER_PROPERTY, null);
        jc.putClientProperty(OLD_BACKGROUND_PROPERTY, null);
        jc.putClientProperty(OLD_OPAQUE_PROPERTY, null);
        jc.putClientProperty(OLD_FOREGROUND_PROPERTY, null);
        jc.putClientProperty(OLD_LABEL_TEXT_PROPERTY, null);
    }

    /*
     * Full clear is kept for debugging/future reset actions. It is no longer
     * used on every keypress because it walks the whole component tree.
     */
    @SuppressWarnings("unused")
    private void clearHighlightsDeep(Component c) {
        if (c instanceof JComponent jc) {
            restoreRememberedComponent(jc);
        }

        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                clearHighlightsDeep(child);
            }
        }
    }

    private void markCurrentHit(JComponent c) {
        if (currentHit != null) {
            restoreBorderOnly(currentHit);
        }

        currentHit =
                c;
    }

    private void restoreBorderOnly(JComponent c) {
        Object o =
                c.getClientProperty(OLD_BORDER_PROPERTY);

        c.setBorder(o instanceof Border b ? b : null);
        c.repaint();
    }

    private boolean containsImagePane(Component c) {
        if (c instanceof ImagePane) {
            return true;
        }

        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                if (containsImagePane(child)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void scrollTo(JComponent c) {
        if (targetScrollPane == null || c == null) {
            return;
        }
        // Deferred: revealing a hit inside a collapsed collection rebuilds/
        // relayouts the card, so the component's bounds aren't final on this
        // pass — converting them now would scroll to a stale (often zero)
        // position. Run after the pending layout so the hit lands in view.
        SwingUtilities.invokeLater(() -> {
            if (c.getParent() == null) {
                return;
            }
            Rectangle rect =
                    SwingUtilities.convertRectangle(
                            c.getParent(),
                            c.getBounds(),
                            targetPanel);
            rect.y -= 24;
            rect.height += 48;
            targetPanel.scrollRectToVisible(rect);
        });
    }

    private void clearResults() {
        resultsPanel.removeAll();
        resultsPanel.revalidate();
        resultsPanel.repaint();
    }

    private void addHitGroupRow(HitGroup g) {
        JPanel row =
                new JPanel(new BorderLayout(4, 0));

        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        g.label =
                new JLabel();

        g.updateLabel();

        JButton prevBtn =
                new JButton("<");

        JButton nextBtn =
                new JButton(">");

        prevBtn.setMargin(new Insets(2, 4, 2, 4));
        nextBtn.setMargin(new Insets(2, 4, 2, 4));

        prevBtn.addActionListener(e -> navigate(g, -1));
        nextBtn.addActionListener(e -> navigate(g, 1));

        JPanel navPanel =
                new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));

        navPanel.add(prevBtn);
        navPanel.add(nextBtn);

        row.add(g.label, BorderLayout.CENTER);
        row.add(navPanel, BorderLayout.EAST);

        resultsPanel.add(row);
    }

    private void navigate(HitGroup g, int delta) {
        if (g.hits.isEmpty()) {
            return;
        }

        g.index =
                Math.floorMod(g.index + delta, g.hits.size());

        g.updateLabel();

        JComponent target =
                g.hits.get(g.index);

        markCurrentHit(target);
        scrollTo(target);
    }

    // --- Virtualized (data-centric) search: hits are quizables, navigated one at
    // a time; each card is built on demand by the VirtualizedCardList. ---

    private void searchSyncVirtual(List<String> queryTokens) {
        Map<String, List<Quizable>> matchesByField =
                searchAndSort.searchQuizables(
                        virtualList.items(),
                        queryTokens,
                        getSearchConfig());

        Map<String, HitGroupQ> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<Quizable>> e : matchesByField.entrySet()) {
            HitGroupQ g = new HitGroupQ(e.getKey());
            g.hits.addAll(e.getValue());
            groups.put(e.getKey(), g);
        }

        showSearchResultsVirtual(groups);
    }

    private void showSearchResultsVirtual(Map<String, HitGroupQ> groups) {
        resultsPanel.removeAll();

        HitGroupQ first = null;
        for (HitGroupQ g : groups.values()) {
            if (g.hits.isEmpty()) {
                continue;
            }
            addHitGroupRowVirtual(g);
            if (first == null) {
                first = g;
            }
        }

        resultsPanel.revalidate();
        resultsPanel.repaint();

        if (first != null) {
            first.index = 0;
            first.updateLabel();
            navigateToCurrentVirtual(first);
        }
    }

    private void addHitGroupRowVirtual(HitGroupQ g) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        g.label = new JLabel();
        g.updateLabel();

        JButton prevBtn = new JButton("<");
        JButton nextBtn = new JButton(">");
        prevBtn.setMargin(new Insets(2, 4, 2, 4));
        nextBtn.setMargin(new Insets(2, 4, 2, 4));
        prevBtn.addActionListener(e -> navigateVirtual(g, -1));
        nextBtn.addActionListener(e -> navigateVirtual(g, 1));

        JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        navPanel.add(prevBtn);
        navPanel.add(nextBtn);

        row.add(g.label, BorderLayout.CENTER);
        row.add(navPanel, BorderLayout.EAST);

        resultsPanel.add(row);
    }

    private void navigateVirtual(HitGroupQ g, int delta) {
        if (g.hits.isEmpty()) {
            return;
        }
        g.index = Math.floorMod(g.index + delta, g.hits.size());
        g.updateLabel();
        navigateToCurrentVirtual(g);
    }

    /** Builds the current hit's card on demand, scrolls to it, and highlights it —
     *  exactly one hit highlighted at a time (the previous one is restored first). */
    private void navigateToCurrentVirtual(HitGroupQ g) {
        clearHighlights();

        Quizable q = g.hits.get(g.index);
        // Pin the hit exactly at the viewport top (real-height layout from it),
        // instead of the estimate-based scrollRectToVisible that could drift.
        JComponent card = virtualList.navigateToTop(q);
        if (card == null) {
            return;
        }

        if (card instanceof QuizablePanel qp) {
            highlightCard(qp);
        }
        markCurrentHitVirtual(card);

        targetPanel.revalidate();
        targetPanel.repaint();
    }

    private void markCurrentHitVirtual(JComponent c) {
        currentHit = c;
        remember(c);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xFF8800), 3, true),
                c.getBorder()));
        c.repaint();
    }

    private static class HitGroupQ {
        final String title;
        final List<Quizable> hits = new ArrayList<>();
        int index = 0;
        JLabel label;

        HitGroupQ(String title) {
            this.title = title;
        }

        void updateLabel() {
            if (label != null) {
                int displayIndex = hits.isEmpty() ? 0 : index + 1;
                label.setText(title + " (" + displayIndex + "/" + hits.size() + ")");
            }
        }
    }

    private static class HitGroup {
        final String title;
        final List<JComponent> hits =
                new ArrayList<>();

        int index =
                0;

        JLabel label;

        HitGroup(String title) {
            this.title = title;
        }

        void updateLabel() {
            if (label != null) {
                int displayIndex =
                        hits.isEmpty() ? 0 : index + 1;

                label.setText(
                        title
                                + " ("
                                + displayIndex
                                + "/"
                                + hits.size()
                                + ")");
            }
        }
    }
}
