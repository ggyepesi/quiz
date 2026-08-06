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
import quiz.enrichment.EnrichmentReviewRequest;
import quiz.enrichment.FindDataProcess;
import quiz.enrichment.FindDataBatchProcess;
import quiz.enrichment.FindDataBatchResult;
import quiz.enrichment.SourcePageImageEnrichmentProvider;
import quiz.enrichment.WikimediaImageEnrichmentProvider;
import quiz.enrichment.ui.EnrichmentReviewPanel;
import quiz.curation.Correction;
import quiz.curation.CorrectionPolicy;
import quiz.curation.Corrections;
import quiz.curation.CurationStaging;
import quiz.curation.IdentityLink;
import quiz.curation.ScopeFilter;

import wikidata.explore.query.swing.SwingQueryRunner;
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
    private final JButton applyButton = new JButton("Apply changes");
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

        // this.instances (the FIELD onType() fills with the selected type's members),
        // NOT the constructor parameter — which "Curate data" passes as null.
        coverageColumns = new FieldCoverageColumns(
                domain, () -> type, () -> this.instances);
        coverage = new ViewConfigEditor(new ViewConfig(), (Viewable) null, coverageColumns);
        coverage.setChangeListener(this::onFieldSelected);

        checkButton.addActionListener(e -> onCheck());
        fieldSourceButton.addActionListener(e -> chooseSourceForSelected(false));
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
            ScopeFilter chosen = (ScopeFilter) scopeCombo.getSelectedItem();
            if (chosen != null && chosen != scopeFilter) {
                scopeFilter = chosen;
                if (identityTask) showIdentityMembers(identityScope(chosen));
                else if (selectedFieldPath != null) onFieldSelected();
            }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JLabel("Type:"));
        bar.add(typeCombo);
        bar.add(new JLabel("Values:"));
        bar.add(scopeCombo);
        bar.add(status);
        bar.add(identityStatus);
        bar.add(showIdentifiedButton);
        bar.add(showUnresolvedButton);
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
        JPanel summary = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
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

    private void showIdentityScope(ScopeFilter filter) {
        identityTask = true;
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
        renderFieldDrill();
    }

    /** Render the field drill over the CURRENT drilledInstances (no re-filter), so a
     *  just-staged value keeps its instance visible until Apply. */
    private void renderFieldDrill() {
        selectedChanged(null);
        instancesHolder.removeAll();
        List<Viewable> matching = drilledInstances;
        instancesHolder.add(header(selectedFieldType, selectedFieldPath, matching.size()),
                            BorderLayout.NORTH);
        if (!matching.isEmpty()) {
            instancesHolder.add(instancesView(matching, selectedFieldType), BorderLayout.CENTER);
        }
        instancesHolder.revalidate();
        instancesHolder.repaint();
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
        applyButton.setText(n == 0 ? "Apply changes" : "Apply changes (" + n + ")");
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

    /** The Wikidata QID of a pending (staged, not yet applied) identity for {@code member}. */
    private String stagedQid(Viewable member) {
        if (staging == null || member == null) return null;
        String type = concreteType(member);
        String targetId = member.getIdentifier();
        return staging.identityLinks().stream()
                      .filter(l -> "Wikidata".equalsIgnoreCase(l.sourceKind()))
                      .filter(l -> java.util.Objects.equals(l.type(), type))
                      .filter(l -> java.util.Objects.equals(l.targetId(), targetId))
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
        if (identityResolver != null) {
            resolveMissingButton.setText("Resolve identities for " + count + " member(s)…");
            h.add(headerLine(resolveMissingButton));
        }

        h.add(headerLine(new JLabel("Curate " + fieldType + "." + path + ":")));
        populateCurateActions(h);
        return h;
    }

    private static JPanel headerLine(java.awt.Component... components) {
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        line.setAlignmentX(LEFT_ALIGNMENT);
        for (java.awt.Component component : components) line.add(component);
        return line;
    }

    private static String scopeDescription(ScopeFilter filter) {
        return switch (filter) {
            case MISSING -> "instance(s) with a missing value";
            case PRESENT -> "instance(s) with an existing value";
            case ALL -> "instance(s)";
        };
    }

    /** Actions are gated by the selected target field's kind:
     *   reference → out of scope; identity (source.*) → Explore identity only, never Find
     *   Data; data (scalar / media) → Find Data + manual value. */
    private void populateCurateActions(JPanel target) {
        CurateType ct = curateTypeOf(selectedFieldType, selectedFieldPath);
        if (ct == CurateType.REFERENCE) {
            JLabel note = new JLabel("— reference-field curation isn't supported yet");
            note.setEnabled(false);
            target.add(headerLine(note));
        } else {
            if (ct == CurateType.DATA) {
                DomainField field = selectedDomainField();
                if (field != null && field.kind() != objectview.field.FieldKind.MEDIA) {
                    updateFieldSourceButton();
                    target.add(headerLine(fieldSourceButton, fieldSourceDbpediaButton));
                }
                checkButton.setText("Find data ↗");
                checkButton.setToolTipText(
                        "Find and review values for the members in this task");
            }
            updateIdentityButton();
            target.add(ct == CurateType.DATA
                               ? headerLine(checkButton, exploreIdentityButton)
                               : headerLine(exploreIdentityButton));
            target.add(headerLine(new JLabel("Selected:"), selectedLabel));
            if (ct == CurateType.DATA) {
                DomainField field = selectedDomainField();
                addValueButton.setVisible(field != null && field.collection());
                target.add(headerLine(manualValue, setValueButton, addValueButton));
            }
        }
        target.revalidate();
        target.repaint();
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
        if (!media && !fieldSources.containsKey(key)
                && !fallbackFieldSources.containsKey(key)) {
            if (chooseSourceForSelected(true)) return;
            // No route is configured and no member resolves to a Wikidata entity yet —
            // fall through so runFillBatch reports that identities must be resolved first.
        }
        runFillBatch(path);
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
                        SwingUtilities.invokeLater(() -> runFillBatch(key.path()));
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
        return key == null ? null : fieldSources.get(key);
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

    private EnrichmentRoute routeFor(
            boolean media, String qid, String path) {
        if (!media) {
            FieldKey key = new FieldKey(selectedFieldType, path);
            return FieldEnrichmentRoutes.from(sourceFor(key), fallbackSourceFor(key));
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
        return ProcessInputHandler.unsupported().request(request, cancellation);
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
        String targetType = concreteType(target);
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
        return objectview.search.SearchableCardView.builder(missing)
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
