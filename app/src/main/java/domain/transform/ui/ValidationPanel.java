package domain.transform.ui;

import domain.datasource.Datasources;
import domain.datasource.SourceRef;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.evidence.ArticleIntroduction;
import domain.datasource.evidence.InfoboxParameters;
import domain.datasource.evidence.WikipediaArticle;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.datasource.wikipedia.WikipediaEvidenceRecipes;
import domain.datasource.wikipedia.WikipediaTableRows;

import objectview.Viewable;
import objectview.field.FieldPath;
import objectview.field.FieldSet;
import objectview.search.SearchPanel;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;
import domain.enrichment.EnrichmentDecision;
import domain.enrichment.EnrichmentDecisionApplier;
import domain.enrichment.EnrichmentProposal;
import domain.enrichment.EnrichmentProvider;
import domain.enrichment.EnrichmentRequest;
import domain.enrichment.EnrichmentRoute;
import process.swing.Dialogs;
import domain.enrichment.EnrichmentSources;
import domain.enrichment.FindDataBatchProcess;
import domain.enrichment.FindDataBatchResult;
import domain.enrichment.FindDataProcess;
import domain.enrichment.FindDataResult;
import domain.enrichment.ResolveNamesProcess;
import domain.enrichment.SourcePageImageEnrichmentProvider;
import domain.enrichment.WikimediaEntityLookup;
import domain.enrichment.WikimediaImageEnrichmentProvider;
import domain.enrichment.WikipediaInfoboxReferenceEvidenceProvider;
import domain.enrichment.WikipediaTableReferenceEvidenceProvider;
import domain.enrichment.WikipediaTableRowInstanceEvidenceProvider;
import domain.enrichment.WikipediaTextEvidenceProvider;
import domain.enrichment.ui.FindDataBatchReviewPanel;
import domain.enrichment.ui.SourceYieldCards;
import domain.curation.Correction;
import domain.curation.CorrectionPolicy;
import domain.curation.Corrections;
import domain.curation.Curatable;
import domain.curation.CurationStaging;
import domain.curation.FieldRulePromoter;
import domain.curation.FieldSourceChoices;
import domain.curation.IdentityLink;
import domain.curation.IdentityLinks;
import domain.curation.ManualCuration;
import domain.curation.ScopeFilter;
import domain.provenance.SourceIdentities;
import domain.provenance.WikidataSource;
import domain.transform.DynamicViewable;
import domain.workbench.ui.AdditionalSourcePicker;
import domain.workbench.ui.ExploreByExamplePanel;
import domain.workbench.ui.WikipediaCategoryRuleEditor;
import domain.workbench.ui.WorkbenchActionList;

import domain.workbench.execution.ui.SwingQueryRunner;
import wikidata.ui.IdentityChip;
import domain.config.FieldProductionKind;
import domain.config.FieldSourceMapping;
import domain.config.FieldSourceType;
import domain.config.RuleDirection;
import work.CancellationToken;
import process.ProcessInputHandler;
import process.ProcessInputRequest;
import process.ProcessStatus;
import process.swing.SwingProcessInput;
import process.swing.SwingProcessRunner;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import domain.DomainField;
import domain.DomainModel;
import domain.DomainSchemas;

/**
 * Consistency validation in the transform app — the front of the CURATE stage. It runs
 * over the WHOLE working schema the domain exposes (base classes, DERIVED classes and
 * facets), one type at a time. The coverage view IS the shared field-config table
 * ({@link ViewConfigEditor}) driven by a {@link FieldTableContributor}: per field it adds
 * {@code Coverage} / {@code Present} / {@code Missing} columns. Select a field to
 * drill into the task's missing, present, or full member scope — rendered with the shared {@link objectview.render.CardListView} /
 * {@link SearchPanel} (selectable, searchable cards), so a missing member IS the object,
 * ready for curation (a manual fill or a source enrichment such as "Check DBpedia").
 */
public final class ValidationPanel extends JPanel {

    static {
        objectview.field.RecordTypes.declare("Resolved name",
                objectview.field.RecordTypes.text("QID"),
                objectview.field.RecordTypes.text("Result"));
        objectview.field.RecordTypes.declare("Unresolved name",
                objectview.field.RecordTypes.text("QID"),
                objectview.field.RecordTypes.text("Result"));
        objectview.field.RecordTypes.declare("Unnamed reference",
                objectview.field.RecordTypes.text("QID"));
    }

    private final DomainModel domain;
    private final Map<String, List<Viewable>> byType = new LinkedHashMap<>();

    private final JComboBox<String> typeCombo = new JComboBox<>();
    private final JComboBox<ScopeFilter> scopeCombo =
            new JComboBox<>(ScopeFilter.values());
    private final FieldCoverageColumns coverageColumns;
    private final ViewConfigEditor coverage;
    private final JLabel status = new JLabel(" ");
    private final JLabel identityStatus = new JLabel(" ");
    private final JButton showIdentifiedButton = new JButton("Show identified");
    private final JButton showUnresolvedButton = new JButton("Show unresolved");
    // One persistent enrichment button, registered with the runner ONCE and reconfigured
    // + re-parented per drill — so drilling many fields doesn't leak run-button registrations.
    private final JButton checkButton = new JButton();
    private final JButton fieldSourceButton = new JButton("Choose Wikidata source…");
    private final JButton fieldSourceDbpediaButton = new JButton("Choose Wikipedia fallback…");
    private final JButton wikipediaEvidenceButton = new JButton("Find text evidence…");
    private final JLabel categoryRelationLabel = new JLabel();
    private final JButton exploreIdentityButton = new JButton("Explore Wikidata…");
    private final JButton cancelProcessButton = new JButton("Cancel Find Data");
    private final JButton resolveMissingButton = new JButton("Resolve identities…");
    private final JButton applyButton = new JButton("Save pending changes");
    /** What the last review accepted and saved. The review closes after acceptance,
     * so its own transient status line is never seen. */
    private final JLabel acceptedOutcome = new JLabel(" ");
    private final JLabel selectedLabel = new JLabel("No instance selected");
    private final JLabel batchTargetLabel = new JLabel("Batch actions: current scope");
    private final JTextField manualValue = new JTextField(18);
    private final JButton setValueButton = new JButton("Set / replace");
    private final JButton addValueButton = new JButton("Add to collection");
    private final JButton resolveNamesButton = new JButton("Resolve names…");
    private final JButton showUnnamedButton = new JButton("Show unnamed");

    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    // Horizontal, NOT vertical: the coverage table's preferred HEIGHT grows with the
    // field count, so a vertical split lets it starve the drill's card viewport to ~0
    // and nothing renders. Side-by-side (like the main workbench) keeps the drill full
    // height — the arrangement that reliably renders the virtualized cards.
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

    private String type;
    // The field selected in the shared field-config tree is both the population filter
    // and the curation target. Missing / present / all is an explicit, orthogonal scope.
    private String selectedFieldType;
    private String selectedFieldPath;
    private List<Viewable> instances = List.of();
    private Viewable selected;
    // A curation source rule has the same representation as a ModelBuilder field source.
    // It is session-scoped until explicitly promoted, while accepted values remain durable
    // per-instance Corrections. The qualified key prevents same-named fields from colliding.
    // Which source each field is read from — and the ORDER in which that is decided —
    // is FieldSourceChoices'; this panel shows it and takes the answer.
    private final FieldSourceChoices fieldSourceChoices;
    // A fallback is an additional route, not a replacement for the field's primary source.
    // Keeping both mappings in the same ModelBuilder representation lets Find Data try
    // Wikidata first and consult DBpedia only for members where that property is missing.
    // Pending edits are deliberately separate from ManualCuration. The staging object is
    // shared by every panel for the same sidecar, so modeless windows cannot accidentally
    // save one another's pending work or display contradictory Apply counts.
    private final CurationStaging staging;
    private final Runnable stagingListener = this::updateApplyButton;
    private final SwingQueryRunner queryRunner;
    private final SwingProcessRunner findDataRunner;
    // Re-render the owning view after an accepted enrichment writes a correction.
    private final Runnable onCurated;
    private final IdentityResolutionLauncher identityResolver;
    private final WorkbenchPurpose purpose;
    private List<Viewable> drilledInstances = List.of();
    private List<Viewable> selectedCards = List.of();
    // null = ordinary field-gap drill; true/false = identified/unresolved identity drill.
    private Boolean identityDrill;
    private boolean identityTask;
    private ScopeFilter scopeFilter = ScopeFilter.MISSING;
    // Set while the scope dropdown's model is being rebuilt, so repopulating it
    // does not fire the listener and re-run the drill mid-switch.
    private boolean syncingScopeChoices;
    // Resolves a fetched QID to the instance a reference field points at, creating one
    // when the pool has none. Rebuilt per drill so it sees the current pool.
    private DomainReferenceResolver referenceResolver;
    private long identityLabelRequest;
    private boolean wikipediaEvidenceOnly;
    /** Exact visible recipe chosen by the action tree. Null only in the full field
     *  editor, where the model's configured recipe inventory is shown separately. */
    private SourceBinding fixedCandidateEvidence;
    private boolean configuredValuesOnly;
    private JPanel fixedTargetSummary;
    private BooleanSupplier beforeLoad = () -> true;
    private java.util.function.Consumer<Boolean> onAcquisition = running -> { };
    private boolean acquisitionRunning;

    /** The concrete member used to discover a field source. Keep its readable name beside
     *  the QID so Explore makes the probe target unambiguous. */
    private record ExploreSeed(String qid, String label) {}

    /** Validate {@code instances} — the SHOWN view (filtered + grouped members of the
     *  selected class); null falls back to the whole domain pool. The domain is still
     *  used for schema, field union, and curation. {@code purpose} says which
     *  application's actions the pane offers. */
    public ValidationPanel(
            DomainModel domain,
            Collection<? extends Viewable> instances,
            SwingQueryRunner queryRunner,
            Runnable onCurated,
            IdentityResolutionLauncher identityResolver,
            WorkbenchPurpose purpose) {
        super(new BorderLayout(6, 6));
        this.domain = domain;
        this.fieldSourceChoices = new FieldSourceChoices(domain);
        this.onCurated = onCurated == null ? () -> { } : onCurated;
        this.identityResolver = identityResolver;
        this.purpose = java.util.Objects.requireNonNull(purpose);
        this.staging = CurationStaging.forCuration(curationStore());
        // Query clients have application lifetime and explicit ownership. A panel must use
        // its owner's runner rather than constructing endpoint clients it cannot close.
        this.queryRunner = java.util.Objects.requireNonNull(
                queryRunner, "ValidationPanel requires an application-owned query runner");
        this.findDataRunner = new SwingProcessRunner(
                this.queryRunner.context(),
                this.queryRunner.logListener(),
                process.ProcessInputHandler.unsupported());
        findDataRunner.registerCancelButton(cancelProcessButton);

        Collection<? extends Viewable> source =
                instances != null ? instances : domain.instances();
        for (String candidateType : domain.types()) {
            List<Viewable> members = membersOf(domain, source, candidateType);
            if (!members.isEmpty()) {
                byType.put(candidateType, members);
            }
        }

        // this.instances (the FIELD onType() fills with the selected type's members),
        // NOT the constructor parameter — which "Curate data" passes as null.
        coverageColumns = new FieldCoverageColumns(
                domain, () -> type, () -> this.instances);
        // The Unnamed count is actionable: pressing it selects that field and switches
        // to the scope those members live in, so the report and the repair are reached
        // from the same place instead of requiring the reader to know a filter exists.
        if (this.purpose.showsDatasourceActions()) {
            coverageColumns.onRepairNames(this::showUnnamedReferences);
        }
        coverage = new ViewConfigEditor(new ViewConfig(), (Viewable) null, coverageColumns);
        coverage.setChangeListener(this::onFieldSelected);

        checkButton.addActionListener(e -> onCheck());
        fieldSourceButton.addActionListener(e -> chooseSourceForSelected(false));
        resolveNamesButton.addActionListener(e -> resolveReferenceNames());
        fieldSourceDbpediaButton.setToolTipText(
                "Pick a Wikipedia infobox (DBpedia) property from a sample member — "
                        + "the fallback source for values Wikidata lacks");
        fieldSourceDbpediaButton.addActionListener(e -> chooseDbpediaSourceForSelected());
        wikipediaEvidenceButton.addActionListener(e -> findWikipediaEvidence());
        queryRunner.registerRunButton(checkButton);
        findDataRunner.registerRunButton(checkButton);
        findDataRunner.registerRunButton(wikipediaEvidenceButton);
        exploreIdentityButton.addActionListener(e -> exploreIdentity());
        exploreIdentityButton.setEnabled(false);
        applyButton.addActionListener(e -> applyStaged());
        applyButton.setEnabled(false);
        resolveMissingButton.addActionListener(e -> {
            List<Viewable> targets = actionInstances();
            if (this.identityResolver != null && !targets.isEmpty()) {
                this.identityResolver.resolve(
                        targets,
                        identityDrill != null
                                ? "Validate: unresolved " + type + " identities"
                                : "Validate: members missing " + selectedFieldType + "."
                                  + selectedFieldPath);
            }
        });
        // A button beside the identity ones, because that is how a scope is already
        // reached here. The dropdown carries the same choice, but a control nobody
        // finds is a control that does not exist.
        showUnnamedButton.setToolTipText(
                "Show the members whose reference shows a QID instead of a name");
        showUnnamedButton.addActionListener(e -> {
            if (selectedFieldPath == null) {
                JOptionPane.showMessageDialog(this,
                        "Select a field first — its Unnamed count says how many.");
                return;
            }
            showUnnamedReferences(FieldPath.parse(selectedFieldPath));
        });
        showIdentifiedButton.addActionListener(e -> showIdentityScope(ScopeFilter.PRESENT));
        showUnresolvedButton.addActionListener(e -> showIdentityScope(ScopeFilter.MISSING));
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
        scopeCombo.setSelectedItem(scopeFilter);
        scopeCombo.addActionListener(e -> {
            if (syncingScopeChoices) return;
            ScopeFilter chosen = (ScopeFilter) scopeCombo.getSelectedItem();
            if (chosen != null && chosen != scopeFilter) {
                scopeFilter = chosen;
                if (identityTask) showIdentityMembers(identityScope(chosen));
                else if (selectedFieldPath != null) onFieldSelected();
            }
        });

        // Wrapping, not plain FlowLayout: this bar carries eight controls, and in
        // BorderLayout.NORTH a plain FlowLayout reports one row's height while laying
        // out several — so the overflow is clipped and the user cannot tell the
        // controls exist. That is how the Values scope became unfindable.
        JPanel bar = new JPanel(
                new objectview.utils.swing.WrapLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JLabel("Type:"));
        bar.add(typeCombo);
        bar.add(new JLabel("Values:"));
        bar.add(scopeCombo);
        bar.add(status);
        if (this.purpose.showsDatasourceActions()) {
            bar.add(identityStatus);
            bar.add(showIdentifiedButton);
            bar.add(showUnresolvedButton);
            bar.add(showUnnamedButton);
        }
        // TransformApp may deliberately collect manual edits before saving them.
        // DataSourceApp's Stage action is already complete and shows its result in the
        // existing group/instances panel, so it exposes no second save/status step.
        // TransformApp deliberately collects manual edits before saving them. In
        // DataSourceApp an accepted review is already saved; the button appears there
        // only while a failed save left accepted changes pending, as the retry.
        bar.add(applyButton);
        bar.add(acceptedOutcome);
        if (this.purpose.showsDatasourceActions()) bar.add(cancelProcessButton);

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
        updateApplyButton();
    }

