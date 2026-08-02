package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.search.SearchPanel;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;
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
import quiz.curation.Correction;
import quiz.curation.CorrectionPolicy;
import quiz.curation.Corrections;
import quiz.curation.ScopeFilter;

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
import javax.swing.JTextField;
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
 * {@code Coverage} / {@code Present} / {@code Missing} columns. Select a field to
 * drill into the task's missing, present, or full member scope — rendered with the shared {@link CardListView} /
 * {@link SearchPanel} (selectable, searchable cards), so a missing member IS the object,
 * ready for curation (a manual fill or a source enrichment such as "Check DBpedia").
 */
public final class ValidationPanel extends JPanel {

    private final DomainModel domain;
    private final Map<String, List<Viewable>> byType = new LinkedHashMap<>();

    private final JComboBox<String> typeCombo = new JComboBox<>();
    private final ViewConfigEditor coverage;
    private final JLabel status = new JLabel(" ");
    private final JLabel identityStatus = new JLabel(" ");
    private final JButton showIdentifiedButton = new JButton("Show identified");
    private final JButton showUnresolvedButton = new JButton("Show unresolved");
    // One persistent enrichment button, registered with the runner ONCE and reconfigured
    // + re-parented per drill — so drilling many fields doesn't leak run-button registrations.
    private final JButton checkButton = new JButton();
    private final JButton sourcesButton = new JButton("Sources…");
    private final JButton cancelProcessButton = new JButton("Cancel Find Data");
    private final JButton resolveMissingButton = new JButton("Resolve identities…");
    private final JLabel selectedLabel = new JLabel("No instance selected");
    private final JTextField manualValue = new JTextField(18);
    private final JButton setValueButton = new JButton("Set / replace");
    private final JButton addValueButton = new JButton("Add to collection");

    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    // Horizontal, NOT vertical: the coverage table's preferred HEIGHT grows with the
    // field count, so a vertical split lets it starve the drill's card viewport to ~0
    // and nothing renders. Side-by-side (like the main workbench) keeps the drill full
    // height — the arrangement that reliably renders the virtualized cards.
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

    private String type;
    private String selectedFieldType;
    private String selectedFieldPath;
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
    // null = ordinary field-gap drill; true/false = identified/unresolved identity drill.
    private Boolean identityDrill;
    private boolean identityTask;
    private ScopeFilter scopeFilter = ScopeFilter.MISSING;

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

