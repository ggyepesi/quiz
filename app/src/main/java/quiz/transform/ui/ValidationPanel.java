package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.render.CardListView;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;
import quiz.Quizable;
import quiz.enrichment.EnrichmentDecisionApplier;
import quiz.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentRequest;
import quiz.enrichment.EnrichmentSources;
import quiz.enrichment.EnrichmentReviewRequest;
import quiz.enrichment.FindDataProcess;
import quiz.enrichment.FindDataBatchProcess;
import quiz.enrichment.FindDataBatchResult;
import quiz.enrichment.SourcePageImageEnrichmentProvider;
import quiz.enrichment.WikimediaFieldEnrichmentProvider;
import quiz.enrichment.WikimediaImageEnrichmentProvider;
import quiz.enrichment.ui.EnrichmentReviewPanel;
import quiz.curation.ui.SourceManagerDialog;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.swing.SwingQueryRunner;
import process.CancellationToken;
import process.ProcessInputHandler;
import process.ProcessInputRequest;
import process.ProcessStatus;
import process.swing.SwingProcessRunner;

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
    // Field path → the Wikidata property CHOSEN to source it (via the property finder).
    // Session-scoped: picked once per field, re-asked after a restart (persistence TBD).
    private final java.util.Map<String, String> fieldProperty = new java.util.HashMap<>();
    private final SwingQueryRunner queryRunner;
    private final SwingProcessRunner findDataRunner;
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
        this.findDataRunner = new SwingProcessRunner(
                this.queryRunner.context(),
                this.queryRunner.logListener(),
                this::handleProcessInput);

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
        findDataRunner.registerRunButton(checkButton);
        sourcesButton.addActionListener(e -> manageSources());
        sourcesButton.setEnabled(false);

        for (String t : domain.types()) {
            if (!byType.getOrDefault(t, List.of()).isEmpty()) {
                typeCombo.addItem(t);
            }
        }
        typeCombo.addActionListener(e -> onType());

        JButton resolveButton = new JButton("Resolve identities…");
        resolveButton.setToolTipText(
                "Search Wikidata for a qid for every shown instance, then confirm in one review");
        resolveButton.addActionListener(e -> runResolveIdentities());
        queryRunner.registerRunButton(resolveButton);
        findDataRunner.registerRunButton(resolveButton);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JLabel("Type:"));
        bar.add(typeCombo);
        bar.add(resolveButton);
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
            checkButton.setText("Find data ↗");
            checkButton.setToolTipText(
                    "Find and review values for all members missing this field");
            h.add(checkButton);   // re-parents the persistent, already-registered button
            updateSourcesButton();
            h.add(sourcesButton);
        }
        return h;
    }

    /** Batch the same reusable Find Data process over every missing member. */
    private void onCheck() {
        if (coverage.selectedPath() != null) {
            findData();
        }
    }

    // One immutable batch plan; one independently logged/cancellable child per member.
    private void findData() {
        String path = coverage.selectedPath();
        if (path == null) return;
        quiz.curation.ManualCuration curation = curationStore();
        // Let a manually identified selected member establish its source before the
        // batch. Other unresolved manual members are skipped, not met with a dialog storm.
        if (selected != null && EnrichmentSources.needsSelection(selected, type, curation)
                && !isQid(selected.getIdentifier())) {
            // Resolve the source first (this dialog is modal), then CONTINUE straight into
            // the batch if one was added.
            SourceManagerDialog.show(this, curation, type, selected.getIdentifier(),
                    selected.getDisplayName(), queryRunner, this::updateSourcesButton);
        }
        DomainField drilled = domain.fields(type).stream()
                .filter(f -> path.equals(f.field()))
                .findFirst().orElse(null);
        boolean media = drilled == null || drilled.kind() == objectview.field.FieldKind.MEDIA;

        // For a scalar field with no property chosen yet, pick it ONCE from a sample
        // member's REAL claims (not a hardcoded name→property map), then fill the batch.
        if (!media && !fieldProperty.containsKey(path)) {
            String sampleQid = sampleQidFor(path, curation);
            if (sampleQid != null) {
                findDataRunner.run(
                        new quiz.enrichment.ChooseFieldPropertyProcess(sampleQid, path,
                                WikimediaFieldEnrichmentProvider.suggestedPropertyPid(path)),
                        outcome -> SwingUtilities.invokeLater(() -> {
                            quiz.enrichment.ChosenProperty chosen = outcome.result();
                            if (chosen != null && chosen.isPresent()) {
                                fieldProperty.put(path, chosen.pid());
                                runFillBatch(path);
                            } else if (outcome.status() == ProcessStatus.FAILED
                                    && outcome.error() != null) {
                                JOptionPane.showMessageDialog(ValidationPanel.this,
                                        "Could not list properties: "
                                                + outcome.error().getMessage());
                            }
                        }),
                        ex -> JOptionPane.showMessageDialog(ValidationPanel.this,
                                "Could not list properties: " + ex.getMessage()));
                return;
            }
        }
        runFillBatch(path);
    }

    // Resolve the Wikidata identity (qid) of EVERY shown instance — the foundational,
    // field-independent step. One label search per instance, then a single review assigns
    // qids, written as IdentityLinks the rest of enrichment reads from.
    private void runResolveIdentities() {
        if (type == null || instances.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select a type with instances first.");
            return;
        }
        quiz.curation.ManualCuration curation = curationStore();
        if (curation == null) {
            JOptionPane.showMessageDialog(this,
                    "This domain has no curation store to record identities.");
            return;
        }
        List<quiz.enrichment.ResolveIdentitiesProcess.Subject> subjects = new ArrayList<>();
        for (Quizable member : instances) {
            String current = resolvedQid(member,
                    EnrichmentSources.collect(member, type, curation));
            subjects.add(new quiz.enrichment.ResolveIdentitiesProcess.Subject(
                    member.getIdentifier(), member.getDisplayName(), current));
        }
        findDataRunner.run(
                new quiz.enrichment.ResolveIdentitiesProcess(subjects, 12),
                outcome -> SwingUtilities.invokeLater(() -> {
                    quiz.enrichment.ResolveIdentitiesDecision decision = outcome.result();
                    if (decision != null) {
                        applyResolvedIdentities(decision);
                    }
                    if (outcome.status() == ProcessStatus.FAILED && outcome.error() != null) {
                        JOptionPane.showMessageDialog(ValidationPanel.this,
                                "Resolve failed: " + outcome.error().getMessage());
                    }
                }),
                ex -> JOptionPane.showMessageDialog(ValidationPanel.this,
                        "Resolve failed: " + ex.getMessage()));
    }

    private void applyResolvedIdentities(
            quiz.enrichment.ResolveIdentitiesDecision decision) {
        quiz.curation.ManualCuration curation = curationStore();
        if (curation == null || decision.resolved().isEmpty()) {
            return;
        }
        try {
            for (quiz.enrichment.ResolveIdentitiesDecision.Resolved r : decision.resolved()) {
                curation.putIdentityLink(new quiz.curation.IdentityLink(
                        type, r.targetId(), "Wikidata", r.qid(),
                        "https://www.wikidata.org/wiki/" + r.qid(), r.label(), "wikidata"));
            }
            curation.save();
            onCurated.run();
            onFieldSelected();
            JOptionPane.showMessageDialog(this,
                    decision.resolved().size() + " identity(ies) set.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    /** The first missing member that carries (or resolves to) a QID — the sample entity
     *  whose properties the picker lists. */
    private String sampleQidFor(String path, quiz.curation.ManualCuration curation) {
        for (Quizable member : instances) {
            if (has(member, path)) continue;
            String qid = resolvedQid(member,
                    EnrichmentSources.collect(member, type, curation));
            if (qid != null) return qid;
        }
        return null;
    }

    // One immutable batch plan; one independently logged/cancellable child per member.
    private void runFillBatch(String path) {
        quiz.curation.ManualCuration curation = curationStore();
        DomainField drilled = domain.fields(type).stream()
                .filter(f -> path.equals(f.field()))
                .findFirst().orElse(null);
        boolean collection = drilled != null && drilled.collection();
        boolean media = drilled == null || drilled.kind() == objectview.field.FieldKind.MEDIA;

        List<FindDataProcess> jobs = new ArrayList<>();
        int skipped = 0;
        for (Quizable member : instances) {
            if (has(member, path)) continue;
            List<EnrichmentProposal.SourceRef> sources =
                    EnrichmentSources.collect(member, type, curation);
            String qid = resolvedQid(member, sources);
            String label = member.getDisplayName();
            if (qid == null && EnrichmentSources.needsSelection(member, type, curation)) {
                skipped++;
                continue;
            }
            EnrichmentRequest request = new EnrichmentRequest(
                    new EnrichmentProposal.Subject(
                            type, member.getIdentifier(), qid, label),
                    path, collection, sources);
            List<quiz.enrichment.EnrichmentProvider> providers =
                    providersFor(media, qid, path);
            if (providers.stream().noneMatch(provider -> provider.supports(request))) {
                skipped++;
                continue;
            }
            // Discovery only (false): the batch runs ONE review over all members below,
            // not a modal per member.
            jobs.add(new FindDataProcess(request, providers, false));
        }
        if (jobs.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No missing member is currently eligible for Find Data. "
                            + "Add an exact source for a selected member, or use a "
                            + "field whose name maps to a Wikidata property.");
            return;
        }

        findDataRunner.run(
                new FindDataBatchProcess(jobs, skipped, path),
                outcome -> SwingUtilities.invokeLater(() -> {
                    FindDataBatchResult result = outcome.result();
                    quiz.curation.ManualCuration store = curationStore();
                    if (result != null && store != null) {
                        for (quiz.enrichment.EnrichmentDecision decision
                                : result.acceptedDecisions()) {
                            applyDecision(store, decision);
                        }
                    }
                    if (outcome.status() == ProcessStatus.FAILED && outcome.error() != null) {
                        JOptionPane.showMessageDialog(ValidationPanel.this,
                                "Lookup failed: " + outcome.error().getMessage());
                    }
                }),
                ex -> JOptionPane.showMessageDialog(ValidationPanel.this,
                        "Lookup failed: " + ex.getMessage()));
    }

    private List<quiz.enrichment.EnrichmentProvider> providersFor(
            boolean media, String qid, String path) {
        if (!media) {
            // The chosen property (null → the provider auto-derives from the field name).
            return List.of(new WikimediaFieldEnrichmentProvider(fieldProperty.get(path)));
        }
        List<quiz.enrichment.EnrichmentProvider> providers = new ArrayList<>();
        providers.add(new WikimediaImageEnrichmentProvider());
        providers.add(new SourcePageImageEnrichmentProvider());
        if (qid == null) providers.add(new DBpediaImageEnrichmentProvider());
        return providers;
    }

    private static boolean isQid(String id) {
        return id != null && id.matches("Q\\d+");
    }

    /** Rich input remains rendered by Swing, but the pause/request belongs to Find Data. */
    private <T> T handleProcessInput(
            ProcessInputRequest<T> request, CancellationToken cancellation)
            throws Exception {
        if (request instanceof EnrichmentReviewRequest review) {
            java.util.concurrent.atomic.AtomicReference<quiz.enrichment.EnrichmentDecision> answer =
                    new java.util.concurrent.atomic.AtomicReference<>();
            onEdt(() -> EnrichmentReviewPanel.showDialog(
                    this, review.title(), review.proposal(), answer::set));
            cancellation.throwIfCancelled();
            return request.responseType().cast(answer.get());
        }
        if (request instanceof quiz.enrichment.FindDataBatchReviewRequest batch) {
            java.util.concurrent.atomic.AtomicReference<quiz.enrichment.BatchReviewDecision> answer =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            new quiz.enrichment.BatchReviewDecision(java.util.List.of()));
            onEdt(() -> quiz.enrichment.ui.FindDataBatchReviewPanel.showDialog(
                    this, batch.title(), batch.prompt(), batch.proposals(), answer::set));
            cancellation.throwIfCancelled();
            return request.responseType().cast(answer.get());
        }
        if (request instanceof quiz.enrichment.ResolveIdentitiesReviewRequest resolve) {
            java.util.concurrent.atomic.AtomicReference<quiz.enrichment.ResolveIdentitiesDecision> answer =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            new quiz.enrichment.ResolveIdentitiesDecision(java.util.List.of()));
            onEdt(() -> quiz.enrichment.ui.ResolveIdentitiesReviewPanel.showDialog(
                    this, resolve.title(), resolve.prompt(), resolve.instances(), answer::set));
            cancellation.throwIfCancelled();
            return request.responseType().cast(answer.get());
        }
        if (request instanceof quiz.enrichment.PropertySelectionRequest pick) {
            java.util.concurrent.atomic.AtomicReference<quiz.enrichment.ChosenProperty> answer =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            new quiz.enrichment.ChosenProperty("", ""));
            onEdt(() -> quiz.enrichment.ui.FieldPropertyPickerPanel.showDialog(
                    this, pick.title(), pick.prompt(), pick.field(), pick.options(),
                    pick.suggestedPid(), answer::set));
            cancellation.throwIfCancelled();
            return request.responseType().cast(answer.get());
        }
        return ProcessInputHandler.unsupported().request(request, cancellation);
    }

    private static void onEdt(Runnable show) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) show.run();
        else SwingUtilities.invokeAndWait(show);
    }

    private quiz.curation.ManualCuration curationStore() {
        return domain instanceof quiz.curation.Curatable c ? c.curation() : null;
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

    /** The Wikidata QID to enrich from: the member's own identifier if it IS a QID, else
     *  one carried by an approved Wikidata source (the QID confirmed in the source dialog).
     *  Null when there's none — QID-only providers skip, name-based ones still run. */
    private static String resolvedQid(Quizable member,
                                      List<EnrichmentProposal.SourceRef> sources) {
        String id = member.getIdentifier();
        if (id != null && id.matches("Q\\d+")) {
            return id;
        }
        for (EnrichmentProposal.SourceRef source : sources) {
            if ("Wikidata".equalsIgnoreCase(source.kind())
                    && source.sourceId() != null && source.sourceId().matches("Q\\d+")) {
                return source.sourceId();
            }
        }
        return null;
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
