package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldAccess;
import objectview.field.FieldKind;
import objectview.field.FieldSet;
import objectview.render.CardListView;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;
import quiz.Quizable;
import quiz.enrichment.CompositeEnrichmentProvider;
import quiz.enrichment.EnrichmentDecisionApplier;
import quiz.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentRequest;
import quiz.enrichment.EnrichmentSources;
import quiz.enrichment.SourcePageImageEnrichmentProvider;
import quiz.enrichment.ui.EnrichmentReviewPanel;
import quiz.curation.ui.SourceManagerDialog;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Consistency validation in the transform app — the front of the CURATE stage. It runs
 * over the WHOLE working schema the domain exposes (base classes, DERIVED classes and
 * facets), one type at a time. The coverage view IS the shared field-config table
 * ({@link ViewConfigEditor}) driven by a {@link FieldTableContributor}: per field it adds
 * {@code Coverage} / {@code Present} / {@code Missing} columns. Select a gappy field to
 * drill into the members missing it — rendered with the shared {@link CardListView} /
 * {@link SearchPanel} (selectable, searchable cards), so a missing member IS the object,
 * ready for curation (a manual fill or a source enrichment such as "Check DBpedia").
 */
public final class ValidationPanel extends JPanel {

    private final DomainModel domain;
    private final Map<String, List<Quizable>> byType = new LinkedHashMap<>();

    private final JComboBox<String> typeCombo = new JComboBox<>();
    private final ViewConfigEditor coverage;
    private final JLabel status = new JLabel(" ");
    // One persistent enrichment button, registered with the runner ONCE and reconfigured
    // + re-parented per drill — so drilling many fields doesn't leak run-button registrations.
    private final JButton checkButton = new JButton();
    private final JButton sourcesButton = new JButton("Sources…");

    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    // Horizontal, NOT vertical: the coverage table's preferred HEIGHT grows with the
    // field count, so a vertical split lets it starve the drill's card viewport to ~0
    // and nothing renders. Side-by-side (like the main workbench) keeps the drill full
    // height — the arrangement that reliably renders the virtualized cards.
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

    private String type;
    private List<Quizable> instances = List.of();
    private Quizable selected;
    private final SwingQueryRunner queryRunner;
    // Re-render the owning view after an accepted enrichment writes a correction.
    private final Runnable onCurated;

    public ValidationPanel(DomainModel domain) {
        this(domain, null, null, null);
    }

    public ValidationPanel(DomainModel domain, SwingQueryRunner queryRunner) {
        this(domain, null, queryRunner, null);
    }

    public ValidationPanel(DomainModel domain, SwingQueryRunner queryRunner, Runnable onCurated) {
        this(domain, null, queryRunner, onCurated);
    }

    /** Validate {@code instances} — the SHOWN view (filtered + grouped members of the
     *  selected class); null falls back to the whole domain pool. The domain is still
     *  used for schema, field union, and curation. */
    public ValidationPanel(DomainModel domain,
                           Collection<? extends Quizable> instances,
                           SwingQueryRunner queryRunner, Runnable onCurated) {
        super(new BorderLayout(6, 6));
        this.domain = domain;
        this.onCurated = onCurated == null ? () -> { } : onCurated;
        this.queryRunner = queryRunner == null
                ? new SwingQueryRunner(
                        new QueryContext(new WikidataSparqlClient(
                                "QuizProject/1.0 (ggyepesi@gmail.com)", 2,
                                WikidataSparqlClient.DBPEDIA_ENDPOINT),
                                new WikidataApiClient("QuizProject/1.0")),
                        null)
                : queryRunner;

        Collection<? extends Quizable> source = instances != null ? instances : domain.instances();
        for (Quizable q : source) {
            if (q != null && q.typeName() != null) {
                byType.computeIfAbsent(q.typeName(), k -> new ArrayList<>()).add(q);
            }
        }

        coverage = new ViewConfigEditor(new ViewConfig(), (Viewable) null, new CoverageColumns());
        coverage.setChangeListener(this::onFieldSelected);

        checkButton.addActionListener(e -> onCheck());
        queryRunner.registerRunButton(checkButton);
        sourcesButton.addActionListener(e -> manageSources());
        sourcesButton.setEnabled(false);

        for (String t : domain.types()) {
            if (!byType.getOrDefault(t, List.of()).isEmpty()) {
                typeCombo.addItem(t);
            }
        }
        typeCombo.addActionListener(e -> onType());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JLabel("Type:"));
        bar.add(typeCombo);
        bar.add(status);

        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(coverage, BorderLayout.CENTER);

