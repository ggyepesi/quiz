package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldPath;
import objectview.field.FieldSet;
import objectview.search.SearchPanel;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;
import quiz.enrichment.EnrichmentDecisionApplier;
import quiz.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentRequest;
import quiz.enrichment.EnrichmentRoute;
import quiz.enrichment.EnrichmentSources;
import quiz.enrichment.FindDataProcess;
import quiz.enrichment.FindDataBatchProcess;
import quiz.enrichment.FindDataBatchResult;
import quiz.enrichment.SourcePageImageEnrichmentProvider;
import quiz.enrichment.WikimediaImageEnrichmentProvider;
import quiz.curation.Correction;
import quiz.curation.CorrectionPolicy;
import quiz.curation.Corrections;
import quiz.curation.CurationStaging;
import quiz.curation.IdentityLink;
import quiz.curation.ScopeFilter;

import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.workbench.IdentityChip;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.RuleDirection;
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
    private final JButton exploreIdentityButton = new JButton("Explore Wikidata…");
    private final JButton cancelProcessButton = new JButton("Cancel Find Data");
    private final JButton resolveMissingButton = new JButton("Resolve identities…");
    private final JButton applyButton = new JButton("Save staged changes");
    private final JLabel selectedLabel = new JLabel("No instance selected");
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
    private final Map<FieldKey, FieldSourceMapping> fieldSources = new java.util.HashMap<>();
    // A fallback is an additional route, not a replacement for the field's primary source.
    // Keeping both mappings in the same ModelBuilder representation lets Find Data try
    // Wikidata first and consult DBpedia only for members where that property is missing.
    private final Map<FieldKey, FieldSourceMapping> fallbackFieldSources =
            new java.util.HashMap<>();
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
    private List<Viewable> drilledInstances = List.of();
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

    /** The concrete member used to discover a field source. Keep its readable name beside
     *  the QID so Explore makes the probe target unambiguous. */
    private record ExploreSeed(String qid, String label) {}

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
        coverageColumns.onRepairNames(this::showUnnamedReferences);
        coverage = new ViewConfigEditor(new ViewConfig(), (Viewable) null, coverageColumns);
        coverage.setChangeListener(this::onFieldSelected);

        checkButton.addActionListener(e -> onCheck());
        fieldSourceButton.addActionListener(e -> chooseSourceForSelected(false));
        resolveNamesButton.addActionListener(e -> resolveReferenceNames());
        fieldSourceDbpediaButton.setToolTipText(
                "Pick a Wikipedia infobox (DBpedia) property from a sample member — "
                        + "the fallback source for values Wikidata lacks");
        fieldSourceDbpediaButton.addActionListener(e -> chooseDbpediaSourceForSelected());
        queryRunner.registerRunButton(checkButton);
        findDataRunner.registerRunButton(checkButton);
        exploreIdentityButton.addActionListener(e -> exploreIdentity());
        exploreIdentityButton.setEnabled(false);
        applyButton.addActionListener(e -> applyStaged());
        applyButton.setEnabled(false);
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
        bar.add(identityStatus);
        bar.add(showIdentifiedButton);
        bar.add(showUnresolvedButton);
        bar.add(showUnnamedButton);
        // Apply is a transaction-level action, not an action of the currently visible
        // field drill. Keep it reachable even when a filter has zero rows or the user
        // navigates to another type after staging an edit.
        bar.add(applyButton);
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
        JPanel summary = new JPanel(
                new objectview.utils.swing.WrapLayout(FlowLayout.LEFT, 6, 4));
        summary.add(new JLabel("Curating:"));
        summary.add(new JLabel(type + "." + field));
        if (scopeDescription != null && !scopeDescription.isBlank()) {
            summary.add(new JLabel("· " + scopeDescription));
        }
        summary.add(applyButton);
        summary.add(cancelProcessButton);
        add(summary, BorderLayout.NORTH);
        add(instancesHolder, BorderLayout.CENTER);
        revalidate();
        repaint();
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
        selectedChanged(null);
        instancesHolder.removeAll();
        List<Viewable> matching = drilledInstances;
        instancesHolder.add(scrollableActions(
                header(selectedFieldType, selectedFieldPath, matching.size())),
                BorderLayout.NORTH);
        if (!matching.isEmpty()) {
            instancesHolder.add(instancesView(matching, selectedFieldType), BorderLayout.CENTER);
        }
        instancesHolder.revalidate();
        instancesHolder.repaint();
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

    /** APPROVE stage indicator — one button commits every staged edit (manual values +
     *  identities) in a single save. */
    private void updateApplyButton() {
        int n = staging == null ? 0 : staging.size();
        applyButton.setEnabled(n > 0);
        // "Save", not "Apply": a result panel's Apply STAGES, and this is the only
        // control that writes. Two buttons both reading Apply left the reader unable to
        // tell whether anything had been persisted.
        applyButton.setText(n == 0 ? "Save staged changes" : "Save " + n + " staged change"
                + (n == 1 ? "" : "s"));
        applyButton.setToolTipText(n == 0
                                           ? "No staged changes"
                                           : "Write " + n + " staged change(s) to the curation sidecar");
    }

    private boolean hasIdentity(Viewable member) {
        return identityQid(member) != null;
    }

    private String identityQid(Viewable member) {
        String anchored = quiz.source.SourceIdentities.wikidataQid(member);
        if (anchored != null) return anchored;
        // A just-approved identity is staged (not yet in the durable curation), so the
        // preview must read pending links too — otherwise the resolved instance shows blank.
        String staged = stagedQid(member);
        if (staged != null) return staged;
        return resolvedQid(member, EnrichmentSources.collect(
                member, concreteType(member), curationStore()));
    }

    /** The Wikidata QID of a pending (staged, not yet applied) identity for {@code member}.
     *  Matching is {@link quiz.curation.IdentityLinks}' job — a link written under the
     *  instance's base class must still be found once it carries a subclass or a role. */
    private String stagedQid(Viewable member) {
        if (staging == null || member == null) return null;
        return staging.identityLinks().stream()
                      .filter(l -> quiz.curation.IdentityLinks.matches(
                              l, member, quiz.curation.IdentityLinks.WIKIDATA))
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
        boolean media = field != null && field.kind() == objectview.field.FieldKind.MEDIA;

        // Wikidata: identity is datasource-dependent, so it sits with the Wikidata source;
        // the property is what yields this field's value.
        JPanel wikidata = titledBlock("Wikidata");
        if (identityResolver != null) {
            resolveMissingButton.setText("Identities…");
            resolveMissingButton.setToolTipText(
                    "Resolve or change the Wikidata identity of these instances "
                            + "(needed before a value can be fetched)");
            wikidata.add(headerLine(new JLabel("Identity"), resolveMissingButton));
        }
        if (!media) {
            updateFieldSourceButton();   // "Source: population (P1082)…" or "Choose Wikidata source…"
            wikidata.add(headerLine(new JLabel("Property"), fieldSourceButton));
        }
        target.add(wikidata);

        if (!media) {
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
            target.add(fallback);
        }

        // Repairing a reference's NAME is not the same job as filling the field: the
        // value is already right, only its target has no label. So it is offered
        // wherever the selected field HAS such references — gating it behind the
        // matching scope hid the cure from the column that reports the disease.
        int repairable = unnamedTargets().size();
        if (repairable > 0) {
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
            names.add(headerLine(
                    new JLabel("References showing a QID instead of a name:"),
                    showThese, resolveNamesButton));
            target.add(names);
        }

        // Load is available for MEDIA too: findData fetches images per entity from their
        // media provider (no property to pick), so an image field is fillable, not inert.
        checkButton.setText("Load values ↗");
        checkButton.setToolTipText(media
                ? "Fetch images for these members from Wikidata (P18) / Wikimedia Commons"
                : "Fetch values for these members from the configured source(s)");
        target.add(headerLine(checkButton));

        // The by-hand path, kept visually separate from the source-driven load.
        JPanel manual = titledBlock("Or set one manually");
        manual.add(headerLine(new JLabel("Selected:"), selectedLabel));
        if (!media) {
            addValueButton.setVisible(field != null && field.collection());
            manual.add(headerLine(manualValue, setValueButton, addValueButton));
        }
        target.add(manual);

        target.revalidate();
        target.repaint();
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
        String path = selectedFieldPath;
        if (path == null) return;
        quiz.curation.ManualCuration curation = curationStore();
        // Let a manually identified selected member establish its source before the
        // batch. Other unresolved manual members are skipped, not met with a dialog storm.
        if (selected != null && EnrichmentSources.needsSelection(
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
        if (!media && sourceFor(key) == null
                && !fallbackFieldSources.containsKey(key)) {
            if (chooseSourceForSelected(true)) return;
            // No route is configured and no member resolves to a Wikidata entity yet —
            // fall through so runFillBatch reports that identities must be resolved first.
        }
        if (!confirmFill(key, media)) {
            return;
        }
        runFillBatch(path);
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
        plan.append("Members: <b>").append(drilledInstances.size()).append("</b> ")
            .append(scopeDescription(scopeFilter)).append("<br>");
        if (media) {
            plan.append("Source: Wikidata image (P18) / Wikimedia Commons<br>");
        } else {
            plan.append("Wikidata: ").append(describe(source)).append("<br>");
            plan.append("Wikipedia fallback: ").append(fallback == null
                    ? "none — Wikidata only" : describe(fallback)).append("<br>");
        }
        plan.append("<br>One request per member. Nothing is written yet: every value ")
            .append("comes back for review, and Apply is still a separate step.</html>");

        return JOptionPane.showConfirmDialog(
                this, new JLabel(plan.toString()), "Load values",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
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
        quiz.enrichment.ResolveNamesProcess resolveProcess =
                new quiz.enrichment.ResolveNamesProcess(unnamed, field);
        process.swing.workflow.ProcessWorkflowAction<
                quiz.enrichment.ResolveNamesProcess.Result,
                quiz.enrichment.ResolveNamesProcess.Name> action =
                new process.swing.workflow.ProcessWorkflowAction<>() {
                    @Override public String id() { return "resolve-reference-names"; }
                    @Override public process.swing.workflow.ProcessWorkflowPlan plan() {
                        return new process.swing.workflow.ProcessWorkflowPlan(
                                "Resolve names", "Fetch names for exactly these referenced entities.",
                                List.of(new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                        "Will query", targets)));
                    }
                    @Override public process.Process<quiz.enrichment.ResolveNamesProcess.Result>
                    process() { return resolveProcess; }
                    @Override public process.swing.workflow.ProcessWorkflowResults<
                            quiz.enrichment.ResolveNamesProcess.Name> results(
                                    process.ProcessOutcome<quiz.enrichment.ResolveNamesProcess.Result>
                                            outcome) {
                        List<process.swing.workflow.ProcessWorkflowResults.Card<
                                quiz.enrichment.ResolveNamesProcess.Name>> resolved =
                                new ArrayList<>();
                        List<process.swing.workflow.ProcessWorkflowResults.Card<
                                quiz.enrichment.ResolveNamesProcess.Name>> unresolved =
                                new ArrayList<>();
                        for (String qid : outcome.result().requested()) {
                            String label = outcome.result().resolved().get(qid);
                            boolean found = label != null && !label.isBlank()
                                    && !label.equals(qid);
                            quiz.transform.DynamicViewable card =
                                    new quiz.transform.DynamicViewable(qid, found ? label : qid);
                            card.type(found ? "Resolved name" : "Unresolved name");
                            card.put("QID", qid);
                            card.put("Result", found ? label : "No name returned");
                            var resultCard = new process.swing.workflow.ProcessWorkflowResults.Card<>(
                                    card, found
                                            ? () -> new quiz.enrichment.ResolveNamesProcess.Name(
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
                            List<quiz.enrichment.ResolveNamesProcess.Name> decisions) {
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
        quiz.transform.DynamicViewable target =
                new quiz.transform.DynamicViewable(qid, qid);
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
    private void stageNames(List<quiz.enrichment.ResolveNamesProcess.Name> accepted) {
        if (staging == null || accepted == null) return;
        int staged = 0;
        for (quiz.enrichment.ResolveNamesProcess.Name name : accepted) {
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
            List<quiz.enrichment.ResolveNamesProcess.Name> accepted) {
        if (selectedFieldPath == null || selectedFieldType == null) return;
        FieldPath path = FieldPath.parse(selectedFieldPath);
        if (!domain.entityOrigin(selectedFieldType, path)) return;
        Map<String, String> byQid = new LinkedHashMap<>();
        for (quiz.enrichment.ResolveNamesProcess.Name name : accepted) {
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
        wikidata.explore.workbench.ExploreByExamplePanel.findProperty(
                this, queryRunner, seed.qid(), seed.label(),
                (pid, label) -> {
                    if (pid == null || pid.isBlank()) return;
                    FieldSourceMapping source = new FieldSourceMapping();
                    source.sourceType(FieldSourceType.SPARQL);
                    source.propertyPid(pid);
                    source.propertyLabel(label);
                    source.direction(RuleDirection.ROOT_TO_ITEM);
                    source.productionKind(FieldProductionKind.AUTO);
                    fieldSources.put(key, source);
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

    /** Pick a Wikipedia-infobox (DBpedia) property from a sample member and set it as the
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
        wikidata.explore.workbench.DbpediaPropertyPicker.findProperty(
                this, queryRunner, List.of(seed.qid()),
                (property, example) -> {
                    if (property == null || property.isBlank()) return;
                    FieldSourceMapping source = new FieldSourceMapping();
                    source.sourceType(FieldSourceType.DBPEDIA);
                    source.propertyPid(property);
                    source.propertyLabel("DBpedia infobox property");
                    source.productionKind(FieldProductionKind.AUTO);
                    fallbackFieldSources.put(key, source);
                    updateFieldSourceButton();
                });
    }

    private FieldSourceMapping sourceFor(FieldKey key) {
        if (key != null && !fieldSources.containsKey(key)) {
            // The model's declaration first: it is what the domain was generated with,
            // so asking for it again is asking the user to re-enter known configuration.
            // Past corrections are the fallback, for a field the model never declared.
            if (!seedFieldSourceFromModel(key)) {
                seedFieldSourceFromCuration(key);
            }
        }
        return key == null ? null : fieldSources.get(key);
    }

    /** Seed this field's source from the backing ModelBuilder model. True when the model
     *  declared one — {@code locations -> P840} is already in movies.model.json, and
     *  re-asking for it was the gap this closes. */
    private boolean seedFieldSourceFromModel(FieldKey key) {
        if (!(domain instanceof quiz.curation.FieldRulePromoter modelBacked)) {
            return false;
        }
        FieldSourceMapping declared = modelBacked.declaredSource(key.type(), key.path());
        if (declared == null || declared.propertyPid() == null
                || declared.propertyPid().isBlank()) {
            return false;
        }
        fieldSources.put(key, declared);
        return true;
    }

    /** Reuse a property already recorded for this field rather than re-discovering it: every
     *  filled value in the curation sidecar carries the source ({@code propertyId}) that
     *  produced it, so a field curated before (e.g. population -> P1082) is seeded from that
     *  provenance. The most-used property wins when several appear. */
    private void seedFieldSourceFromCuration(FieldKey key) {
        quiz.curation.ManualCuration curation = curationStore();
        if (key == null || curation == null) {
            return;
        }
        FieldSourceMapping reused = reusableSource(
                curation.corrections(), key.type(), key.path());
        if (reused != null) {
            fieldSources.put(key, reused);
        }
    }

    /** The primary (Wikidata) source to reuse for {@code type.field}, rebuilt from the
     *  provenance already recorded on that field's filled values — the most-used property
     *  when several appear — or null when nothing was ever sourced for it. */
    static FieldSourceMapping reusableSource(
            List<Correction> corrections, String type, String field) {
        java.util.Map<String, quiz.curation.ValueSource> byPid = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (Correction c : corrections == null ? List.<Correction>of() : corrections) {
            if (!type.equals(c.type()) || !field.equals(c.field())) {
                continue;
            }
            quiz.curation.ValueSource s = c.source();
            // Primary (Wikidata) provenance only; the DBpedia fallback is a separate slot.
            if (s == null || s.propertyId() == null || s.propertyId().isBlank()
                    || !"Wikidata".equalsIgnoreCase(s.kind())) {
                continue;
            }
            byPid.putIfAbsent(s.propertyId(), s);
            counts.merge(s.propertyId(), 1, Integer::sum);
        }
        if (byPid.isEmpty()) {
            return null;
        }
        quiz.curation.ValueSource best = byPid.get(counts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElseThrow());
        FieldSourceMapping mapping = new FieldSourceMapping();
        mapping.sourceType(FieldSourceType.SPARQL);
        mapping.propertyPid(best.propertyId());
        mapping.propertyLabel(best.propertyLabel() == null ? "" : best.propertyLabel());
        mapping.direction(directionOf(best.direction()));
        mapping.productionKind(FieldProductionKind.AUTO);
        return mapping;
    }

    private static RuleDirection directionOf(String direction) {
        try {
            return direction == null ? RuleDirection.ROOT_TO_ITEM
                    : RuleDirection.valueOf(direction);
        } catch (IllegalArgumentException e) {
            return RuleDirection.ROOT_TO_ITEM;
        }
    }

    private FieldSourceMapping fallbackSourceFor(FieldKey key) {
        return key == null ? null : fallbackFieldSources.get(key);
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
                "Choose the DBpedia fallback used only when Wikidata has no usable value");
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

    /** The first missing member that carries (or resolves to) a QID — the sample entity
     *  whose properties the picker lists. */
    private ExploreSeed sampleForSourceDiscovery(quiz.curation.ManualCuration curation) {
        for (Viewable member : drilledInstances) {
            if (!domain.isInstanceOf(member, selectedFieldType)) continue;
            String qid = resolvedQid(member,
                                     EnrichmentSources.collect(member, concreteType(member), curation));
            if (qid != null) return new ExploreSeed(qid, member.getDisplayName());
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

        findDataRunner.run(
                new FindDataBatchProcess(jobs, skipped, path),
                outcome -> SwingUtilities.invokeLater(() -> {
                    FindDataBatchResult result = outcome.result();
                    quiz.curation.ManualCuration store = curationStore();
                    if (result != null && store != null) {
                        List<quiz.enrichment.EnrichmentProposal> proposals = result.results()
                                .stream().map(quiz.enrichment.FindDataResult::proposal).toList();
                        quiz.enrichment.ui.FindDataBatchReviewPanel.showModeless(
                                this,
                                "Review found data" + (path.isBlank() ? "" : " — " + path),
                                "Review the values found for these members.", proposals,
                                reviewed -> reviewed.accepted().forEach(
                                        decision -> applyDecision(store, decision)));
                    }
                    if (outcome.status() == ProcessStatus.FAILED && outcome.error() != null) {
                        JOptionPane.showMessageDialog(ValidationPanel.this,
                                                      "Lookup failed: " + outcome.error().getMessage());
                    }
                }),
                ex -> JOptionPane.showMessageDialog(ValidationPanel.this,
                                                    "Lookup failed: " + ex.getMessage()));
    }

    private EnrichmentRoute routeFor(
            boolean media, String qid, String path) {
        if (!media) {
            FieldKey key = new FieldKey(selectedFieldType, path);
            return FieldEnrichmentRoutes.from(
                    sourceFor(key), fallbackSourceFor(key), referenceResolver);
        }
        List<quiz.enrichment.EnrichmentProvider> providers = new ArrayList<>();
        providers.add(new WikimediaImageEnrichmentProvider());
        providers.add(new SourcePageImageEnrichmentProvider());
        if (qid == null) providers.add(new DBpediaImageEnrichmentProvider());
        return EnrichmentRoute.all(providers);
    }

    private static boolean isQid(String id) {
        return quiz.source.WikidataSource.isQid(id);
    }


    private quiz.curation.ManualCuration curationStore() {
        return domain instanceof quiz.curation.Curatable c ? c.curation() : null;
    }

    private void applyDecision(
            quiz.curation.ManualCuration curation,
            quiz.enrichment.EnrichmentDecision decision) {
        try {
            // Find Data has its own reviewed, durable transaction. It cannot persist the
            // detached manual/identity staging session, which remains pending afterwards.
            EnrichmentDecisionApplier.apply(domain, curation, decision);
            updateApplyButton();
            refreshCoverage();
            onCurated.run();
            onFieldSelected();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    private void exploreIdentity() {
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a member card first.");
            return;
        }
        Viewable target = selected;
        wikidata.explore.workbench.ExploreByExamplePanel.showPicker(
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
        queryRunner.runQuiet(new quiz.enrichment.WikimediaEntityLookup().byQid(qid),
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
        // Written under the stable identity type — see quiz.curation.IdentityLinks.
        String targetType = quiz.curation.IdentityLinks.stableType(target);
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

    /** APPROVE stage — commit every staged edit (manual values + identities) to the sidecar
     *  in one save, then re-filter the current drill so approved items refresh out of scope. */
    private void applyStaged() {
        if (staging == null || staging.size() == 0) return;
        try {
            staging.apply();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                                          "Could not save the staged changes: " + ex.getMessage(),
                                          "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        updateApplyButton();
        refreshCoverage();
        onCurated.run();
        if (identityTask) showIdentityMembers(identityDrill);
        else if (selectedFieldPath != null) onFieldSelected();
    }

    private void updateIdentityButton() {
        quiz.curation.ManualCuration curation = curationStore();
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
        quiz.curation.ManualCuration store = curationStore();
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

    private String concreteType(Viewable member) {
        String concrete = domain.mostSpecificClass(member);
        if (concrete != null && !concrete.isBlank()) {
            return concrete;
        }
        return member != null && member.typeName() != null
                && !member.typeName().isBlank() ? member.typeName() : type;
    }

    /** The Wikidata QID to enrich from: the member's own identifier if it IS a QID, else
     *  one carried by an approved Wikidata identity selected in Explore.
     *  Null when there's none — QID-only providers skip, name-based ones still run. */
    private static String resolvedQid(Viewable member,
                                      List<EnrichmentProposal.SourceRef> sources) {
        String id = member.getIdentifier();
        if (isQid(id)) {
            return id;
        }
        for (EnrichmentProposal.SourceRef source : sources) {
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
                .valueLinker(wikidata.explore.workbench.WikidataLinks.valueLinker())
                                                   .sample(missing.get(0))
                                                   .hiddenFields(domain.structuralFields(type))
                                                   .fieldTypes(domain.fieldTypes(type))
                                                   .fieldSchemas(q -> domain.fieldSchema(q.typeName()))
                                                   .cardDecorator(member -> IdentityChip.of(identityQid(member)))
                                                   .collapsible(true)
                                                   .selectionListener(o -> {
                                                       selectedChanged(o instanceof Viewable q ? q : null);
                                                   }).build();
    }

    private static boolean has(Viewable q, String path) {
        return FieldCoverageColumns.hasValue(q, FieldPath.parse(path));
    }
}