    // Split the space evenly once realized (setResizeWeight only governs *resize*
    // distribution, not the initial divider seeded from preferred sizes).
    @Override public void addNotify() {
        super.addNotify();
        if (staging != null) staging.addChangeListener(stagingListener);
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.5));
    }

    @Override public void removeNotify() {
        if (staging != null) staging.removeChangeListener(stagingListener);
        super.removeNotify();
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
        scopeCombo.setSelectedItem(filter);
        selectType(type);
        coverage.setSelectedPath(FieldPath.parse(field));
    }

    /** Turn this panel into the action half of curation for a target selected elsewhere.
     *  TransformApp's main workbench already chose the class, group, field and visible
     *  instances; repeating those selectors here creates two competing scope models.
     *  The supplied constructor population is therefore treated as the exact target and
     *  this surface retains only action configuration, instance choice and Apply. */
    public void useFixedTarget(String type, String field, String scopeDescription) {
        // The constructor already restricted the population to the visible instances, so
        // ALL here means all of that immutable target—not all members of the domain.
        selectField(type, field, ScopeFilter.ALL);

        remove(split);
        if (fixedTargetSummary != null) remove(fixedTargetSummary);
        JPanel summary = fixedTargetSummary = new JPanel(
                new objectview.utils.swing.WrapLayout(FlowLayout.LEFT, 6, 4));
        summary.add(new JLabel(purpose.showsDatasourceActions() ? "Loading:" : "Editing:"));
        summary.add(new JLabel(type + "." + field));
        if (scopeDescription != null && !scopeDescription.isBlank()) {
            summary.add(new JLabel("· " + scopeDescription));
        }
        summary.add(applyButton);
        summary.add(acceptedOutcome);
        if (purpose.showsDatasourceActions()) summary.add(cancelProcessButton);
        add(summary, BorderLayout.NORTH);
        add(instancesHolder, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /** The field editor already owns source configuration. This action reviews values only. */
    public void useConfiguredValueTarget(String type, String field,
            BooleanSupplier beforeLoad, java.util.function.Consumer<Boolean> running) {
        boolean sameTarget = configuredValuesOnly && type.equals(selectedFieldType)
                && field.equals(selectedFieldPath);
        configuredValuesOnly = true;
        this.beforeLoad = beforeLoad;
        onAcquisition = running;
        if (sameTarget) {
            // Applying a field-source edit changes the action, not the chosen cards.
            BorderLayout layout = (BorderLayout) instancesHolder.getLayout();
            var actions = layout.getLayoutComponent(BorderLayout.NORTH);
            if (actions != null) instancesHolder.remove(actions);
            showFieldActions();
            instancesHolder.revalidate();
            instancesHolder.repaint();
        } else {
            acceptedOutcome.setText("");
            useFixedTarget(type, field, "Select cards to limit the load to those instances");
        }
    }

    public void cancelFindData() {
        findDataRunner.cancel();
    }

    /** Present only the configured Wikipedia evidence operation. DataSourceApp uses
     * this when the reusable action tree has already selected the Wikipedia source, so
     * the detail pane does not repeat Wikidata and mixed-source actions under it. */
    public void showWikipediaEvidenceOnly() {
        wikipediaEvidenceOnly = true;
    }

    public void useFixedCandidateEvidence(SourceBinding binding) {
        fixedCandidateEvidence = binding;
    }

    /** Curate the provenance-declared identity: select the type, apply the scope filter, show the
     *  matching members in the identity drill. */
    public void selectIdentity(String type, ScopeFilter filter) {
        scopeFilter = filter;
        scopeCombo.setSelectedItem(filter);
        selectType(type);
        showIdentityMembers(identityScope(filter));
    }

    private static Boolean identityScope(ScopeFilter filter) {
        return filter == ScopeFilter.ALL ? null : filter == ScopeFilter.PRESENT;
    }

    /** The scopes a task can be filtered by. An identity is not a reference, so the
     *  unnamed-reference scope is a field-task scope only — offered there, and absent
     *  from the identity drill rather than silently coerced into "unresolved". */
    static ScopeFilter[] scopeChoices(boolean identityTask) {
        return identityTask
                ? new ScopeFilter[] {
                        ScopeFilter.MISSING, ScopeFilter.PRESENT, ScopeFilter.ALL }
                : ScopeFilter.values();
    }

    /** Rebuilds the scope dropdown for the current task, keeping the selection when it
     *  survives the change. Guarded so re-populating the model does not fire the
     *  listener and re-run the drill mid-switch. */
    private void syncScopeChoices() {
        ScopeFilter[] choices = scopeChoices(identityTask);
        if (scopeCombo.getItemCount() == choices.length) {
            return;
        }
        ScopeFilter keep = scopeFilter;
        syncingScopeChoices = true;
        try {
            scopeCombo.setModel(new javax.swing.DefaultComboBoxModel<>(choices));
            boolean stillOffered = java.util.Arrays.asList(choices).contains(keep);
            scopeFilter = stillOffered ? keep : ScopeFilter.MISSING;
            scopeCombo.setSelectedItem(scopeFilter);
        } finally {
            syncingScopeChoices = false;
        }
    }

    private void showIdentityScope(ScopeFilter filter) {
        identityTask = true;
        syncScopeChoices();
        scopeFilter = filter;
        scopeCombo.setSelectedItem(filter);
        showIdentityMembers(identityScope(filter));
    }

    private void onType() {
        type = (String) typeCombo.getSelectedItem();
        instances = type == null ? List.of() : byType.getOrDefault(type, List.of());
        selected = null;
        drilledInstances = List.of();
        selectedCards = List.of();
        identityDrill = null;
        identityTask = false;
        syncScopeChoices();
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
        coverage.setClassBranches(DomainSchemas.classBranches(domain, type));
        status.setText("   " + instances.size() + " " + (type == null ? "" : type)
                               + " instance(s)");
        updateIdentityStatus();
    }

    /** SELECT stage — (re)build the drilled instance set from the field + scope, then render.
     *  Called on field/scope change and after an explicit Apply (so approved items refresh
     *  out of the missing scope). */
    private void onFieldSelected() {
        ScopedField scoped = scopedField(coverage.selectedPath());
        String path = scoped == null ? null : scoped.path();
        selectedFieldType = scoped == null ? null : scoped.type();
        selectedFieldPath = path;
        identityDrill = null;
        identityTask = false;
        if (path == null) {
            selectedChanged(null);
            instancesHolder.removeAll();
            instancesHolder.add(new JLabel(
                                        "   Select a field with a gap to drill into its missing members."),
                                BorderLayout.NORTH);
            instancesHolder.revalidate();
            instancesHolder.repaint();
            return;
        }
        drilledInstances = List.copyOf(scoped == null ? List.of()
                                               : membersWithFieldScope(
                domain, instances, scoped.type(), path, scopeFilter));
        // Over the WHOLE domain, not the drilled scope: a location already pooled under
        // another film must be linked, not created a second time.
        referenceResolver = new DomainReferenceResolver(domain.instances());
        renderFieldDrill();
    }

    /** Render the field drill over the CURRENT drilledInstances (no re-filter), so a
     *  just-staged value keeps its instance visible until Apply. */
    private void renderFieldDrill() {
        selectedCards = List.of();
        updateBatchTargetLabel();
        selectedChanged(null);
        instancesHolder.removeAll();
        showFieldActions();
        List<Viewable> matching = drilledInstances;
        if (!matching.isEmpty()) {
            instancesHolder.add(instancesView(matching, selectedFieldType), BorderLayout.CENTER);
        }
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    private void showFieldActions() {
        instancesHolder.add(scrollableActions(
                header(selectedFieldType, selectedFieldPath, drilledInstances.size())),
                BorderLayout.NORTH);
    }

    /** The source/action stack can be taller than the fixed-target curation pane. Keep it
     *  in normal layout flow with a bounded viewport instead of letting BorderLayout.NORTH
     *  clip its lower blocks over the card browser. */
    private static JComponent scrollableActions(JComponent actions) {
        JScrollPane scroll = new JScrollPane(actions,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        java.awt.Dimension preferred = actions.getPreferredSize();
        scroll.setPreferredSize(new java.awt.Dimension(
                preferred.width, Math.min(300, preferred.height + 4)));
        return scroll;
    }

    /** Identity coverage is a convenience shortcut beside the ordinary
     * configurable provenance field. Drill either side into the same
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
        // Filter into the drilled set ONCE, when the drill is opened / scope changes. A
        // subsequent approval re-renders this same set (renderIdentityDrill) rather than
        // re-filtering, so a just-resolved instance stays visible with its source filled.
        drilledInstances = List.copyOf(instances.stream()
                                                .filter(member -> identified == null || hasIdentity(member) == identified)
                                                .toList());
        renderIdentityDrill();
    }

    /** Render the identity drill over the CURRENT drilledInstances (no re-filter). */
    private void renderIdentityDrill() {
        selectedCards = List.of();
        updateBatchTargetLabel();
        selectedChanged(null);
        Boolean identified = identityDrill;
        List<Viewable> matching = drilledInstances;
        instancesHolder.removeAll();

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        header.add(new JLabel(matching.size() + " " + type + " instance(s) "
                                      + (identified == null ? "with any identity state"
                : identified ? "with a Wikidata identity" : "without a Wikidata identity")));
        if (Boolean.FALSE.equals(identified) && identityResolver != null && !matching.isEmpty()) {
            resolveMissingButton.setText("Resolve " + matching.size() + " identities…");
            header.add(resolveMissingButton);
        }
        header.add(exploreIdentityButton);
        instancesHolder.add(header, BorderLayout.NORTH);
        if (!matching.isEmpty()) {
            // Identity rides in each card's header as a chip (instancesView's cardDecorator),
            // and card selection sets the target — no separate identity index.
            instancesHolder.add(instancesView(matching, type), BorderLayout.CENTER);
        }
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    /** Recovery/collected-edit save. Reviewed results save during acceptance; this button
     * remains for deliberately collected manual edits and a failed acceptance save. */
    private void updateApplyButton() {
        int n = staging == null ? 0 : staging.size();
        applyButton.setEnabled(n > 0);
        // Reviewed results have already been accepted and saved. This control is only
        // for manual edits deliberately collected here, or recovery after a failed save;
        // where nothing is collected by hand it shows only when there is something to retry.
        String label = saveButtonLabel(purpose.showsTransformActions(), n);
        applyButton.setVisible(label != null);
        if (label != null) applyButton.setText(label);
        applyButton.setToolTipText(n == 0
                                           ? "No pending changes"
                                           : "Retry saving the accepted changes");
    }

    /** The pending-changes button: always offered where manual edits are collected by
     * hand (TransformApp); elsewhere only as the retry after a failed acceptance save.
     * Null means the button is not shown. */
    static String saveButtonLabel(boolean collectsManualEdits, int pending) {
        if (collectsManualEdits) return "Save pending changes";
        if (pending <= 0) return null;
        return "Retry saving " + pending + " accepted change" + (pending == 1 ? "" : "s");
    }

    private boolean hasIdentity(Viewable member) {
        return identityQid(member) != null;
    }

    private String identityQid(Viewable member) {
        String anchored = SourceIdentities.wikidataQid(member);
        if (anchored != null) return anchored;
        // A just-approved identity is staged (not yet in the durable curation), so the
        // preview must read pending links too — otherwise the resolved instance shows blank.
        String staged = stagedQid(member);
        if (staged != null) return staged;
        return resolvedQid(member, EnrichmentSources.collect(
                member, concreteType(member), curationStore()));
    }

    /** The Wikidata QID of a pending (staged, not yet applied) identity for {@code member}.
     *  Matching is {@link IdentityLinks}' job — a link written under the
     *  instance's base class must still be found once it carries a subclass or a role. */
    private String stagedQid(Viewable member) {
        if (staging == null || member == null) return null;
        return staging.identityLinks().stream()
                      .filter(l -> IdentityLinks.matches(l, member, IdentityLinks.WIKIDATA))
                      .map(IdentityLink::sourceId)
                      .filter(ValidationPanel::isQid)
                      .findFirst().orElse(null);
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

    /** Pure population rule shared by the UI and regression tests. */
    static List<Viewable> membersWithFieldScope(
            DomainModel domain,
            Collection<? extends Viewable> source,
            String ownerType,
            String path,
            ScopeFilter filter) {
        return FieldCoverageColumns.select(
                domain, source, ownerType, FieldPath.parse(path), filter);
    }


    private record ScopedField(String type, String path) { }

    /** A ModelBuilder-compatible field source is qualified by its owning class. */
    private record FieldKey(String type, String path) { }

    /** ViewConfigEditor represents a subtype heading with an internal path segment
     * such as {@code @subtype:USState.admissionDate}. Strip those presentation-only
     * segments and retain the deepest subtype as the field's coverage scope. */
    private ScopedField scopedField(FieldPath rawPath) {
        FieldCoverageColumns.Scoped s = FieldCoverageColumns.scoped(type, rawPath);
        return s == null ? null : new ScopedField(s.type(), s.path().dotted());
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Viewable> sampleClass(Viewable q) {
        return (Class<? extends Viewable>) q.getClass();
    }


    private JComponent header(String fieldType, String path, int count) {
        JPanel h = new JPanel();
        h.setLayout(new javax.swing.BoxLayout(h, javax.swing.BoxLayout.Y_AXIS));

        h.add(headerLine(new JLabel(count + " " + scopeDescription(scopeFilter)
                                            + " for " + fieldType + "." + path)));
        if (count == 0) return h;
        // Identity is no longer a stray "Resolve identities" line here — it lives inside
        // the Wikidata block below, where it belongs (identity is a Wikidata-source fact).
        populateCurateActions(h);
        return h;
    }

    private static JPanel headerLine(java.awt.Component... components) {
        JPanel line = new JPanel(
                new objectview.utils.swing.WrapLayout(FlowLayout.LEFT, 8, 2));
        line.setAlignmentX(LEFT_ALIGNMENT);
        for (java.awt.Component component : components) line.add(component);
        return line;
    }

    private static String scopeDescription(ScopeFilter filter) {
        return switch (filter) {
            case MISSING -> "instance(s) with a missing value";
            case PRESENT -> "instance(s) with an existing value";
            case UNNAMED_REFERENCE -> "instance(s) whose reference has no name";
            case ASSERTED_EMPTY -> "instance(s) the source reports unknown / none";
            case OVERFILLED_SINGLE ->
                    "instance(s) holding several values in a single-valued field";
            case ALL -> "instance(s)";
        };
    }

    /** Curate actions ORGANISED BY DATA SOURCE: what to fetch is configured per source
     *  (Wikidata: its identity + the property; Wikipedia: the fallback infobox property),
     *  then one "Load values" triggers the fetch. Manual entry is a separate by-hand path.
     *  A reference field is out of scope for now. */
    private void populateCurateActions(JPanel target) {
        DomainField field = selectedDomainField();
        if (configuredValuesOnly) {
            target.add(headerLine(batchTargetLabel));
            var key = new FieldKey(selectedFieldType, selectedFieldPath);
            checkButton.setText("Load values ↗");
            boolean supported = configuredRouteAvailable(field, key);
            checkButton.setEnabled(!acquisitionRunning && !actionInstances().isEmpty()
                    && supported);
            target.add(new WorkbenchActionList(List.of(new WorkbenchActionList.Item(checkButton,
                    supported ? "Fetch values from the applied field source and review the results."
                            : "Load values supports direct Wikidata properties without qualifier "
                                    + "or language restrictions, Wikipedia infobox values, "
                                    + "and DBpedia properties."))));
            return;
        }
        boolean media = field != null && field.kind() == objectview.field.FieldKind.MEDIA;
        boolean sourceActions = purpose.showsDatasourceActions();
        boolean localActions = purpose.showsTransformActions();

        java.util.List<SourceBinding> evidenceRecipes = candidateEvidenceBindings(
                selectedFieldType, selectedFieldPath);
        WikipediaTextEvidenceProvider.CandidateEvidenceMode candidateMode =
                configuredCandidateEvidence(selectedFieldType, selectedFieldPath);
        WikipediaDatasourceProvider.ArticleTableColumnLinkSpec tableColumnSpec =
                configuredArticleTableColumnLinks(selectedFieldType, selectedFieldPath);
        WikipediaDatasourceProvider.ArticleTableRowInstanceSpec tableRowSpec =
                configuredArticleTableRowInstances(selectedFieldType, selectedFieldPath);
        WikipediaDatasourceProvider.ArticlePassageSpec passageSpec =
                configuredArticlePassageSpec(selectedFieldType, selectedFieldPath);
        boolean configuredEvidence = candidateMode
                != WikipediaTextEvidenceProvider.CandidateEvidenceMode.NONE
                || tableColumnSpec != null;
        boolean articlePassages = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode.ARTICLE_PASSAGES;
        boolean articlePassageInstances = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode.ARTICLE_PASSAGE_INSTANCES;
        boolean articleTableRowInstances = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode
                        .ARTICLE_TABLE_ROW_INSTANCES;
        boolean infoboxRowLinks = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode.INFOBOX_ROW_LINKS;
        boolean tableColumnLinks = tableColumnSpec != null;

        List<WorkbenchActionList.Item> actions = new ArrayList<>();
        JPanel actionListHolder = new JPanel(new BorderLayout());
        actionListHolder.setAlignmentX(LEFT_ALIGNMENT);

        batchTargetLabel.setToolTipText(
                "Click one card, or Ctrl/Cmd-click several cards, to limit batch actions");
        if (sourceActions) {
            target.add(headerLine(batchTargetLabel));
            // Reserve the first block for the complete action list. The actions are
            // collected while the source descriptions below are assembled, but they
            // remain visible before those details rather than being buried after them.
            target.add(actionListHolder);
        }

        // Wikidata: identity is datasource-dependent, so it sits with the Wikidata source;
        // the property is what yields this field's value.
        JPanel wikidata = titledBlock("Wikidata");
        if (!wikipediaEvidenceOnly && identityResolver != null) {
            resolveMissingButton.setText("Identities…");
            resolveMissingButton.setToolTipText(
                    "Resolve or change the Wikidata identity of these instances "
                            + "(needed before a value can be fetched)");
            wikidata.add(headerLine(new JLabel("Identity: resolved separately when needed")));
            actions.add(new WorkbenchActionList.Item(resolveMissingButton,
                    "Resolve or change the Wikidata identity used by datasource operations."));
        }
        if (!wikipediaEvidenceOnly && !media) {
            updateFieldSourceButton();   // "Source: population (P1082)…" or "Choose Wikidata source…"
            makeSourceBindingsReadOnly();
            wikidata.add(headerLine(new JLabel("Property"), fieldSourceButton));
        }
        if (sourceActions && !wikipediaEvidenceOnly) target.add(wikidata);

        if (sourceActions && !media) {
            // Wikipedia (DBpedia) fallback: consulted only where Wikidata has no value.
            // Media has no text-property fallback (an image comes from P18, not an infobox
            // property you pick), so the fallback block is data-only.
            JPanel fallback = titledBlock("Wikipedia fallback");
            fallback.add(headerLine(new JLabel("Infobox property"), fieldSourceDbpediaButton));
            JLabel why = new JLabel("<html>Samples a member's Wikipedia infobox (reached via its "
                    + "Wikidata <i>sameAs</i>) and lists its properties; pick the one that holds "
                    + "<b>" + selectedFieldType + "." + selectedFieldPath + "</b>. Consulted only "
                    + "for members Wikidata leaves empty.</html>");
            why.setEnabled(false);
            fallback.add(headerLine(why));
            if (!wikipediaEvidenceOnly) target.add(fallback);

            JPanel articleEvidence = titledBlock("Wikipedia article evidence");
            makeCategoryRelationReadOnly();
            articleEvidence.add(headerLine(new JLabel("Category relation"),
                    categoryRelationLabel));
            wikipediaEvidenceButton.setText(articleTableRowInstances
                    ? "Find table-row instances…"
                    : articlePassageInstances ? "Find passage instances…"
                    : articlePassages ? "Find " + passageSpec.description() + "…"
                    : infoboxRowLinks ? "Find infobox link evidence…"
                    : tableColumnLinks ? "Find table-column link evidence…"
                    : "Find text evidence…");
            wikipediaEvidenceButton.setToolTipText(
                    configuredEvidence
                            ? "Find configured Wikipedia evidence; every result keeps the "
                                    + "article revision and exact passages for review"
                            : "Find exact mentions supporting values already present in this "
                                    + "field; results keep the article revision and excerpt "
                                    + "for review");
            articleEvidence.add(headerLine(new JLabel(articleTableRowInstances
                            ? "Proposed " + tableRowSpec.targetClass()
                                    + " instances from configured article table rows"
                            : articlePassageInstances
                            ? "Proposed instances from bounded article passages"
                            : articlePassages ? "Bounded " + passageSpec.description()
                            : infoboxRowLinks ? "Loaded targets linked from an infobox row"
                            : tableColumnLinks
                            ? "Loaded targets linked from an article table column"
                            : configuredEvidence ? "Existing values and loaded candidates"
                            : "Existing values")));
            TableRowEvidenceSummary tableSummary = articleTableRowInstances
                    ? tableRowEvidenceSummary(actionInstances(), selectedFieldPath, tableRowSpec)
                    : TableRowEvidenceSummary.EMPTY;
            JLabel evidenceWhy = new JLabel(
                    articleTableRowInstances
                            ? "<html>" + tableSummary.description(tableRowSpec)
                                    + " Existing linked members remain; every unrepresented "
                                    + "row proposes one <b>" + tableRowSpec.targetClass()
                                    + "</b>, including a row whose display cell has no "
                                    + "Wikipedia link.</html>"
                            : articlePassageInstances
                            ? "<html>Reads each selected member's full article and proposes one "
                                    + "target-class instance per bounded passage. Each passage "
                                    + "is reviewed separately; none is bulk-accepted.</html>"
                            : infoboxRowLinks
                            ? "<html>Reads links already retained in the configured rendered "
                                    + "infobox row and proposes only already-loaded instances "
                                    + "of the field's target class. It creates no identities.</html>"
                            : tableColumnLinks
                            ? "<html>Reads one configured section/table/column from each "
                                    + "selected member's structured article and proposes only "
                                    + "already-loaded instances of the field's target class. "
                                    + "It creates no identities.</html>"
                            : candidateMode == WikipediaTextEvidenceProvider
                            .CandidateEvidenceMode.ARTICLE_PASSAGES
                            ? "<html>Reads each selected member's full article and proposes at "
                                    + "most 40 " + passageSpec.description()
                                    + " / 20,000 characters as one "
                                    + "reviewable text value. It infers no entities or events.</html>"
                            : configuredEvidence
                            ? "<html>Corroborates current values and proposes already-loaded "
                                    + "instances of the declared target class mentioned in the "
                                    + "retained introduction. It creates no identities.</html>"
                            : "<html>Corroborates current values from versioned article text. "
                                    + "It does not infer arbitrary missing fields.</html>");
            evidenceWhy.setEnabled(false);
            articleEvidence.add(headerLine(evidenceWhy));
            // The full curation editor shows the source/configuration block. In the
            // embedded datasource action view the action list above already names the
            // exact operation, scope and effect; repeating this block below it turns the
            // compact action pane into a clipped copy of the full editor.
            if (!wikipediaEvidenceOnly) target.add(articleEvidence);

            if (fixedCandidateEvidence != null || evidenceRecipes.size() <= 1) {
                actions.add(new WorkbenchActionList.Item(wikipediaEvidenceButton,
                        articleTableRowInstances
                                ? tableSummary.actionDescription(tableRowSpec)
                                : "Run the configured Wikipedia evidence operation and review "
                                        + "its proposals before staging them."));
            } else {
                for (SourceBinding recipe : evidenceRecipes) {
                    JButton runRecipe = new JButton("Find "
                            + WikipediaEvidenceRecipes.describe(recipe) + "…");
                    runRecipe.addActionListener(event -> findWikipediaEvidence(recipe));
                    findDataRunner.registerRunButton(runRecipe);
                    actions.add(new WorkbenchActionList.Item(runRecipe,
                            "Run this configured recipe only, once for every instance in "
                                    + "the current scope."));
                }
            }
        }

        // Repairing a reference's NAME is not the same job as filling the field: the
        // value is already right, only its target has no label. So it is offered
        // wherever the selected field HAS such references — gating it behind the
        // matching scope hid the cure from the column that reports the disease.
        int repairable = unnamedTargets().size();
        if (sourceActions && !wikipediaEvidenceOnly && repairable > 0) {
            JPanel names = titledBlock("Names");
            resolveNamesButton.setText("Resolve " + repairable + " name"
                                               + (repairable == 1 ? "" : "s") + "…");
            resolveNamesButton.setToolTipText(
                    "Fetch the missing labels for the referenced entities in this scope");
            // Beside the repair, in the drill panel the reader is already looking at.
            // The same action exists in the toolbar and on the field row, and both were
            // missed twice — an affordance is only real where the eye already is.
            JButton showThese = new JButton("Show the " + repairable + " member"
                                                    + (repairable == 1 ? "" : "s") + "…");
            showThese.setToolTipText(
                    "Drill the members whose " + selectedFieldPath + " shows a QID");
            showThese.addActionListener(e ->
                    showUnnamedReferences(FieldPath.parse(selectedFieldPath)));
            names.add(headerLine(new JLabel(
                    "References showing a QID instead of a name:"), showThese));
            target.add(names);
            actions.add(new WorkbenchActionList.Item(resolveNamesButton,
                    "Fetch labels for the " + repairable
                            + " referenced member" + (repairable == 1 ? "" : "s") + "."));
        }

        // Load is available for MEDIA too: findData fetches images per entity from their
        // media provider (no property to pick), so an image field is fillable, not inert.
        checkButton.setText("Load values ↗");
        checkButton.setToolTipText(media
                ? "Fetch images for these members from Wikidata (P18) / Wikimedia Commons"
                : "Fetch values for these members from the configured source(s)");
        if (sourceActions && !wikipediaEvidenceOnly) {
            actions.add(new WorkbenchActionList.Item(checkButton, media
                    ? "Fetch images from Wikidata (P18) and Wikimedia Commons."
                    : "Fetch this field from its configured primary and fallback sources."));
        }
        if (sourceActions) {
            actionListHolder.add(new WorkbenchActionList(actions), BorderLayout.CENTER);
        }

        // The by-hand path, kept visually separate from the source-driven load.
        if (localActions) {
            JPanel manual = titledBlock("Set manually");
            manual.setName("local.field.actions");
            manual.add(headerLine(new JLabel("Selected:"), selectedLabel));
            if (!media) {
                addValueButton.setVisible(field != null && field.collection());
                manual.add(headerLine(manualValue, setValueButton, addValueButton));
            }
            target.add(manual);
        }

        target.revalidate();
        target.repaint();
    }

    record TableRowEvidenceSummary(int rows, int currentMembers, int retainedArticles) {
        static final TableRowEvidenceSummary EMPTY = new TableRowEvidenceSummary(0, 0, 0);

        String description(WikipediaDatasourceProvider.ArticleTableRowInstanceSpec spec) {
            if (retainedArticles == 0) {
                return "Reads every data row in <b>" + spec.description()
                        + "</b>. The field currently has <b>" + currentMembers + " member"
                        + (currentMembers == 1 ? "" : "s") + "</b>. The structured article "
                        + "is not retained yet and will be fetched by the run.";
            }
            return "The retained article contains <b>" + rows
                    + " configured table row" + (rows == 1 ? "" : "s") + "</b>; the field "
                    + "currently has <b>" + currentMembers + " member"
                    + (currentMembers == 1 ? "" : "s") + "</b>.";
        }

        String actionDescription(WikipediaDatasourceProvider.ArticleTableRowInstanceSpec spec) {
            if (retainedArticles == 0) {
                return "Read every data row in " + spec.description()
                        + "; keep the " + currentMembers + " current member"
                        + (currentMembers == 1 ? "" : "s")
                        + " and propose each unrepresented row for review.";
            }
            return "Read " + rows + " configured table row" + (rows == 1 ? "" : "s")
                    + "; keep the " + currentMembers + " current member"
                    + (currentMembers == 1 ? "" : "s")
                    + " and propose every unrepresented row for review.";
        }
    }

    static TableRowEvidenceSummary tableRowEvidenceSummary(
            Collection<? extends Viewable> owners,
            String field,
            WikipediaDatasourceProvider.ArticleTableRowInstanceSpec spec) {
        if (spec == null || !spec.configured()) return TableRowEvidenceSummary.EMPTY;
        int rows = 0;
        int members = 0;
        int articles = 0;
        for (Viewable owner : owners == null ? List.<Viewable>of() : owners) {
            Object current = objectview.field.FieldAccess.getPath(owner, field);
            if (current instanceof Collection<?> values) members += values.size();
            else if (current != null) members++;
            WikipediaArticle article = owner instanceof
                    domain.runtime.WikidataDynamicObject wikidata
                    ? wikidata.wikipediaArticle() : null;
            if (article == null) continue;
            articles++;
            rows += WikipediaTableRows.select(article, spec.sectionPath(), spec.table(),
                    spec.displayColumn()).rows().size();
        }
        return new TableRowEvidenceSummary(rows, members, articles);
    }

    private static JPanel titledBlock(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(javax.swing.BorderFactory.createTitledBorder(title));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    /** How a chosen field is curated: provenance identity → Explore, an ordinary
     *  data field (Find Data / manual value), or a reference (entity-valued — out of scope for
     *  now). Identity is matched first, so the provenance {@code source} object — itself a
     *  reference — routes to Explore, not the reference dead-end. */
    private enum CurateType { IDENTITY, DATA, REFERENCE, NONE }

    private CurateType curateTypeOf(String type, String path) {
        if (path == null) return CurateType.NONE;
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
        if (acquisitionRunning || !beforeLoad.getAsBoolean()) return;
        String path = selectedFieldPath;
        if (path == null) return;
        ManualCuration curation = curationStore();
        // Let a manually identified selected member establish its source before the
        // batch. Other unresolved manual members are skipped, not met with a dialog storm.
        if (!configuredValuesOnly && selected != null && EnrichmentSources.needsSelection(
                selected, concreteType(selected), curation)
                && !isQid(selected.getIdentifier())) {
            // Resolve the identity first (the explorer is modal), then continue into the
            // batch if the selected entity was approved and saved.
            exploreIdentity();
        }
        DomainField drilled = domain.fields(selectedFieldType).stream()
                                    .filter(f -> path.equals(f.field()))
                                    .findFirst().orElse(null);
        boolean media = drilled == null || drilled.kind() == objectview.field.FieldKind.MEDIA;

        FieldKey key = new FieldKey(selectedFieldType, path);
        if (configuredValuesOnly && !configuredRouteAvailable(drilled, key)) return;
        if (!configuredValuesOnly && !media && sourceFor(key) == null
                && fallbackSourceFor(key) == null) {
            if (chooseSourceForSelected(true)) return;
            // No route is configured and no member resolves to a Wikidata entity yet —
            // fall through so runFillBatch reports that identities must be resolved first.
        }
        if (!confirmFill(key, media)) {
            return;
        }
        runFillBatch(path);
    }

    private boolean configuredRouteAvailable(DomainField field, FieldKey key) {
        if (field == null || curationStore() == null) return false;
        var schema = DomainSchemas.resolve(domain, key.type(), key.path());
        if (schema == null || schema.valueKind() == objectview.field.FieldKind.MEDIA) return false;
        FieldSourceMapping primary = sourceFor(key);
        FieldSourceMapping fallback = fallbackSourceFor(key);
        if (primary == null && declaredBinding(SourceBindingSlot.PRIMARY_FIELD_VALUE) != null
                || fallback == null && declaredBinding(SourceBindingSlot.FALLBACK_FIELD_VALUE) != null) {
            return false;
        }
        return (primary != null || fallback != null)
                && (primary == null || FieldEnrichmentRoutes.canReadConfiguredValue(primary))
                && (fallback == null || FieldEnrichmentRoutes.canReadConfiguredValue(fallback));
    }

    private java.util.function.Predicate<String> confirmLoad = plan -> JOptionPane.showConfirmDialog(
            this, new JLabel(plan), "Load values", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;

    void loadConfirmation(java.util.function.Predicate<String> confirmation) {
        confirmLoad = confirmation;
    }

    /**
     * Shows what the load will do before it does it: which members, from which source,
     * and where the values will land.
     *
     * <p>The run reaches an external service once per member and can take minutes over
     * a large scope, so starting it is a decision — and the source it will use may have
     * been seeded from the model rather than chosen here, which makes it worth stating
     * rather than assuming the user knows.
     */
    private boolean confirmFill(FieldKey key, boolean media) {
        FieldSourceMapping source = media ? null : sourceFor(key);
        FieldSourceMapping fallback = media ? null : fallbackSourceFor(key);

        StringBuilder plan = new StringBuilder("<html><b>Load values for ")
                .append(key.type()).append('.').append(key.path())
                .append("</b><br><br>");
        List<Viewable> targets = actionInstances();
        plan.append("Members: <b>").append(targets.size()).append("</b> ")
            .append(selectedCards.isEmpty() ? scopeDescription(scopeFilter)
                    : "selected card(s)").append("<br>");
        if (media) {
            plan.append("Source: Wikidata image (P18) / Wikimedia Commons<br>");
        } else {
            plan.append("Primary source: ").append(describe(source)).append("<br>");
            plan.append("Fallback source: ").append(fallback == null
                    ? "none" : describe(fallback)).append("<br>");
        }
        plan.append("<br>Fetch values for eligible members and show them for review.</html>");

        return confirmLoad.test(plan.toString());
    }

    private static String describe(FieldSourceMapping source) {
        if (source == null || source.propertyPid() == null
                || source.propertyPid().isBlank()) {
            return "no property configured";
        }
        String label = source.propertyLabel() == null || source.propertyLabel().isBlank()
                ? "" : source.propertyLabel() + " ";
        String direction = source.direction() == RuleDirection.ITEM_TO_ROOT
                ? " (incoming)" : "";
        return label + "(" + source.propertyPid() + ")" + direction;
    }

    /**
     * Fetches the missing labels for the references in the current drill and stages one
     * correction per named entity.
     *
     * <p>The correction lands on the TARGET, not on the film that points at it: the name
     * belongs to the location, and one label fixes it for every instance referring to it.
     * Writing it per referring instance would store the same fact hundreds of times and
     * still leave the entity unnamed anywhere else it appears.
     */
    private void resolveReferenceNames() {
        java.util.LinkedHashSet<String> unnamed = unnamedTargets();
        if (unnamed.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Every reference in this scope has a name.");
            return;
        }
        showResolveNamesPlan(unnamed);
    }

    /** Preview the exact unnamed entities before any requests begin. The preview uses the
     *  same virtualized Viewable card path as the rest of TransformApp. */
    private void showResolveNamesPlan(java.util.LinkedHashSet<String> unnamed) {
        List<Viewable> targets = unnamed.stream().map(this::unnamedTargetCard).toList();
        String field = selectedFieldType + "." + selectedFieldPath;
        ResolveNamesProcess resolveProcess =
                new ResolveNamesProcess(unnamed, field);
        process.swing.workflow.ProcessWorkflowAction<
                ResolveNamesProcess.Result,
                ResolveNamesProcess.Name> action =
                new process.swing.workflow.ProcessWorkflowAction<>() {
                    @Override public String id() { return "resolve-reference-names"; }
                    @Override public String subject() {
                        return field + " " + String.join(" ", unnamed);
                    }
                    @Override public process.swing.workflow.ProcessWorkflowPlan plan() {
                        return new process.swing.workflow.ProcessWorkflowPlan(
                                "Resolve names", "Fetch names for exactly these referenced entities.",
                                List.of(new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                        "Will query", targets)));
                    }
                    @Override public process.Process<ResolveNamesProcess.Result>
                    process() { return resolveProcess; }
                    @Override public process.swing.workflow.ProcessWorkflowResults<
                            ResolveNamesProcess.Name> results(
                                    process.ProcessOutcome<ResolveNamesProcess.Result>
                                            outcome) {
                        List<process.swing.workflow.ProcessWorkflowResults.Card<
                                ResolveNamesProcess.Name>> resolved =
                                new ArrayList<>();
                        List<process.swing.workflow.ProcessWorkflowResults.Card<
                                ResolveNamesProcess.Name>> unresolved =
                                new ArrayList<>();
                        for (String qid : outcome.result().requested()) {
                            String label = outcome.result().resolved().get(qid);
                            boolean found = label != null && !label.isBlank()
                                    && !label.equals(qid);
                            DynamicViewable card =
                                    new DynamicViewable(qid, found ? label : qid);
                            card.type(found ? "Resolved name" : "Unresolved name");
                            card.put("QID", qid);
                            card.put("Result", found ? label : "No name returned");
                            var resultCard = new process.swing.workflow.ProcessWorkflowResults.Card<>(
                                    card, found
                                            ? () -> new ResolveNamesProcess.Name(
                                                    qid, label)
                                            : () -> null,
                                    found);
                            (found ? resolved : unresolved).add(resultCard);
                        }
                        return new process.swing.workflow.ProcessWorkflowResults<>(
                                "Resolve names — results", outcome.summary(),
                                // Staging, not saving: the toolbar's Save is what
                                // writes, and a button reading "Apply" here made the
                                // two indistinguishable.
                                "Stage", List.of(
                                new process.swing.workflow.ProcessWorkflowResults.Tab<>(
                                        "Resolved", resolved),
                                new process.swing.workflow.ProcessWorkflowResults.Tab<>(
                                        "Unresolved", unresolved)));
                    }
                    @Override public void apply(
                            List<ResolveNamesProcess.Name> decisions) {
                        stageNames(decisions);
                    }
                };
        process.swing.workflow.SwingProcessWorkflow.start(
                this, findDataRunner, action);
    }

    private Viewable unnamedTargetCard(String qid) {
        Viewable existing = domain.instances().stream()
                .filter(candidate -> qid.equals(candidate.getIdentifier()))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        DynamicViewable target =
                new DynamicViewable(qid, qid);
        target.type("Unnamed reference");
        target.put("QID", qid);
        return target;
    }

    /** Every QID the drilled members' selected field still shows instead of a name.
     *  QIDs, not instances: after the product compiler a collapsed reference IS a
     *  string, and the label is recorded against the entity's QID either way. */
    private java.util.LinkedHashSet<String> unnamedTargets() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (selectedFieldPath == null || selectedFieldType == null) return out;
        FieldPath path = FieldPath.parse(selectedFieldPath);
        boolean entityOrigin = domain.entityOrigin(selectedFieldType, path);
        // Over the TYPE's members, not the drilled scope: the count then matches the
        // Unnamed column, and the action is available from any scope — a name is
        // repaired on the entity, so which films are currently listed is irrelevant.
        for (Viewable member : instances) {
            if (!domain.isInstanceOf(member, selectedFieldType)) continue;
            out.addAll(FieldCoverageColumns.unnamedReferences(member, path, entityOrigin));
        }
        return out;
    }

    /** Stages the names the reader accepted. One correction per ENTITY, so it fixes
     *  every instance referring to it. */
    private void stageNames(List<ResolveNamesProcess.Name> accepted) {
        if (staging == null || accepted == null) return;
        int staged = 0;
        for (ResolveNamesProcess.Name name : accepted) {
            if (name.label() == null || name.label().isBlank()
                    || name.label().equals(name.qid())) {
                continue;
            }
            staging.stage(Correction.entityLabel(name.qid(), name.label(), "wikidata"));
            staged++;
        }
        // Preview the detached label overlay in the same live pool coverage inspects.
        // Without this, cards could show the reviewed labels while the cached Unnamed
        // column and Names block continued reporting the pre-review count.
        Corrections.apply(domain.instances(), List.of(staging));
        renameCollapsedReferences(accepted);
        refreshCoverage();
        int remaining = unnamedTargets().size();
        status.setText("Staged " + staged + " name(s); " + remaining
                + " unnamed remain; Apply to save.");
        updateApplyButton();
        onCurated.run();
        if (selectedFieldPath != null) renderFieldDrill();
    }

    /**
     * Replaces a just-named QID where it survives as a COLLAPSED COPY.
     *
     * <p>Renaming the entity is not enough on a compiled domain: ProductCompiler turns a
     * bare reference into its display name, so a film holds the string "Q592174", not a
     * pointer to the entity that string came from. The next load collapses again and
     * picks up the repaired label — but until then the counts would keep reporting
     * references the user has just fixed, which reads as the repair not working.
     *
     * <p>Only the SELECTED field, and only where the model says it was entity-valued:
     * an ordinary field may legitimately hold QID-shaped text of its own.
     */
    private void renameCollapsedReferences(
            List<ResolveNamesProcess.Name> accepted) {
        if (selectedFieldPath == null || selectedFieldType == null) return;
        FieldPath path = FieldPath.parse(selectedFieldPath);
        if (!domain.entityOrigin(selectedFieldType, path)) return;
        Map<String, String> byQid = new LinkedHashMap<>();
        for (ResolveNamesProcess.Name name : accepted) {
            if (name.label() != null && !name.label().isBlank()) {
                byQid.put(name.qid(), name.label());
            }
        }
        if (byQid.isEmpty()) return;
        for (Viewable member : instances) {
            if (!domain.isInstanceOf(member, selectedFieldType)) continue;
            Object value = objectview.field.FieldAccess.getPath(member, selectedFieldPath);
            if (value instanceof CharSequence text) {
                String label = byQid.get(text.toString().trim());
                if (label != null) {
                    objectview.field.FieldAccess.setPath(member, selectedFieldPath, label);
                }
            } else if (value instanceof List<?> values) {
                List<Object> replaced = new ArrayList<>(values.size());
                boolean changed = false;
                for (Object item : values) {
                    String label = item instanceof CharSequence text2
                            ? byQid.get(text2.toString().trim()) : null;
                    replaced.add(label == null ? item : label);
                    changed |= label != null;
                }
                if (changed) {
                    objectview.field.FieldAccess.setPath(member, selectedFieldPath, replaced);
                }
            }
        }
    }

    /** Selects {@code path} and drills the members whose reference has no name. */
    private void showUnnamedReferences(FieldPath path) {
        identityTask = false;
        syncScopeChoices();
        scopeFilter = ScopeFilter.UNNAMED_REFERENCE;
        scopeCombo.setSelectedItem(scopeFilter);
        coverage.setSelectedPath(path);
        onFieldSelected();
    }

    /** Starts source discovery; true means a picker was opened asynchronously. */
    private boolean chooseSourceForSelected(boolean produceAfterChoice) {
        if (selectedFieldType == null || selectedFieldPath == null) return false;
        ExploreSeed seed = sampleForSourceDiscovery(curationStore());
        if (seed == null) {
            if (!produceAfterChoice) {
                JOptionPane.showMessageDialog(this,
                                              "No member in this scope has a Wikidata identity to sample.");
            }
            return false;
        }
        chooseFieldSource(seed,
                          new FieldKey(selectedFieldType, selectedFieldPath), produceAfterChoice);
        return true;
    }

    private void chooseFieldSource(
            ExploreSeed seed, FieldKey key, boolean produceAfterChoice) {
        // Explore mode 2: pick a property of the resolved sample entity and RETURN it —
        // the caller (here) sets the field source. No bespoke property-picker path.
        ExploreByExamplePanel.findProperty(
                this, queryRunner, seed.qid(), seed.label(),
                (pid, label) -> {
                    if (pid == null || pid.isBlank()) return;
                    FieldSourceMapping source = new FieldSourceMapping();
                    source.sourceType(FieldSourceType.SPARQL);
                    source.propertyPid(pid);
                    source.propertyLabel(label);
                    source.direction(RuleDirection.ROOT_TO_ITEM);
                    source.productionKind(FieldProductionKind.AUTO);
                    fieldSourceChoices.choosePrimary(key.type(), key.path(), source);
                    updateFieldSourceButton();
                    if (produceAfterChoice) {
                        // Through the same confirmation as every other start. Choosing a
                        // property is picking a tool, not pulling the trigger — and this
                        // path is the one where the user has just seen a dialog, so a run
                        // beginning by itself reads as the picker having done it.
                        SwingUtilities.invokeLater(() -> {
                            if (confirmFill(key, false)) runFillBatch(key.path());
                        });
                    }
                });
    }

    /** Pick a Wikipedia infobox source from sample members and set it as the
     *  selected field's fallback source. The primary Wikidata mapping remains intact; the
     *  ordinary Find Data process decides per member whether this fallback is needed. */
    private void chooseDbpediaSourceForSelected() {
        if (selectedFieldType == null || selectedFieldPath == null) {
            return;
        }
        ExploreSeed seed = sampleForSourceDiscovery(curationStore());
        if (seed == null) {
            JOptionPane.showMessageDialog(this,
                                          "No member in this scope has a Wikidata identity to sample.");
            return;
        }
        FieldKey key = new FieldKey(selectedFieldType, selectedFieldPath);
        List<String> qids = actionInstances().stream()
                .map(v -> resolvedQid(v, EnrichmentSources.collect(
                        v, concreteType(v), curationStore())))
                .filter(ValidationPanel::isQid).distinct().limit(12).toList();
        // Choosing is shared with ModelBuilder; RECORDING is this dataset's own business,
        // and so is having something to clear — the model side has no curation sidecar.
        AdditionalSourcePicker.choose(this, queryRunner,
                AdditionalSourcePicker.ofEntities(
                        qids.isEmpty() ? List.of(seed.qid()) : qids),
                choice -> recordAdditionalSource(key, choice.sourceType(),
                        choice.property(), choice.label()),
                datasetFallbackOverride(key) == null ? null : () -> clearAdditionalSource(key));
    }

    private void clearAdditionalSource(FieldKey key) {
        fieldSourceChoices.clearAdditional(key.type(), key.path());
        updateFieldSourceButton();
    }

    /** Record this DATASET's additional source for a field, whichever kind was chosen.
     *  Only the native branch used to write the sidecar, so a DBpedia choice lived until
     *  the panel closed and then had to be made again — while the abandoned native recipe
     *  was still there to be heard on reload. One choice, one slot, written one way. */
    private void recordAdditionalSource(FieldKey key, FieldSourceType sourceType,
            String property, String label) {
        if (property == null || property.isBlank()) return;
        FieldSourceMapping source = new FieldSourceMapping();
        source.sourceType(sourceType);
        source.propertyPid(property);
        source.propertyLabel(label);
        source.productionKind(FieldProductionKind.AUTO);
        fieldSourceChoices.recordAdditional(key.type(), key.path(), source);
        updateFieldSourceButton();
    }

    private FieldSourceMapping sourceFor(FieldKey key) {
        if (key == null) return null;
        return configuredValuesOnly
                ? fieldSourceChoices.declaredPrimary(key.type(), key.path())
                : fieldSourceChoices.primary(key.type(), key.path());
    }

    private FieldSourceMapping datasetFallbackOverride(FieldKey key) {
        return key == null ? null : fieldSourceChoices.datasetOverride(key.type(), key.path());
    }

    private FieldSourceMapping fallbackSourceFor(FieldKey key) {
        if (key == null) return null;
        return configuredValuesOnly
                ? FieldSourceChoices.additionalSource(null, domain, key.type(), key.path())
                : fieldSourceChoices.additional(key.type(), key.path());
    }

    private void updateFieldSourceButton() {
        FieldKey key = new FieldKey(selectedFieldType, selectedFieldPath);
        FieldSourceMapping source = sourceFor(key);
        FieldSourceMapping fallback = fallbackSourceFor(key);
        // Primary and fallback have independent state: choosing one never erases the other.
        fieldSourceButton.setText("Choose Wikidata source…");
        fieldSourceButton.setToolTipText(
                "Choose a property from a sample member's real Wikidata claims");
        fieldSourceDbpediaButton.setText("Choose Wikipedia fallback…");
        fieldSourceDbpediaButton.setToolTipText(
                "Choose a native Wikipedia or DBpedia infobox fallback used only when "
                        + "Wikidata has no usable value");
        if (source != null && !source.propertyPid().isBlank()) {
            String label = source.propertyLabel().isBlank()
                    ? source.propertyPid() : source.propertyLabel();
            fieldSourceButton.setText("Source: " + label + " (" + source.propertyPid() + ")…");
            fieldSourceButton.setToolTipText("Change this field's source rule");
        }
        if (fallback != null && !fallback.propertyPid().isBlank()) {
            fieldSourceDbpediaButton.setText(
                    "Fallback: " + fallback.propertyPid() + " (Wikipedia)…");
            fieldSourceDbpediaButton.setToolTipText(
                    "Change the fallback used when the Wikidata source has no usable value");
        }
    }

    /** DataSourceApp executes the model's saved bindings; it does not become a second
     * field-source editor. The disabled controls name exactly what will be used, asked
     * through the same declared bindings validation and execution read: a field whose
     * primary source is a datasource operation is configured even with no property. */
    private void makeSourceBindingsReadOnly() {
        FieldKey key = new FieldKey(selectedFieldType, selectedFieldPath);
        fieldSourceButton.setEnabled(false);
        fieldSourceDbpediaButton.setEnabled(false);
        fieldSourceButton.setToolTipText("Configure this field source in ModelBuilder");
        fieldSourceDbpediaButton.setToolTipText(
                "Configure this fallback source in ModelBuilder");
        fieldSourceButton.setText(configuredSource(sourceFor(key),
                declaredBinding(SourceBindingSlot.PRIMARY_FIELD_VALUE),
                "Source: ", "Not configured in ModelBuilder"));
        fieldSourceDbpediaButton.setText(configuredSource(fallbackSourceFor(key),
                declaredBinding(SourceBindingSlot.FALLBACK_FIELD_VALUE),
                "Fallback: ", "No configured fallback"));
    }

    private void makeCategoryRelationReadOnly() {
        domain.config.WikipediaCategoryRule effective =
                selectedFieldType == null || selectedFieldPath == null ? null
                        : domain.wikipediaCategoryRule(selectedFieldType, selectedFieldPath);
        categoryRelationLabel.setText(effective != null && effective.configured()
                ? "Model: " + WikipediaCategoryRuleEditor.summary(effective)
                : "No category relation configured");
        categoryRelationLabel.setToolTipText("Configure category relations in ModelBuilder");
    }

    private SourceBinding declaredBinding(
            SourceBindingSlot slot) {
        FieldRulePromoter ruleModel =
                domain.capability(FieldRulePromoter.class);
        return ruleModel == null || selectedFieldType == null || selectedFieldPath == null
                ? null : ruleModel.declaredBinding(selectedFieldType, selectedFieldPath, slot);
    }

    private SourceBinding candidateEvidenceBinding(String ownerType, String field) {
        if (fixedCandidateEvidence != null) return fixedCandidateEvidence;
        java.util.List<SourceBinding> recipes = candidateEvidenceBindings(ownerType, field);
        return recipes.isEmpty() ? null : recipes.getFirst();
    }

    private java.util.List<SourceBinding> candidateEvidenceBindings(
            String ownerType, String field) {
        if (fixedCandidateEvidence != null) return java.util.List.of(fixedCandidateEvidence);
        FieldRulePromoter ruleModel = domain.capability(FieldRulePromoter.class);
        return ruleModel == null ? java.util.List.of() : ruleModel.declaredBindings(
                ownerType, field, SourceBindingSlot.CANDIDATE_EVIDENCE);
    }

    /** A property mapping names its property; any other declared binding names its
     *  registered datasource operation. Nothing configured says so. */
    static String configuredSource(FieldSourceMapping mapping,
            SourceBinding binding, String prefix, String none) {
        if (mapping != null && !mapping.propertyPid().isBlank()) {
            String label = mapping.propertyLabel().isBlank()
                    ? mapping.propertyPid() : mapping.propertyLabel();
            return prefix + label + " (" + mapping.propertyPid() + ")";
        }
        if (binding == null || binding.recipe() == null) return none;
        try {
            return prefix + binding.recipe().resolve(Datasources.standard())
                    .displayName();
        } catch (IllegalArgumentException unknown) {
            return prefix + binding.recipe().providerId() + " · "
                    + binding.recipe().operationId();
        }
    }

    /** The first missing member that carries (or resolves to) a QID — the sample entity
     *  whose properties the picker lists. */
    private ExploreSeed sampleForSourceDiscovery(ManualCuration curation) {
        for (Viewable member : actionInstances()) {
            if (!domain.isInstanceOf(member, selectedFieldType)) continue;
            String qid = resolvedQid(member,
                                     EnrichmentSources.collect(member, concreteType(member), curation));
            if (qid != null) return new ExploreSeed(qid, member.getDisplayName());
        }
        return null;
    }

    // One immutable batch plan; one independently logged/cancellable child per member.
    private void runFillBatch(String path) {
        ManualCuration curation = curationStore();
        DomainField drilled = domain.fields(selectedFieldType).stream()
                                    .filter(f -> path.equals(f.field()))
                                    .findFirst().orElse(null);
        boolean collection = drilled != null && drilled.collection();
        boolean media = drilled == null || drilled.kind() == objectview.field.FieldKind.MEDIA;

        List<FindDataProcess> jobs = new ArrayList<>();
        int skipped = 0;
        for (Viewable member : actionInstances()) {
            if (!domain.isInstanceOf(member, selectedFieldType)) continue;
            String memberType = fieldContextType(domain, member, selectedFieldType);
            List<SourceRef> sources =
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
                    DomainSchemas.resolve(domain, memberType, path),
                    objectview.field.FieldAccess.getPath(member, path));
            EnrichmentRoute route = routeFor(media, qid, path).applicableTo(request);
            if (route.isEmpty()) {
                skipped++;
                continue;
            }
            // Discovery only (false): the batch runs ONE review over all members below,
            // not a modal per member.
            jobs.add(new FindDataProcess(request, route, false));
        }
        if (jobs.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                                          "No member in this scope is currently eligible for Find Data. "
                                                  + "Add an exact source for a selected member and try again.");
            return;
        }

        runReviewBatch(jobs, skipped, path,
                "Review found data" + (path.isBlank() ? "" : " — " + path),
                "Review the values found for these members.", "Lookup failed");
    }

    /** One execution and result-presentation path for every field acquisition action.
     *  The caller chooses the configured providers and honest wording only; batching,
     *  logging, failure handling, review and decision application stay identical. */
    private void runReviewBatch(
            List<FindDataProcess> jobs, int skipped, String path,
            String reviewTitle, String reviewPrompt, String failureTitle) {
        if (acquisitionRunning) return;
        acquisitionRunning = true;
        onAcquisition.accept(true);
        findDataRunner.run(new FindDataBatchProcess(jobs, skipped, path),
                outcome -> SwingUtilities.invokeLater(() -> {
                    try {
                        FindDataBatchResult result = outcome.result();
                        ManualCuration store = curationStore();
                        if (result != null && store != null) {
                            List<EnrichmentProposal> proposals = result.results().stream()
                                    .map(FindDataResult::proposal).toList();
                            showReview(reviewTitle, reviewPrompt, proposals,
                                    SourceYieldCards.of(result.sourceYields()));
                        }
                        if (outcome.status() == ProcessStatus.FAILED && outcome.error() != null) {
                            JOptionPane.showMessageDialog(this,
                                    failureTitle + ": " + outcome.error().getMessage());
                        }
                    } finally {
                        acquisitionFinished();
                    }
                }), ex -> {
                    try {
                        JOptionPane.showMessageDialog(this, failureTitle + ": " + ex.getMessage());
                    } finally {
                        acquisitionFinished();
                    }
                });
    }

    private void acquisitionFinished() {
        acquisitionRunning = false;
        if (configuredValuesOnly) {
            checkButton.setEnabled(!actionInstances().isEmpty() && configuredRouteAvailable(
                    selectedDomainField(), new FieldKey(selectedFieldType, selectedFieldPath)));
        }
        onAcquisition.accept(false);
    }

    /** The review is document-modal (Dialogs.reviewDialog): while it is open this
     * workbench, including its run actions, cannot be used, so no second request can
     * start behind it. */
    private void showReview(String title, String prompt,
            List<EnrichmentProposal> proposals, List<Viewable> sourceReport) {
        review.show(title, prompt, proposals, sourceReport, this::applyDecisions);
    }

    @FunctionalInterface
    interface AcquisitionReview {
        void show(String title, String prompt, List<EnrichmentProposal> proposals,
                List<Viewable> sourceReport, java.util.function.Predicate<List<EnrichmentDecision>> accept);
    }

    private AcquisitionReview review = (title, prompt, proposals, sourceReport, accept) ->
            FindDataBatchReviewPanel.showReview(this, title, prompt, proposals, sourceReport,
                    reviewed -> accept.test(reviewed.accepted()));

    void acquisitionReview(AcquisitionReview presenter) {
        review = presenter;
    }

    private domain.config.WikipediaCategoryRule effectiveWikipediaCategoryRule(
            String ownerType, String field) {
        return domain.wikipediaCategoryRule(ownerType, field);
    }

    /** One lookup of the model-authored candidate rule, shared by its explanation and
     *  execution so the button cannot promise a mode the action then fails to run. */
    private WikipediaTextEvidenceProvider.CandidateEvidenceMode
            configuredCandidateEvidence(String ownerType, String field) {
        if (configuredArticleTableRowInstances(ownerType, field) != null) {
            return WikipediaTextEvidenceProvider.CandidateEvidenceMode
                    .ARTICLE_TABLE_ROW_INSTANCES;
        }
        if (configuredArticlePassageInstances(ownerType, field) != null) {
            return WikipediaTextEvidenceProvider.CandidateEvidenceMode
                    .ARTICLE_PASSAGE_INSTANCES;
        }
        SourceBinding binding = candidateEvidenceBinding(ownerType, field);
        if (WikipediaDatasourceProvider
                .articlePassages(binding)) {
            return WikipediaTextEvidenceProvider.CandidateEvidenceMode
                    .ARTICLE_PASSAGES;
        }
        if (WikipediaDatasourceProvider
                .articleIntroductionMentions(binding)) {
            return WikipediaTextEvidenceProvider.CandidateEvidenceMode
                    .LOADED_INTRODUCTION_MENTIONS;
        }
        if (WikipediaDatasourceProvider
                .infoboxRowLinkOperation(binding)) {
            return WikipediaTextEvidenceProvider.CandidateEvidenceMode
                    .INFOBOX_ROW_LINKS;
        }
        return WikipediaTextEvidenceProvider.CandidateEvidenceMode.NONE;
    }

    private String configuredInfoboxRowLinks(String ownerType, String field) {
        SourceBinding binding = candidateEvidenceBinding(ownerType, field);
        return WikipediaDatasourceProvider.infoboxRowLinks(binding);
    }

    private WikipediaDatasourceProvider.ArticleTableColumnLinkSpec
            configuredArticleTableColumnLinks(String ownerType, String field) {
        SourceBinding binding = candidateEvidenceBinding(ownerType, field);
        return WikipediaDatasourceProvider
                .articleTableColumnLinks(binding);
    }

    private WikipediaDatasourceProvider.ArticleTableRowInstanceSpec
            configuredArticleTableRowInstances(String ownerType, String field) {
        SourceBinding binding = candidateEvidenceBinding(ownerType, field);
        return WikipediaDatasourceProvider
                .articleTableRowInstances(binding);
    }

    private WikipediaDatasourceProvider.ArticlePassageInstanceSpec
            configuredArticlePassageInstances(String ownerType, String field) {
        SourceBinding binding = candidateEvidenceBinding(ownerType, field);
        return WikipediaDatasourceProvider
                .articlePassageInstances(binding);
    }

    private WikipediaDatasourceProvider.ArticlePassageSpec
            configuredArticlePassageSpec(String ownerType, String field) {
        SourceBinding binding = candidateEvidenceBinding(ownerType, field);
        return WikipediaDatasourceProvider.articlePassageSpec(binding);
    }

    /** Reviews configured evidence from versioned Wikipedia prose. This is deliberately
     * a separate action from Load values: a mention may corroborate an existing value or,
     * when the model says so, nominate an already-typed instance; neither is automatic
     * field inference. */
    private void findWikipediaEvidence() {
        String path = selectedFieldPath;
        if (path == null || path.isBlank()) return;
        DomainField drilled = domain.fields(selectedFieldType).stream()
                .filter(f -> path.equals(f.field())).findFirst().orElse(null);
        if (drilled != null && drilled.kind() == objectview.field.FieldKind.MEDIA) {
            JOptionPane.showMessageDialog(this,
                    "Text evidence is available for data and reference fields, not media.");
            return;
        }
        ManualCuration curation = curationStore();
        List<FindDataProcess> jobs = new ArrayList<>();
        domain.config.WikipediaCategoryRule categoryRule =
                effectiveWikipediaCategoryRule(selectedFieldType, path);
        // What this field REFERS TO decides which category candidates are even plausible:
        // "Films set in <value>" matches "Films set in 1999" as honestly as it matches a
        // place, and a year offered as a Location is noise the reviewer rejects every run.
        objectview.field.FieldRef fieldSchema =
                DomainSchemas.resolve(domain, selectedFieldType, path);
        domain.config.EntityKindRule referentKind = fieldSchema == null
                ? null : domain.entityKindRule(fieldSchema.targetType());
        WikipediaTextEvidenceProvider.CandidateEvidenceMode candidateMode =
                configuredCandidateEvidence(selectedFieldType, path);
        boolean introductionMentions = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode
                        .LOADED_INTRODUCTION_MENTIONS;
        boolean articlePassages = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode
                        .ARTICLE_PASSAGES;
        boolean articlePassageInstances = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode
                        .ARTICLE_PASSAGE_INSTANCES;
        boolean articleTableRowInstances = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode
                        .ARTICLE_TABLE_ROW_INSTANCES;
        boolean infoboxRowLinks = candidateMode
                == WikipediaTextEvidenceProvider.CandidateEvidenceMode
                        .INFOBOX_ROW_LINKS;
        String infoboxRowKey = configuredInfoboxRowLinks(selectedFieldType, path);
        WikipediaDatasourceProvider.ArticleTableColumnLinkSpec
                tableColumnSpec = configuredArticleTableColumnLinks(
                        selectedFieldType, path);
        boolean tableColumnLinks = tableColumnSpec != null;
        WikipediaDatasourceProvider.ArticleTableRowInstanceSpec
                tableRowSpec = configuredArticleTableRowInstances(
                        selectedFieldType, path);
        WikipediaDatasourceProvider.ArticlePassageInstanceSpec
                passageInstanceSpec = configuredArticlePassageInstances(
                        selectedFieldType, path);
        WikipediaDatasourceProvider.ArticlePassageSpec passageSpec =
                configuredArticlePassageSpec(selectedFieldType, path);
        if (articlePassages && (fieldSchema == null
                || fieldSchema.kind() != objectview.field.FieldKind.TEXT
                || fieldSchema.collection())) {
            JOptionPane.showMessageDialog(this,
                    "Bounded article passages require one Text value. " + selectedFieldType
                            + "." + path + " does not have that field shape.");
            return;
        }
        if (articlePassageInstances && (fieldSchema == null
                || !fieldSchema.reference() || !fieldSchema.collection()
                || fieldSchema.targetType().isBlank())) {
            JOptionPane.showMessageDialog(this,
                    "Article passage instances require a reference list with a declared "
                            + "target class. " + selectedFieldType + "." + path
                            + " does not have that field shape.");
            return;
        }
        if (articleTableRowInstances && (fieldSchema == null
                || !fieldSchema.reference() || !fieldSchema.collection()
                || fieldSchema.targetType().isBlank())) {
            JOptionPane.showMessageDialog(this,
                    "Article table-row instances require a reference list with a declared "
                            + "target class. " + selectedFieldType + "." + path
                            + " does not have that field shape.");
            return;
        }
        if (infoboxRowLinks && (fieldSchema == null || !fieldSchema.reference()
                || !fieldSchema.collection() || fieldSchema.targetType().isBlank())) {
            JOptionPane.showMessageDialog(this,
                    "Rendered infobox links require a reference list with a declared "
                            + "target class. " + selectedFieldType + "." + path
                            + " does not have that field shape.");
            return;
        }
        if (infoboxRowLinks && infoboxRowKey == null) {
            JOptionPane.showMessageDialog(this,
                    "Rendered infobox links require a key such as Rendered.Parents. "
                            + "Complete the candidate-evidence configuration first.");
            return;
        }
        if (tableColumnLinks && (fieldSchema == null || !fieldSchema.reference()
                || !fieldSchema.collection() || fieldSchema.targetType().isBlank())) {
            JOptionPane.showMessageDialog(this,
                    "Article table-column links require a reference list with a declared "
                            + "target class. " + selectedFieldType + "." + path
                            + " does not have that field shape.");
            return;
        }
        boolean mentionTargetConfigured = (introductionMentions || infoboxRowLinks
                || tableColumnLinks)
                && fieldSchema != null
                && fieldSchema.reference() && !fieldSchema.targetType().isBlank();
        List<Viewable> mentionCandidates = !mentionTargetConfigured
                ? List.of() : domain.instancesOf(fieldSchema.targetType());
        objectview.field.FieldRef rowReferencesSchema = !articleTableRowInstances
                || tableRowSpec.referencesField().isBlank() ? null
                : DomainSchemas.resolve(domain, tableRowSpec.targetClass(),
                        tableRowSpec.referencesField());
        List<Viewable> rowReferenceCandidates = rowReferencesSchema == null
                || rowReferencesSchema.targetType().isBlank() ? List.of()
                : domain.instancesOf(rowReferencesSchema.targetType());
        List<Viewable> rowCandidates = new ArrayList<>(
                domain.instancesOf(fieldSchema.targetType()));
        java.util.Set<String> rowCandidateIdentities = rowCandidates.stream()
                .map(value -> value.typeName() + '\0' + value.getIdentifier())
                .collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
        for (Viewable candidate : rowReferenceCandidates) {
            if (rowCandidateIdentities.add(
                    candidate.typeName() + '\0' + candidate.getIdentifier())) {
                rowCandidates.add(candidate);
            }
        }
        EnrichmentProvider provider = articleTableRowInstances
                ? new WikipediaTableRowInstanceEvidenceProvider(
                        tableRowSpec, rowCandidates,
                        rowReferencesSchema == null ? "" : rowReferencesSchema.targetType(),
                        domain)
                : tableColumnLinks
                ? new WikipediaTableReferenceEvidenceProvider(
                        tableColumnSpec, mentionCandidates, domain)
                : infoboxRowLinks
                ? new WikipediaInfoboxReferenceEvidenceProvider(
                        infoboxRowKey, mentionCandidates, domain)
                : new WikipediaTextEvidenceProvider(
                        categoryRule, referentKind, mentionCandidates, candidateMode,
                        passageInstanceSpec, passageSpec);
        int skipped = 0;
        for (Viewable member : actionInstances()) {
            if (!domain.isInstanceOf(member, selectedFieldType)) continue;
            Object current = objectview.field.FieldAccess.getPath(member, path);
            boolean hasCategories = member instanceof
                    domain.runtime.WikidataDynamicObject wikidata
                    && !wikidata.categoryMemberships().isEmpty()
                    && categoryRule != null && categoryRule.configured();
            ArticleIntroduction introduction = member instanceof
                    domain.runtime.WikidataDynamicObject wikidata
                    ? wikidata.articleIntroduction() : null;
            WikipediaArticle retainedArticle = member instanceof
                    domain.runtime.WikidataDynamicObject wikidata
                    ? wikidata.wikipediaArticle() : null;
            InfoboxParameters infobox = member instanceof
                    domain.runtime.WikidataDynamicObject wikidata
                    ? wikidata.infoboxParameters() : null;
            boolean hasIntroductionCandidates = introduction != null
                    && introductionMentions && !mentionCandidates.isEmpty();
            boolean hasInfoboxCandidates = infoboxRowLinks && infobox != null
                    && !mentionCandidates.isEmpty()
                    && infobox.structuredValues().entrySet().stream()
                            .anyMatch(entry -> entry.getKey().toString()
                                    .equalsIgnoreCase(infoboxRowKey)
                                    && !entry.getValue().links().isEmpty());
            boolean hasCurrentValue = current != null
                    && (!(current instanceof Collection<?> values) || !values.isEmpty());
            boolean usableEvidence = articleTableRowInstances
                    ? true
                    : tableColumnLinks
                    ? !mentionCandidates.isEmpty()
                    : infoboxRowLinks ? hasInfoboxCandidates
                    : hasCurrentValue || hasCategories || hasIntroductionCandidates
                            || articlePassages || articlePassageInstances;
            if (!usableEvidence) {
                skipped++;
                continue;
            }
            String memberType = fieldContextType(domain, member, selectedFieldType);
            List<SourceRef> sources = EnrichmentSources.collect(member, memberType, curation);
            String qid = resolvedQid(member, sources);
            if (!isQid(qid)) {
                skipped++;
                continue;
            }
            EnrichmentRequest request = new EnrichmentRequest(
                    new EnrichmentProposal.Subject(memberType, member.getIdentifier(), qid,
                            member.getDisplayName()),
                    path, drilled != null && drilled.collection(), sources,
                    DomainSchemas.resolve(domain, memberType, path), current,
                    member instanceof domain.runtime.WikidataDynamicObject wikidata
                            ? wikidata.categoryMemberships() : List.of(),
                    introduction, retainedArticle, infobox);
            EnrichmentRoute route = EnrichmentRoute.all(List.of(provider))
                    .applicableTo(request);
            if (route.isEmpty()) {
                skipped++;
                continue;
            }
            jobs.add(new FindDataProcess(request, route, false));
        }
        if (jobs.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    articleTableRowInstances
                            ? "No selected group has a Wikidata identity."
                    : tableColumnLinks
                            ? "No member in this scope has a Wikidata identity, or there are "
                                    + "no loaded instances of the field's target class."
                            : infoboxRowLinks
                            ? "No member in this scope has links in the retained "
                                    + infoboxRowKey + " infobox row and a Wikidata identity."
                            : "No member in this scope has an existing value, configured "
                                    + "retained article evidence, or a Wikidata identity.");
            return;
        }
        String mentionExplanation = articleTableRowInstances
                ? "Each selected group's retained structured article is read once. Every "
                        + "data row in <b>" + tableRowSpec.description()
                        + "</b> that is not already represented by a current linked member "
                        + "proposes one " + tableRowSpec.targetClass() + ". The row "
                        + "fills " + tableRowSpec.detailsField() + ", its selected group "
                        + "fills " + tableRowSpec.sourceField()
                        + (tableRowSpec.referencesField().isBlank() ? ""
                        : ", and loaded links from the display cell fill "
                                + tableRowSpec.referencesField())
                        + ". Unresolved links create no entity. These exact configured rows "
                        + "are included in Stage all safe results.<br>"
                : articlePassageInstances
                ? "Each selected member's full article is read once. Every bounded content "
                        + "passage becomes a separate review card proposing one "
                        + fieldSchema.targetType() + "; accepting that card fills "
                        + passageInstanceSpec.textField() + " with the passage, fills "
                        + passageInstanceSpec.sourceField() + " with the selected "
                        + selectedFieldType + ", and adds the new instance to " + path
                        + ". No passage is included in Stage all safe results.<br>"
                : articlePassages
                ? "Each selected member's full article is read once; at most "
                        + WikipediaDatasourceProvider
                                .MAX_ARTICLE_PASSAGES
                        + " " + passageSpec.description() + " and "
                        + WikipediaDatasourceProvider
                                .MAX_ARTICLE_PASSAGE_CHARACTERS
                        + " characters are proposed as one reviewable text value. Standard "
                        + "maintenance sections are omitted; no entities or events are inferred.<br>"
                : infoboxRowLinks
                ? !mentionTargetConfigured
                    ? "Infobox-row links are configured, but this field has no declared "
                            + "reference target; no typed candidates can be proposed.<br>"
                    : mentionCandidates.isEmpty()
                    ? "Infobox-row links are configured, but there are 0 loaded "
                            + fieldSchema.targetType()
                            + " instances; no typed candidates can be proposed.<br>"
                    : "Links retained in <b>" + infoboxRowKey + "</b> can propose only "
                            + mentionCandidates.size() + " loaded "
                            + fieldSchema.targetType() + " instance(s). Unlinked text and "
                            + "links to unloaded entities are ignored; no identity is created.<br>"
                : tableColumnLinks
                ? !mentionTargetConfigured
                    ? "Article table-column links are configured, but this field has no "
                            + "declared reference target; no typed candidates can be proposed.<br>"
                    : mentionCandidates.isEmpty()
                    ? "Article table-column links are configured, but there are 0 loaded "
                            + fieldSchema.targetType()
                            + " instances; no typed candidates can be proposed.<br>"
                    : "Links in <b>" + tableColumnSpec.description()
                            + "</b> can propose only " + mentionCandidates.size()
                            + " loaded " + fieldSchema.targetType()
                            + " instance(s). Unlinked text and links to unloaded entities "
                            + "are reported but create no identity.<br>"
                : !introductionMentions ? ""
                : !mentionTargetConfigured
                ? "Introduction mentions are configured, but this field has no declared "
                        + "reference target; no typed candidates can be proposed.<br>"
                : mentionCandidates.isEmpty()
                ? "Introduction mentions are configured, but there are 0 loaded "
                        + fieldSchema.targetType()
                        + " instances; no typed candidates can be proposed.<br>"
                : "Retained article introductions can propose exact mentions among "
                        + mentionCandidates.size() + " loaded " + fieldSchema.targetType()
                        + " instance(s); they cannot create new identities.<br>";
        String message = "<html><div style='width: 520px'><b>Find Wikipedia evidence for "
                + selectedFieldType + "."
                + path + "</b><br><br>Eligible members: <b>" + jobs.size() + "</b><br>"
                + "Skipped: <b>" + skipped + "</b> ("
                + (articleTableRowInstances
                        ? "no Wikidata identity"
                        : tableColumnLinks
                        ? "no configured table-column candidates or no identity"
                        : infoboxRowLinks
                        ? "no retained links in the configured row or no identity"
                        : "no usable value/category evidence or identity")
                + ")<br><br>"
                + (infoboxRowLinks || tableColumnLinks || articleTableRowInstances ? ""
                        : "Existing values are checked against article text. Configured category "
                                + "relations can also propose new values from memberships acquired "
                                + "by ModelBuilder. ")
                + mentionExplanation
                + (articlePassages || articlePassageInstances || articleTableRowInstances
                        || infoboxRowLinks
                        || tableColumnLinks
                        || referentKind == null ? ""
                        : "Typed candidates must be a " + fieldSchema.targetType()
                                + " by the model's rule (" + referentKind.propertyPid()
                                + "); others are not offered.<br>")
                + "Every result retains its article revision and exact source evidence; "
                + "nothing is saved until you accept it.</div></html>";
        if (JOptionPane.showConfirmDialog(this, new JLabel(message),
                articleTableRowInstances ? "Find Wikipedia table-row instances"
                        : tableColumnLinks ? "Find Wikipedia table-column link evidence"
                        : infoboxRowLinks ? "Find Wikipedia infobox link evidence"
                        : "Find Wikipedia text evidence", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;

        runReviewBatch(jobs, skipped, path, "Review Wikipedia evidence — " + path,
                articleTableRowInstances
                        ? "Review modeled instances produced from "
                                + tableRowSpec.description() + "."
                        : tableColumnLinks
                        ? "Review loaded target instances linked from "
                                + tableColumnSpec.description() + "."
                        : infoboxRowLinks
                        ? "Review loaded target instances linked from "
                                + infoboxRowKey + "."
                        : "Review exact article mentions and configured category relations.",
                "Wikipedia lookup failed");
    }

    private void findWikipediaEvidence(SourceBinding recipe) {
        SourceBinding previous = fixedCandidateEvidence;
        fixedCandidateEvidence = recipe;
        try {
            findWikipediaEvidence();
        } finally {
            fixedCandidateEvidence = previous;
        }
    }

    private EnrichmentRoute routeFor(
            boolean media, String qid, String path) {
        if (!media) {
            FieldKey key = new FieldKey(selectedFieldType, path);
            return FieldEnrichmentRoutes.from(
                    domain, sourceFor(key), fallbackSourceFor(key), referenceResolver);
        }
        List<EnrichmentProvider> providers = new ArrayList<>();
        providers.add(new WikimediaImageEnrichmentProvider());
        providers.add(new SourcePageImageEnrichmentProvider());
        if (qid == null) providers.add(new DBpediaImageEnrichmentProvider());
        return EnrichmentRoute.all(providers);
    }

    private static boolean isQid(String id) {
        return WikidataSource.isQid(id);
    }


    private ManualCuration curationStore() {
        Curatable c = domain.capability(Curatable.class);
        return c == null ? null : c.curation();
    }

    /** One review action validates, previews and durably saves one transaction. Staging
     * is internal machinery; accepting a reviewed result never asks for a second save. */
    private boolean applyDecisions(List<EnrichmentDecision> decisions) {
        try {
            EnrichmentDecisionApplier.StagedPreview preview =
                    EnrichmentDecisionApplier.acceptAndSave(
                            domain, staging, decisions);
            if (!preview.unresolvedReferences().isEmpty()) {
                Dialogs.showMessage(this,
                        preview.unresolvedReferences().size()
                                + " reference(s) of the accepted instances name "
                                + "nothing loaded, so they were left empty:\n"
                                + String.join("\n", preview.unresolvedReferences()),
                        "Unresolved references", JOptionPane.WARNING_MESSAGE);
            }
            acceptedOutcome.setText(acceptedOutcomeText(decisions, preview, sidecarName()));
            updateApplyButton();
            refreshCoverage();
            onCurated.run();
            renderFieldDrill();
            return true;
        } catch (Exception ex) {
            updateApplyButton();
            Dialogs.showMessage(this, "Could not accept and save result: " + ex.getMessage(),
                    "Could not save accepted result", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /** The curation file an accepted review writes, as named to the user. */
    private String sidecarName() {
        ManualCuration curation = curationStore();
        return curation == null || curation.file() == null ? "" : curation.file().getName();
    }

    /** Names the domain objects one accepted review created and the file it was saved to
     * (directives 17/18). Persistence may require several internal records per object,
     * but that is not a user-visible result. */
    static String acceptedOutcomeText(List<EnrichmentDecision> decisions,
            EnrichmentDecisionApplier.StagedPreview preview, String savedTo) {
        int changes = 0;
        int created = 0;
        Map<String, Integer> createdByType = new LinkedHashMap<>();
        for (EnrichmentDecision decision : decisions == null
                ? List.<EnrichmentDecision>of() : decisions) {
            if (decision == null) continue;
            List<EnrichmentProposal.FieldCandidate> fields = decision.fields().stream()
                    .map(EnrichmentDecision.FieldDecision::candidate).toList();
            changes += EnrichmentProposal.logicalChangeCount(
                    fields,
                    decision.media() == null ? List.of() : List.of(decision.media()),
                    decision.instances());
            created += decision.instances().size();
            for (EnrichmentProposal.InstanceCandidate instance : decision.instances()) {
                createdByType.merge(instance.type(), 1, Integer::sum);
            }
        }
        if (preview == null || preview.stagedRecords() == 0) {
            return "Nothing was accepted from that review.";
        }
        String createdText = createdDescription(created, createdByType);
        String what;
        if (created > 0 && changes == created) {
            what = "Accepted and saved " + createdText;
        } else if (created > 0) {
            what = "Accepted and saved " + changes + " change"
                    + (changes == 1 ? "" : "s") + ", including " + createdText;
        } else {
            what = "Accepted and saved " + changes + " change"
                    + (changes == 1 ? "" : "s");
        }
        if (savedTo != null && !savedTo.isBlank()) what += " to " + savedTo;
        return preview.appliedValues() == 0
                ? what + ", but none changed the loaded instances — check the field and "
                        + "its target class."
                : what + ".";
    }

    private static String createdDescription(int created,
            Map<String, Integer> createdByType) {
        String type = createdByType.size() == 1
                ? createdByType.keySet().iterator().next() : "";
        return created + " new " + (type.isBlank() ? "" : type + " ")
                + (created == 1 ? "instance" : "instances");
    }

    private void exploreIdentity() {
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a member card first.");
            return;
        }
        Viewable target = selected;
        ExploreByExamplePanel.showPicker(
                this, queryRunner, target.getDisplayName(), false,
                (qid, label) -> approveWikidataIdentity(target, qid, label));
    }

    private void approveWikidataIdentity(Viewable target, String qid, String label) {
        if (target == null || !isQid(qid)) return;
        String targetId = target.getIdentifier();
        if (targetId == null || targetId.isBlank()) {
            JOptionPane.showMessageDialog(this,
                                          "This instance has no stable identifier, so its identity cannot be saved.");
            return;
        }
        String picked = label == null ? "" : label.trim();
        if (!picked.isBlank()) {
            identityLabelRequest++;
            commitApprovedIdentity(target, qid, picked);
            return;
        }
        // Reuse the application's managed query runner rather than creating an unmanaged
        // thread. The generation token prevents a slow fallback lookup from overwriting a
        // newer picker choice for the same panel.
        long request = ++identityLabelRequest;
        queryRunner.runQuiet(new WikimediaEntityLookup().byQid(qid),
                             record -> SwingUtilities.invokeLater(() -> {
                                 if (request != identityLabelRequest) return;
                                 commitApprovedIdentity(target, qid,
                                                        record == null ? null : record.label());
                             }),
                             error -> SwingUtilities.invokeLater(() -> {
                                 if (request != identityLabelRequest) return;
                                 commitApprovedIdentity(target, qid, null);
                             }));
    }

    /** STAGE an approved identity with a non-empty canonical name (falling back to the
     *  instance display name), preview its source on the instance, and keep it visible until
     *  Apply. Find Data reads that preview through the ordinary source contract, so the
     *  detached durable write never blocks a batch fill. */
    private void commitApprovedIdentity(Viewable target, String qid, String canonicalName) {
        if (staging == null || target == null) return;
        // Written under the stable identity type — see IdentityLinks.
        String targetType = IdentityLinks.stableType(target);
        String targetId = target.getIdentifier();
        if (targetId == null || targetId.isBlank()) return;
        String name = canonicalName == null || canonicalName.isBlank()
                ? target.getDisplayName() : canonicalName;
        IdentityLink approved = new IdentityLink(
                targetType, targetId, "Wikidata", qid,
                "https://www.wikidata.org/wiki/" + qid, name, "manual");
        staging.stage(approved);
        // The pending identity lives only in CurationStaging until Apply; the card reads
        // it back from there (and from the curation history), never from instance state.
        updateIdentityButton();
        updateIdentityStatus();
        // Re-render (no re-filter) so the modal picker closes promptly and the just-resolved
        // instance stays visible with its source filled.
        if (identityTask) {
            SwingUtilities.invokeLater(this::renderIdentityDrill);
        } else if (selectedFieldPath != null) {
            SwingUtilities.invokeLater(this::renderFieldDrill);
        }
    }

    /** Recovery path after a save failure — commit every pending edit to the sidecar,
     * then re-filter the current drill so accepted items refresh out of scope. */
    private void applyStaged() {
        if (staging == null || staging.size() == 0) return;
        try {
            staging.apply();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                                          "Could not save the pending changes: " + ex.getMessage(),
                                          "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        acceptedOutcome.setText(" ");
        updateApplyButton();
        refreshCoverage();
        onCurated.run();
        if (identityTask) showIdentityMembers(identityDrill);
        else if (selectedFieldPath != null) onFieldSelected();
    }

    private void updateIdentityButton() {
        ManualCuration curation = curationStore();
        boolean enabled = selected != null && curation != null;
        exploreIdentityButton.setEnabled(enabled);
        String qid = enabled ? identityQid(selected) : null;
        exploreIdentityButton.setText(qid == null
                                              ? "Explore Wikidata…"
                                              : "Change Wikidata identity (" + qid + ")…");
        exploreIdentityButton.setToolTipText(enabled
                                                     ? "Find and approve the exact Wikidata entity for the selected instance"
                                                     : "Select a member card first");
    }

    private DomainField selectedDomainField() {
        if (selectedFieldType == null || selectedFieldPath == null) return null;
        return domain.fields(selectedFieldType).stream()
                     .filter(field -> selectedFieldPath.equals(field.field()))
                     .findFirst().orElse(null);
    }

    /** Stage an ordinary field edit as a Correction on the selected field. Identity
     * deliberately does not come through here: provenance identity is approved and staged as an
     * IdentityLink. */
    private void saveManualValue(CorrectionPolicy policy) {
        ManualCuration store = curationStore();
        if (store == null || staging == null || selected == null
                || selectedFieldPath == null) return;
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
            // A pending replacement is the current session's value; consult it before the
            // durable entry when accumulating another collection member.
            java.util.stream.Stream.concat(
                        staging.corrections().stream(), store.corrections().stream())
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
        Correction pending = new Correction(memberType, selected.getIdentifier(),
                                            selectedFieldPath, value, Correction.MANUAL, null, policy, null);
        staging.stage(pending);
        // Apply only the detached overlay for preview; the durable store is unchanged.
        Corrections.apply(domain.instances(), List.of(staging));
        refreshCoverage();
        manualValue.setText("");
        onCurated.run();
        // Re-render (no re-filter) so the just-staged value shows and its instance stays.
        renderFieldDrill();
    }

    private void refreshCoverage() {
        coverageColumns.invalidate();
        coverage.repaint();
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
        } else {
            manualValue.setText("");
        }
        updateIdentityButton();
    }

    private void selectedCardsChanged(List<Object> values) {
        selectedCards = values == null ? List.of() : values.stream()
                .filter(Viewable.class::isInstance).map(Viewable.class::cast).toList();
        updateBatchTargetLabel();
    }

    private List<Viewable> actionInstances() {
        return selectedCards.isEmpty() ? List.copyOf(drilledInstances) : selectedCards;
    }

    private void updateBatchTargetLabel() {
        batchTargetLabel.setText(selectedCards.isEmpty()
                ? "Batch actions: all " + drilledInstances.size() + " cards in this scope"
                : "Batch actions: " + selectedCards.size() + " selected card"
                        + (selectedCards.size() == 1 ? "" : "s"));
    }

    private String concreteType(Viewable member) {
        String concrete = domain.mostSpecificClass(member);
        if (concrete != null && !concrete.isBlank()) {
            return concrete;
        }
        return member != null && member.typeName() != null
                && !member.typeName().isBlank() ? member.typeName() : type;
    }

    /**
     * The configured field chooses the class context for an operation. A value may also
     * carry an unrelated direct class with a deeper hierarchy (the Assessors of Maat
     * entity is both {@code DeityGroup} and {@code EgyptianDeity}); that unrelated stamp
     * must not make {@code DeityGroup.members} look absent. A real subtype of the chosen
     * class keeps its subtype context so inherited-field corrections still address its
     * concrete instance identity.
     */
    static String fieldContextType(
            domain.DomainModel domain, Viewable member, String selectedType) {
        String concrete = domain == null || member == null
                ? null : domain.mostSpecificClass(member);
        return concrete != null && domain.isSubclassOf(concrete, selectedType)
                ? concrete : selectedType;
    }

    /** The Wikidata QID to enrich from: the member's own identifier if it IS a QID, else
     *  one carried by an approved Wikidata identity selected in Explore.
     *  Null when there's none — QID-only providers skip, name-based ones still run. */
    private static String resolvedQid(Viewable member,
                                      List<SourceRef> sources) {
        String id = member.getIdentifier();
        if (isQid(id)) {
            return id;
        }
        for (SourceRef source : sources) {
            if ("Wikidata".equalsIgnoreCase(source.kind())
                    && isQid(source.sourceId())) {
                return source.sourceId();
            }
        }
        return null;
    }

    // The shared instance rendering: selectable, searchable cards (same components the
    // curation panel uses), so a missing member is the object — click to select it.
    private JComponent instancesView(List<Viewable> missing, String type) {
        return objectview.view.SearchableView.builder(missing)
                .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                                                   .sample(missing.get(0))
                                                   .hiddenFields(domain.structuralFields(type))
                                                   .fieldTypes(domain.fieldTypes(type))
                                                   .fieldSchemas(q -> domain.fieldSchema(q.typeName()))
                                                   .typeSchemas(domain::fieldSchema)
                                                   .cardDecorator(member -> IdentityChip.of(identityQid(member)))
                                                   .collapsible(true)
                                                   .selectionListener(o -> {
                                                       selectedChanged(o instanceof Viewable q ? q : null);
                                                   })
                                                   .selectionSetListener(this::selectedCardsChanged)
                                                   .build();
    }

    private static boolean has(Viewable q, String path) {
        return FieldCoverageColumns.hasValue(q, FieldPath.parse(path));
    }
}