        instancesHolder.add(
                new JLabel("   Select a field with a gap to drill into its missing members."),
                BorderLayout.NORTH);

        split.setTopComponent(top);
        split.setBottomComponent(instancesHolder);
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        if (typeCombo.getItemCount() > 0) {
            typeCombo.setSelectedIndex(0);
            onType();
        }
    }

    // Split the space evenly once realized (setResizeWeight only governs *resize*
    // distribution, not the initial divider seeded from preferred sizes).
    @Override public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.5));
    }

    /** The last selected missing member — the target a curation/enrichment action fills. */
    public Quizable selected() {
        return selected;
    }

    private void onType() {
        type = (String) typeCombo.getSelectedItem();
        instances = type == null ? List.of() : byType.getOrDefault(type, List.of());
        selected = null;

        // Enumerate via the SHARED config source (same fields / order / types as the
        // search/sort/view editors), rendered as an inline collapsible tree. Rebuilding
        // clears the selection, which fires onFieldSelected and resets the drill. The
        // UNION sample shows every field of the type, not just those the first instance
        // happens to carry (a laureate with no portrait would otherwise hide the field).
        Quizable sample = type == null ? null : domain.representativeSample(type);
        coverage.setConfigRows(
                sample == null ? new ViewConfig() : ViewConfig.all(sampleClass(sample)),
                sample,
                type == null ? null : domain.fieldTypes(type),
                type == null ? Set.of() : domain.structuralFields(type));
        status.setText("   " + instances.size() + " " + (type == null ? "" : type)
                + " instance(s)");
    }

    private void onFieldSelected() {
        String path = coverage.selectedPath();
        selected = null;
        instancesHolder.removeAll();
        if (path == null) {
            instancesHolder.add(new JLabel(
                    "   Select a field with a gap to drill into its missing members."),
                    BorderLayout.NORTH);
            instancesHolder.revalidate();
            instancesHolder.repaint();
            return;
        }
        List<Quizable> missing = new ArrayList<>();
        for (Quizable q : instances) {
            if (!has(q, path)) {
                missing.add(q);
            }
        }
        instancesHolder.add(header(path, missing.size()), BorderLayout.NORTH);
        if (!missing.isEmpty()) {
            instancesHolder.add(instancesView(missing, type), BorderLayout.CENTER);
        }
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    // The coverage plugin: single-select field picker + Coverage / Present / Missing
    // columns computed over the currently selected type's instances.
    private final class CoverageColumns implements FieldTableContributor {
        @Override public SelectionMode selectionMode() {
            return SelectionMode.SINGLE;
        }

        @Override public List<ExtraColumn> columns() {
            return List.of(
                    column("Coverage", 80, this::pct),
                    column("Present", 64, p -> String.valueOf(present(p))),
                    column("Missing", 64, p -> String.valueOf(instances.size() - present(p))));
        }

        private String pct(String path) {
            int total = instances.size();
            if (total == 0) {
                return "—";
            }
            return (Math.round(1000.0 * present(path) / total) / 10.0) + "%";
        }
    }

    private int present(String path) {
        int n = 0;
        for (Quizable q : instances) {
            if (has(q, path)) {
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Viewable> sampleClass(Quizable q) {
        return (Class<? extends Viewable>) q.getClass();
    }

    private static FieldTableContributor.ExtraColumn column(
            String header,
            int width,
            Function<String, Object> value) {

        return new FieldTableContributor.ExtraColumn() {
            @Override
            public String header() {
                return header;
            }

            @Override
            public int width() {
                return width;
            }

            @Override
            public Object value(FieldRow row) {
                return value.apply(row.path());
            }
        };
    }

    private JComponent header(String path, int gap) {
        JPanel h = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        h.add(new JLabel(gap == 0
                ? type + "." + path + " — fully covered."
                : gap + " member(s) missing " + type + "." + path));
        if (gap > 0) {
            boolean media = isMediaField(path);
            checkButton.setText(media ? "Find image ↗" : "Check DBpedia ↗");
            checkButton.setToolTipText((media
                    ? "Find an image for the SELECTED member on DBpedia"
                    : "Look up the SELECTED member's \"" + leaf(path) + "\" on DBpedia")
                    + " (select a card first)");
            h.add(checkButton);   // re-parents the persistent, already-registered button
            updateSourcesButton();
            h.add(sourcesButton);
        }
        return h;
    }

    /** Run the enrichment for the currently-drilled field — image lookup for a media
     *  field, else the text-property lookup. Reads the live selected path, so the single
     *  persistent button always acts on the current drill. */
    private void onCheck() {
        String path = coverage.selectedPath();
        if (path == null) {
            return;
        }
        if (isMediaField(path)) {
            findDbpediaImage();
        } else {
            checkDbpedia(leaf(path));
        }
    }

    /** Whether {@code path} on the current type is a media field — so the drill offers
     *  an image lookup + thumbnail preview instead of the text-property lookup. */
    private boolean isMediaField(String path) {
        for (DomainField f : domain.fields(type)) {
            if (f.field().equals(path)) {
                if (f.kind() == FieldKind.MEDIA) {
                    return true;
                }
                break;
            }
        }
        // Fallback: sniff a member that HAS the field (the drill list is all-missing).
        for (Quizable q : byType.getOrDefault(type, List.of())) {
            Object v = FieldAccess.getPath(q, path);
            if (v != null) {
                return FieldKind.ofValue(v) == FieldKind.MEDIA;
            }
        }
        return false;
    }

    // PROPOSE-only: find image candidate(s) for the selected member on DBpedia (by QID
    // via owl:sameAs, else by name via rdfs:label) and show them as thumbnails. Next
    // step: accept one → a media Correction (origin "dbpedia") into the overlay.
    private void findDbpediaImage() {
        Quizable m = selected;
        if (m == null) {
            JOptionPane.showMessageDialog(this, "Select a member card first.");
            return;
        }
        quiz.curation.ManualCuration curation = curationStore();
        if (EnrichmentSources.needsSelection(m, type, curation)) {
            SourceManagerDialog.show(this, curation, type, m.getIdentifier(),
                    m.getDisplayName(), queryRunner, this::updateSourcesButton);
            return;
        }
        String qid = m.getIdentifier();
        String label = m.getDisplayName();
        boolean hasQid = qid != null && qid.matches("Q\\d+");
        if (!hasQid && (label == null || label.isBlank())) {
            JOptionPane.showMessageDialog(this,
                    "This member has no Wikidata QID or name to look up on DBpedia.");
            return;
        }
        String path = coverage.selectedPath();
        boolean collection = domain.fields(type).stream()
                .filter(f -> path != null && path.equals(f.field()))
                .findFirst()
                .map(DomainField::collection)
                .orElse(false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject(type, qid, label),
                path, collection, EnrichmentSources.collect(m, type, curationStore()));
        CompositeEnrichmentProvider provider = new CompositeEnrichmentProvider(List.of(
                new SourcePageImageEnrichmentProvider(),
                new DBpediaImageEnrichmentProvider()));
        queryRunner.run(
                provider.discover(request),
                proposal -> SwingUtilities.invokeLater(() -> {
                    if (proposal.media().isEmpty()) {
                        JOptionPane.showMessageDialog(ValidationPanel.this,
                                "No configured source returned an image for " + label + ".");
                    } else {
                        reviewProposal(proposal);
                    }
                }),
                ex -> JOptionPane.showMessageDialog(ValidationPanel.this,
                        "Image lookup failed: " + ex.getMessage()));
    }

    private quiz.curation.ManualCuration curationStore() {
        return domain instanceof quiz.curation.Curatable c ? c.curation() : null;
    }

    private void reviewProposal(EnrichmentProposal proposal) {
        quiz.curation.ManualCuration curation = curationStore();
        if (curation == null) {
            return;
        }
        EnrichmentReviewPanel.showDialog(
                this,
                "Review enrichment — " + proposal.subject().displayName(),
                proposal,
                decision -> applyDecision(curation, decision));
    }

    private void applyDecision(
            quiz.curation.ManualCuration curation,
            quiz.enrichment.EnrichmentDecision decision) {
        try {
            EnrichmentDecisionApplier.apply(domain, curation, decision);
            onCurated.run();
            onFieldSelected();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    private void manageSources() {
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a member card first.");
            return;
        }
        SourceManagerDialog.show(this, curationStore(), type, selected.getIdentifier(),
                selected.getDisplayName(), queryRunner, this::updateSourcesButton);
    }

    private void updateSourcesButton() {
        quiz.curation.ManualCuration curation = curationStore();
        boolean enabled = selected != null && curation != null;
        sourcesButton.setEnabled(enabled);
        int count = enabled
                ? EnrichmentSources.collect(selected, type, curation).size() : 0;
        sourcesButton.setText(count == 0 ? "Sources…" : "Sources (" + count + ")…");
        sourcesButton.setToolTipText(enabled
                ? "Review or add exact source records for the selected member"
                : "Select a member card first");
    }

    // PROPOSE-only enrichment: look up the selected member's field on DBpedia and show
    // the candidate(s). Next step: accept one → a Correction (origin "dbpedia") overlay.
    private void checkDbpedia(String property) {
        Quizable m = selected;
        if (m == null) {
            JOptionPane.showMessageDialog(this, "Select a member card first.");
            return;
        }
        String qid = m.getIdentifier();
        if (qid == null || !qid.matches("Q\\d+")) {
            JOptionPane.showMessageDialog(this, "This member has no Wikidata QID to join on.");
            return;
        }
        String name = m.getDisplayName();
        queryRunner.run(
                DBpediaLookup.values(qid, property),
                c -> SwingUtilities.invokeLater(() -> {
                    String msg = c.isEmpty()
                            ? "DBpedia has no \"" + property + "\" for " + name + "."
                            : "DBpedia \"" + property + "\" for " + name + ":\n  • "
                                    + String.join("\n  • ", c);
                    JOptionPane.showMessageDialog(ValidationPanel.this, msg,
                            "DBpedia candidates", JOptionPane.INFORMATION_MESSAGE);
                }),
                ex -> JOptionPane.showMessageDialog(ValidationPanel.this,
                        "DBpedia lookup failed: " + ex.getMessage()));
    }

    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    // The shared instance rendering: selectable, searchable cards (same components the
    // curation panel uses), so a missing member is the object — click to select it.
    private JComponent instancesView(List<Quizable> missing, String type) {
        CardListView v = new CardListView();
        RenderContext ctx = new RenderContext();
        ctx.setCollapsibleCards(true);
        ctx.setSelectionEnabled(true);
        ctx.addSelectionListener(o -> {
            selected = o instanceof Quizable q ? q : null;
            updateSourcesButton();
        });
        v.setRenderContext(ctx);
        for (Quizable m : missing) {
            v.addViewable(m);
        }
        v.createCardsPanel(1);

        JPanel panel = new JPanel(new BorderLayout());
        Quizable sample = missing.get(0);
        @SuppressWarnings("unchecked")
        Class<? extends Quizable> cls = (Class<? extends Quizable>) sample.getClass();
        SearchPanel engine = new SearchPanel(cls, sample);
        engine.setHiddenFields(domain.structuralFields(type));
        engine.setFieldTypes(domain.fieldTypes(type));
        engine.setTarget(v.getCardsPanel(), v.getCardsScrollPane());
        v.addTargetListener(engine);
        panel.add(engine, BorderLayout.NORTH);
        panel.add(v.getCardsScrollPane(), BorderLayout.CENTER);

        // The virtualized card list materializes cards off its viewport size. On the
        // FIRST drill in a freshly shown dialog that size isn't settled yet and the
        // build can miss; once the panel is laid out, force one rebuild so the cards
        // always appear (not just after a warm-up dialog).
        SwingUtilities.invokeLater(() -> {
            if (v.getVirtualList() != null) {
                v.getVirtualList().rebuild();
            }
        });
        return panel;
    }

    private static boolean has(Quizable q, String path) {
        List<Object> current = new ArrayList<>();
        current.add(q);
        for (String seg : path.split("\\.")) {
            List<Object> next = new ArrayList<>();
            for (Object o : current) {
                if (o instanceof Viewable v) {
                    Object val = FieldSet.of(v).read(seg);
                    // `name` is identity/display data (getName()), not a stored map/
                    // reflected field, so a plain read misses it — fall back to it, else
                    // the always-present name reads as 0% covered.
                    if (val == null && "name".equals(seg)) {
                        val = v.getName();
                    }
                    if (val instanceof Collection<?> c) {
                        next.addAll(c);
                    } else if (val != null) {
                        next.add(val);
                    }
                }
            }
            current = next;
        }
        for (Object o : current) {
            if (o == null) {
                continue;
            }
            if (o instanceof String s && s.isBlank()) {
                continue;
            }
            if (o instanceof Collection<?> c && c.isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }
}
