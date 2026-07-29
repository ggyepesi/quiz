package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.search.SearchPanel;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;
import objectview.Viewable;
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
import process.swing.SwingProcessInput;
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
    private final Map<String, List<Viewable>> byType = new LinkedHashMap<>();

    private final JComboBox<String> typeCombo = new JComboBox<>();
    private final ViewConfigEditor coverage;
    private final JLabel status = new JLabel(" ");
    // One persistent enrichment button, registered with the runner ONCE and reconfigured
    // + re-parented per drill — so drilling many fields doesn't leak run-button registrations.
    private final JButton checkButton = new JButton();
    private final JButton sourcesButton = new JButton("Sources…");
    private final JButton cancelProcessButton = new JButton("Cancel Find Data");
    private final JButton resolveMissingButton = new JButton("Resolve identities…");

    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    // Horizontal, NOT vertical: the coverage table's preferred HEIGHT grows with the
    // field count, so a vertical split lets it starve the drill's card viewport to ~0
    // and nothing renders. Side-by-side (like the main workbench) keeps the drill full
    // height — the arrangement that reliably renders the virtualized cards.
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

    private String type;
    private List<Viewable> instances = List.of();
    private Viewable selected;
    // Field path → the Wikidata property CHOSEN to source it (via the property finder).
    // Session-scoped: picked once per field, re-asked after a restart (persistence TBD).
    private final java.util.Map<String, String> fieldProperty = new java.util.HashMap<>();
    private final SwingQueryRunner queryRunner;
    private final SwingProcessRunner findDataRunner;
    // Re-render the owning view after an accepted enrichment writes a correction.
    private final Runnable onCurated;
    private final IdentityResolutionLauncher identityResolver;
    private List<Viewable> drilledInstances = List.of();

    public ValidationPanel(DomainModel domain) {
        this(domain, null, null, null, null);
    }

    public ValidationPanel(DomainModel domain, SwingQueryRunner queryRunner) {
        this(domain, null, queryRunner, null, null);
    }

    public ValidationPanel(DomainModel domain, SwingQueryRunner queryRunner, Runnable onCurated) {
        this(domain, null, queryRunner, onCurated, null);
    }

    /** Validate {@code instances} — the SHOWN view (filtered + grouped members of the
     *  selected class); null falls back to the whole domain pool. The domain is still
     *  used for schema, field union, and curation. */
    public ValidationPanel(DomainModel domain,
                           Collection<? extends Viewable> instances,
                           SwingQueryRunner queryRunner, Runnable onCurated) {
        this(domain, instances, queryRunner, onCurated, null);
    }

    public ValidationPanel(
            DomainModel domain,
            Collection<? extends Viewable> instances,
            SwingQueryRunner queryRunner,
            Runnable onCurated,
            IdentityResolutionLauncher identityResolver) {
        super(new BorderLayout(6, 6));
        this.domain = domain;
        this.onCurated = onCurated == null ? () -> { } : onCurated;
        this.identityResolver = identityResolver;
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
        findDataRunner.registerCancelButton(cancelProcessButton);

        Collection<? extends Viewable> source = instances != null ? instances : domain.instances();
        for (Viewable q : source) {
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
        resolveMissingButton.addActionListener(e -> {
            if (this.identityResolver != null && !drilledInstances.isEmpty()) {
                this.identityResolver.resolve(
                        List.copyOf(drilledInstances),
                        "Validate: members missing " + type + "." + coverage.selectedPath());
            }
        });

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
        bar.add(cancelProcessButton);

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
    public Viewable selected() {
        return selected;
    }

    private void onType() {
        type = (String) typeCombo.getSelectedItem();
        instances = type == null ? List.of() : byType.getOrDefault(type, List.of());
        selected = null;
        drilledInstances = List.of();

        // Enumerate via the SHARED config source (same fields / order / types as the
        // search/sort/view editors), rendered as an inline collapsible tree. Rebuilding
        // clears the selection, which fires onFieldSelected and resets the drill. The
        // UNION sample shows every field of the type, not just those the first instance
        // happens to carry (a laureate with no portrait would otherwise hide the field).
        Viewable sample = type == null ? null : domain.representativeSample(type);
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
        List<Viewable> missing = new ArrayList<>();
        for (Viewable q : instances) {
            if (!has(q, path)) {
                missing.add(q);
            }
        }
        drilledInstances = List.copyOf(missing);
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
        for (Viewable q : instances) {
            if (has(q, path)) {
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Viewable> sampleClass(Viewable q) {
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
            if (identityResolver != null) {
                resolveMissingButton.setText("Resolve identities for " + gap + " missing…");
                h.add(resolveMissingButton);
            }
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

    /** The first missing member that carries (or resolves to) a QID — the sample entity
     *  whose properties the picker lists. */
    private String sampleQidFor(String path, quiz.curation.ManualCuration curation) {
        for (Viewable member : instances) {
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
        for (Viewable member : instances) {
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
            quiz.enrichment.EnrichmentDecision answer =
                    SwingProcessInput.await(cancellation, completed ->
                    EnrichmentReviewPanel.showModeless(
                            this, review.title(), review.proposal(), completed));
            return request.responseType().cast(answer);
        }
        if (request instanceof quiz.enrichment.FindDataBatchReviewRequest batch) {
            quiz.enrichment.BatchReviewDecision answer =
                    SwingProcessInput.await(cancellation, completed ->
                    quiz.enrichment.ui.FindDataBatchReviewPanel.showModeless(
                            this, batch.title(), batch.prompt(), batch.proposals(), completed));
            return request.responseType().cast(answer);
        }
        if (request instanceof quiz.enrichment.PropertySelectionRequest pick) {
            quiz.enrichment.ChosenProperty answer =
                    SwingProcessInput.await(cancellation, completed ->
                    quiz.enrichment.ui.FieldPropertyPickerPanel.showModeless(
                            this, pick.title(), pick.prompt(), pick.field(), pick.options(),
                            pick.suggestedPid(), completed));
            return request.responseType().cast(answer);
        }
        return ProcessInputHandler.unsupported().request(request, cancellation);
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
    private static String resolvedQid(Viewable member,
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
    private JComponent instancesView(List<Viewable> missing, String type) {
        return InstanceBrowser.create(
                missing, missing.get(0), domain.structuralFields(type), domain.fieldTypes(type),
                o -> {
            selected = o instanceof Viewable q ? q : null;
            updateSourcesButton();
        });
    }

    private static boolean has(Viewable q, String path) {
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