        Collection<? extends Viewable> source =
                instances != null ? instances : domain.instances();
        for (String candidateType : domain.types()) {
            List<Viewable> members = membersOf(domain, source, candidateType);
            if (!members.isEmpty()) {
                byType.put(candidateType, members);
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
                        identityDrill != null
                                ? "Validate: unresolved " + type + " identities"
                                : "Validate: members missing " + selectedFieldType + "."
                                        + selectedFieldPath);
            }
        });
        showIdentifiedButton.addActionListener(e -> showIdentityMembers(true));
        showUnresolvedButton.addActionListener(e -> showIdentityMembers(false));
        setValueButton.addActionListener(e -> saveManualValue(CorrectionPolicy.REPLACE));
        addValueButton.addActionListener(e -> saveManualValue(CorrectionPolicy.ADD_TO_COLLECTION));
        setValueButton.setEnabled(false);
        addValueButton.setEnabled(false);

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
        bar.add(identityStatus);
        bar.add(showIdentifiedButton);
        bar.add(showUnresolvedButton);
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

    /** Select the curation type: its instances become the drilled population; coverage %
     *  stays over the full domain. */
    public void selectType(String type) {
        typeCombo.setSelectedItem(type);
        onType();
    }

    /** Curate a specific field: select the type, apply the scope filter, drill the field. */
    public void selectField(String type, String field, ScopeFilter filter) {
        scopeFilter = filter;
        selectType(type);
        coverage.setSelectedPath(field);
    }

    /** Curate identity (source.qid): select the type, apply the scope filter, show the
     *  matching members in the identity drill. */
    public void selectIdentity(String type, ScopeFilter filter) {
        scopeFilter = filter;
        selectType(type);
        showIdentityMembers(filter == ScopeFilter.ALL ? null : filter == ScopeFilter.PRESENT);
    }

    private void onType() {
        type = (String) typeCombo.getSelectedItem();
        instances = type == null ? List.of() : byType.getOrDefault(type, List.of());
        selected = null;
        drilledInstances = List.of();
        identityDrill = null;
        identityTask = false;
        selectedFieldType = null;
        selectedFieldPath = null;

        // Enumerate via the SHARED config source (same fields / order / types as the
        // search/sort/view editors), rendered as an inline collapsible tree. Rebuilding
        // clears the selection, which fires onFieldSelected and resets the drill. The
        // UNION sample shows every field of the type, not just those the first instance
        // happens to carry (a laureate with no portrait would otherwise hide the field).
        Viewable sample = type == null ? null : domain.configSample(type);
        ViewConfig coverageConfig = sample == null
                ? new ViewConfig() : ViewConfig.allWithMinorFields(sampleClass(sample));
        coverageConfig.setAllMinorFields(true);
        coverage.setConfigRows(
                coverageConfig,
                sample,
                type == null ? null : domain.fieldTypes(type),
                type == null ? Set.of() : domain.structuralFields(type));
        coverage.setClassBranches(classBranches(type));
        status.setText("   " + instances.size() + " " + (type == null ? "" : type)
                + " instance(s)");
        updateIdentityStatus();
    }

    private void onFieldSelected() {
        ScopedField scoped = scopedField(coverage.selectedPath());
        String path = scoped == null ? null : scoped.path();
        selectedFieldType = scoped == null ? null : scoped.type();
        selectedFieldPath = path;
        selectedChanged(null);
        identityDrill = null;
        identityTask = false;
        instancesHolder.removeAll();
        if (path == null) {
            instancesHolder.add(new JLabel(
                    "   Select a field with a gap to drill into its missing members."),
                    BorderLayout.NORTH);
            instancesHolder.revalidate();
            instancesHolder.repaint();
            return;
        }
        List<Viewable> matching = new ArrayList<>();
        List<Viewable> eligible = scoped == null ? List.of() : instances.stream()
                .filter(member -> domain.isInstanceOf(member, scoped.type())).toList();
        for (Viewable q : eligible) {
            boolean present = has(q, path);
            if (scopeFilter == ScopeFilter.ALL
                    || scopeFilter == ScopeFilter.PRESENT && present
                    || scopeFilter == ScopeFilter.MISSING && !present) {
                matching.add(q);
            }
        }
        drilledInstances = List.copyOf(matching);
        instancesHolder.add(header(selectedFieldType, path, matching.size()), BorderLayout.NORTH);
        if (!matching.isEmpty()) {
            instancesHolder.add(instancesView(matching, selectedFieldType), BorderLayout.CENTER);
        }
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    /** Identity coverage is a convenience shortcut beside the ordinary
     * configurable {@code source.qid} field. Drill either side into the same
     * searchable/selectable instance panel used for field gaps. */
    private void updateIdentityStatus() {
        long identified = instances.stream().filter(this::hasIdentity).count();
        long unresolved = instances.size() - identified;
        identityStatus.setText("Identity: " + identified + " identified · "
                + unresolved + " unresolved");
        showIdentifiedButton.setText("Show identified (" + identified + ")");
        showUnresolvedButton.setText("Show unresolved (" + unresolved + ")");
        showIdentifiedButton.setEnabled(identified > 0);
        showUnresolvedButton.setEnabled(unresolved > 0);
    }

    private void showIdentityMembers(Boolean identified) {
        identityTask = true;
        identityDrill = identified;
        selectedChanged(null);
        List<Viewable> matching = instances.stream()
                .filter(member -> identified == null || hasIdentity(member) == identified)
                .toList();
        drilledInstances = List.copyOf(matching);
        instancesHolder.removeAll();

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        header.add(new JLabel(matching.size() + " " + type + " instance(s) "
                + (identified == null ? "with any identity state"
                : identified ? "with a Wikidata identity" : "without a Wikidata identity")));
        if (Boolean.FALSE.equals(identified) && identityResolver != null && !matching.isEmpty()) {
            resolveMissingButton.setText("Resolve " + matching.size() + " identities…");
            header.add(resolveMissingButton);
        }
        // The source dialog contains the individual Wikidata search/exploration path.
        sourcesButton.setText("Explore identity / sources…");
        header.add(sourcesButton);
        instancesHolder.add(header, BorderLayout.NORTH);
        if (!matching.isEmpty()) {
            instancesHolder.add(identityInstancesView(matching), BorderLayout.CENTER);
        }
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    private boolean hasIdentity(Viewable member) {
        return identityQid(member) != null;
    }

    private String identityQid(Viewable member) {
        Object fieldValue = objectview.field.FieldAccess.getPath(member, "source.qid");
        if (fieldValue instanceof String text) {
            String qid = text.split("\\|", 2)[0].strip();
            if (qid.matches("Q\\d+")) return qid;
        }
        return resolvedQid(member, EnrichmentSources.collect(
                member, concreteType(member), curationStore()));
    }

    /** Identity coverage needs the actual source key, not just a yes/no count.
     * Keep the ordinary cards below it, but add a compact searchable index whose
     * QID column uses the shared Wikidata-link renderer. */
    private JComponent identityInstancesView(List<Viewable> matching) {
        wikidata.explore.workbench.EntityResultPanel identities =
                new wikidata.explore.workbench.EntityResultPanel(
                        List.of("Instance", "Class", "QID"), 2, false);
        identities.setColumnWidths(260, 110, 90);
        identities.setRows(matching.stream()
                .map(member -> List.<Object>of(
                        new IdentityMember(member),
                        java.util.Objects.requireNonNullElse(concreteType(member), ""),
                        java.util.Objects.requireNonNullElse(identityQid(member), "")))
                .toList());
        identities.onSelectionChanged(() -> {
            List<List<Object>> rows = identities.selectedRows();
            Object value = rows.isEmpty() || rows.get(0).isEmpty()
                    ? null : rows.get(0).get(0);
            selected = value instanceof IdentityMember item ? item.member() : null;
            updateSourcesButton();
        });

        JSplitPane identityAndCards = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                identities, instancesView(matching, type));
        identityAndCards.setResizeWeight(0.32);
        SwingUtilities.invokeLater(() -> identityAndCards.setDividerLocation(0.32));
        return identityAndCards;
    }

    private record IdentityMember(Viewable member) {
        @Override public String toString() {
            return member == null ? "" : member.getDisplayName();
        }
    }

    /** Subclass membership is inherited: a USState belongs in State validation too. */
    static List<Viewable> membersOf(
            DomainModel domain,
            Collection<? extends Viewable> source,
            String type) {
        if (domain == null || source == null || type == null) {
            return List.of();
        }
        return source.stream()
                .filter(java.util.Objects::nonNull)
                .filter(member -> domain.isInstanceOf(member, type))
                .map(Viewable.class::cast)
                .toList();
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
                    column("Missing", 64, p -> String.valueOf(eligibleCount(p) - present(p))));
        }

        private String pct(String path) {
            int total = eligibleCount(path);
            if (total == 0) {
                return "—";
            }
            return (Math.round(1000.0 * present(path) / total) / 10.0) + "%";
        }
    }

    private int present(String path) {
        ScopedField scoped = scopedField(path);
        if (scoped == null) return 0;
        int n = 0;
        for (Viewable q : instances) {
            if (domain.isInstanceOf(q, scoped.type()) && has(q, scoped.path())) {
                n++;
            }
        }
        return n;
    }

    private int eligibleCount(String path) {
        ScopedField scoped = scopedField(path);
        if (scoped == null) return 0;
        int n = 0;
        for (Viewable q : instances) {
            if (domain.isInstanceOf(q, scoped.type())) n++;
        }
        return n;
    }

    /** Build the same virtual subtype branches used by the main search/sort/view
     * editors. Only fields introduced by the subtype occur under its heading; base
     * fields remain in the base branch and therefore cover the full class family. */
    private List<ViewConfigEditor.ClassBranch> classBranches(String baseType) {
        if (baseType == null) return List.of();
        List<ViewConfigEditor.ClassBranch> branches = new ArrayList<>();
        for (String subtype : domain.subtypesOf(baseType)) {
            Set<String> additional = domain.additionalFields(subtype);
            if (additional.isEmpty()) continue;
            ViewConfig config = new ViewConfig();
            config.setAllFields(false);
            for (String field : additional) {
                config.addField(field, ViewConfig.leaf());
            }
            Viewable sample = domain.representativeSample(subtype);
            @SuppressWarnings("unchecked")
            Class<? extends Viewable> cls = sample == null
                    ? Viewable.class
                    : (Class<? extends Viewable>) sample.getClass();
            branches.add(new ViewConfigEditor.ClassBranch(
                    subtype, domain.baseType(subtype), cls,
                    onlyFields(domain.fieldTypes(subtype), additional), config));
        }
        return List.copyOf(branches);
    }

    private static objectview.viewconfig.FieldTypeSource onlyFields(
            objectview.viewconfig.FieldTypeSource source, Set<String> fields) {
        Set<String> included = fields == null ? Set.of()
                : java.util.Collections.unmodifiableSet(
                        new java.util.LinkedHashSet<>(fields));
        return new objectview.viewconfig.FieldTypeSource() {
            @Override public FieldTypeInfo field(String name) {
                return included.contains(name) && source != null
                        ? source.field(name) : null;
            }

            @Override public List<String> fieldNames() {
                return List.copyOf(included);
            }
        };
    }

    private record ScopedField(String type, String path) { }

    /** ViewConfigEditor represents a subtype heading with an internal path segment
     * such as {@code @subtype:USState.admissionDate}. Strip those presentation-only
     * segments and retain the deepest subtype as the field's coverage scope. */
    private ScopedField scopedField(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || type == null) return null;
        String scopedType = type;
        List<String> fieldSegments = new ArrayList<>();
        for (String segment : rawPath.split("\\.")) {
            if (segment.startsWith("@subtype:") && segment.length() > 9) {
                scopedType = segment.substring(9);
            } else if (!segment.isBlank()) {
                fieldSegments.add(segment);
            }
        }
        return fieldSegments.isEmpty() ? null
                : new ScopedField(scopedType, String.join(".", fieldSegments));
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

    private JComponent header(String fieldType, String path, int count) {
        JPanel h = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        h.add(new JLabel(count + " " + scopeFilter.toString().toLowerCase()
                + " for " + fieldType + "." + path));
        if (count == 0) return h;

        // Stage 3 — the curation type offered is gated by the field's kind:
        //   reference → out of scope (no Find Data / manual entry yet)
        //   identity (source.qid) → Explore identity only, NEVER Find Data
        //   data (scalar / media) → Find Data + manual value
        CurateType ct = curateTypeOf(fieldType, path);
        if (ct == CurateType.REFERENCE) {
            JLabel note = new JLabel("— reference-field curation isn't supported yet");
            note.setEnabled(false);
            h.add(note);
            return h;
        }
        if (identityResolver != null) {
            resolveMissingButton.setText("Resolve identities for " + count + " member(s)…");
            h.add(resolveMissingButton);
        }
        if (ct == CurateType.DATA) {
            checkButton.setText("Find data ↗");
            checkButton.setToolTipText(
                    "Find and review values for the members in this task");
            h.add(checkButton);   // re-parents the persistent, already-registered button
        }
        updateSourcesButton();
        h.add(sourcesButton);     // "Explore identity / sources…" — the identity action
        h.add(new JLabel("Selected:"));
        h.add(selectedLabel);
        if (ct == CurateType.DATA) {
            h.add(manualValue);
            h.add(setValueButton);
            DomainField field = selectedDomainField();
            addValueButton.setVisible(field != null && field.collection());
            h.add(addValueButton);
        }
        return h;
    }

    /** How a chosen field is curated (Stage 3): identity (source.qid → Explore), an ordinary
     *  data field (Find Data / manual value), or a reference (entity-valued — out of scope for
     *  now). Identity is matched first, so the provenance {@code source} object — itself a
     *  reference — routes to Explore, not the reference dead-end. */
    private enum CurateType { IDENTITY, DATA, REFERENCE, NONE }

    private CurateType curateTypeOf(String type, String path) {
        if (path == null) return CurateType.NONE;
        if (path.equals("source") || path.startsWith("source.")) return CurateType.IDENTITY;
        DomainField f = fieldFor(type, path);
        if (f != null && f.reference()) return CurateType.REFERENCE;
        return CurateType.DATA;
    }

    private DomainField fieldFor(String type, String path) {
        if (type == null || path == null) return null;
        return domain.fields(type).stream()
                .filter(f -> path.equals(f.field()))
                .findFirst().orElse(null);
    }

    /** Batch the same reusable Find Data process over every missing member. */
    private void onCheck() {
        if (selectedFieldPath != null) {
            findData();
        }
    }

    // One immutable batch plan; one independently logged/cancellable child per member.
    private void findData() {
        String path = selectedFieldPath;
        if (path == null) return;
        quiz.curation.ManualCuration curation = curationStore();
        // Let a manually identified selected member establish its source before the
        // batch. Other unresolved manual members are skipped, not met with a dialog storm.
        if (selected != null && EnrichmentSources.needsSelection(
                selected, concreteType(selected), curation)
                && !isQid(selected.getIdentifier())) {
            // Resolve the source first (this dialog is modal), then CONTINUE straight into
            // the batch if one was added.
            SourceManagerDialog.show(this, curation, concreteType(selected),
                    selected.getIdentifier(),
                    selected.getDisplayName(), queryRunner, this::identitySourceChanged);
        }
        DomainField drilled = domain.fields(selectedFieldType).stream()
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
                                null),
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
        for (Viewable member : drilledInstances) {
            if (!domain.isInstanceOf(member, selectedFieldType)) continue;
            String qid = resolvedQid(member,
                    EnrichmentSources.collect(member, concreteType(member), curation));
            if (qid != null) return qid;
        }
        return null;
    }

    // One immutable batch plan; one independently logged/cancellable child per member.
    private void runFillBatch(String path) {
        quiz.curation.ManualCuration curation = curationStore();
        DomainField drilled = domain.fields(selectedFieldType).stream()
                .filter(f -> path.equals(f.field()))
                .findFirst().orElse(null);
        boolean collection = drilled != null && drilled.collection();
        boolean media = drilled == null || drilled.kind() == objectview.field.FieldKind.MEDIA;

        List<FindDataProcess> jobs = new ArrayList<>();
        int skipped = 0;
        for (Viewable member : drilledInstances) {
            if (!domain.isInstanceOf(member, selectedFieldType)) continue;
            String memberType = concreteType(member);
            List<EnrichmentProposal.SourceRef> sources =
                    EnrichmentSources.collect(member, memberType, curation);
            String qid = resolvedQid(member, sources);
            String label = member.getDisplayName();
            if (qid == null && EnrichmentSources.needsSelection(
                    member, memberType, curation)) {
                skipped++;
                continue;
            }
            EnrichmentRequest request = new EnrichmentRequest(
                    new EnrichmentProposal.Subject(
                            memberType, member.getIdentifier(), qid, label),
                    path, collection, sources,
                    DomainSchemas.resolve(domain, memberType, path));
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
        SourceManagerDialog.show(this, curationStore(), concreteType(selected),
                selected.getIdentifier(), selected.getDisplayName(), queryRunner,
                this::identitySourceChanged);
    }

    private void updateSourcesButton() {
        quiz.curation.ManualCuration curation = curationStore();
        boolean enabled = selected != null && curation != null;
        sourcesButton.setEnabled(enabled);
        int count = enabled
                ? EnrichmentSources.collect(
                        selected, concreteType(selected), curation).size() : 0;
        sourcesButton.setText(count == 0
                ? "Explore identity / sources…"
                : "Explore identity / sources (" + count + ")…");
        sourcesButton.setToolTipText(enabled
                ? "Review or add exact source records for the selected member"
                : "Select a member card first");
    }

    private void identitySourceChanged() {
        if (selected != null) {
            quiz.curation.IdentitySources.refresh(selected, curationStore());
        }
        updateSourcesButton();
        updateIdentityStatus();
        if (identityTask) {
            showIdentityMembers(identityDrill);
        }
        onCurated.run();
    }

    private DomainField selectedDomainField() {
        if (selectedFieldType == null || selectedFieldPath == null) return null;
        return domain.fields(selectedFieldType).stream()
                .filter(field -> selectedFieldPath.equals(field.field()))
                .findFirst().orElse(null);
    }

    /** Persist an ordinary field edit as a Correction. Identity deliberately does not
     * come through here: source.qid is approved and stored as an IdentityLink. */
    private void saveManualValue(CorrectionPolicy policy) {
        quiz.curation.ManualCuration store = curationStore();
        if (store == null || selected == null || selectedFieldPath == null) return;
        String text = manualValue.getText().trim();
        if (text.isEmpty()) return;
        DomainField field = selectedDomainField();
        if (policy == CorrectionPolicy.ADD_TO_COLLECTION
                && (field == null || !field.collection())) return;

        Object value;
        try {
            value = typedManualValue(text);
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this,
                    "Value does not match the declared field type: " + invalid.getMessage());
            return;
        }
        if (policy == CorrectionPolicy.ADD_TO_COLLECTION) {
            List<Object> additions = new ArrayList<>();
            store.corrections().stream()
                    .filter(c -> java.util.Objects.equals(c.type(), concreteType(selected))
                            && selected.getIdentifier().equals(c.qid())
                            && selectedFieldPath.equals(c.field())
                            && c.effectivePolicy() == CorrectionPolicy.ADD_TO_COLLECTION)
                    .findFirst().map(Correction::value).ifPresent(existing -> {
                        if (existing instanceof Collection<?> collection) additions.addAll(collection);
                        else additions.add(existing);
                    });
            if (!additions.contains(value)) additions.add(value);
            value = List.copyOf(additions);
        }

        String memberType = concreteType(selected);
        List<Correction> before = store.corrections().stream()
                .filter(c -> (c.type() == null || memberType.equals(c.type()))
                        && selected.getIdentifier().equals(c.qid())
                        && selectedFieldPath.equals(c.field()))
                .toList();
        store.put(memberType, selected.getIdentifier(), selectedFieldPath, value,
                Correction.MANUAL, null, policy, null);
        try {
            store.save();
        } catch (Exception ex) {
            store.remove(memberType, selected.getIdentifier(), selectedFieldPath);
            before.forEach(store::restore);
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            return;
        }
        Corrections.apply(domain.instances(), List.of(store));
        manualValue.setText("");
        onCurated.run();
        onFieldSelected();
    }

    private Object typedManualValue(String text) {
        objectview.field.FieldRef schema = DomainSchemas.resolve(
                domain, selectedFieldType, selectedFieldPath);
        if (schema == null) return text;
        objectview.field.FieldKind kind = schema.collection()
                ? schema.valueKind() : schema.kind();
        try {
            if (kind == objectview.field.FieldKind.BOOLEAN) {
                if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                    throw new IllegalArgumentException("Enter true or false");
                }
                return Boolean.valueOf(text);
            }
            if (kind == objectview.field.FieldKind.ORDERED) {
                return text.matches("[-+]?\\d+")
                        ? Long.valueOf(text) : Double.valueOf(text);
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
        return text;
    }

    private void selectedChanged(Viewable member) {
        selected = member;
        selectedLabel.setText(member == null ? "No instance selected" : member.getDisplayName());
        setValueButton.setEnabled(member != null && selectedFieldPath != null);
        addValueButton.setEnabled(member != null && selectedFieldPath != null);
        if (member != null && selectedFieldPath != null) {
            Object current = objectview.field.FieldAccess.getPath(member, selectedFieldPath);
            manualValue.setText(current == null ? "" : String.valueOf(current));
        }
        updateSourcesButton();
    }

    private String concreteType(Viewable member) {
        String concrete = domain.mostSpecificClass(member);
        if (concrete != null && !concrete.isBlank()) {
            return concrete;
        }
        return member != null && member.typeName() != null
                && !member.typeName().isBlank() ? member.typeName() : type;
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
        return objectview.search.SearchableCardView.builder(missing)
                .sample(missing.get(0))
                .hiddenFields(domain.structuralFields(type))
                .fieldTypes(domain.fieldTypes(type))
                .fieldSchemas(q -> domain.fieldSchema(q.typeName()))
                .collapsible(true)
                .selectionListener(o -> {
            selectedChanged(o instanceof Viewable q ? q : null);
        }).build();
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
