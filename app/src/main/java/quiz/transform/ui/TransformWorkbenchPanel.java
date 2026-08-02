package quiz.transform.ui;

import objectview.demo.MultiView;
import objectview.render.GroupTreeView;
import process.CancellationToken;
import process.ProcessInputHandler;
import process.ProcessInputRequest;
import process.ProcessStatus;
import process.swing.SwingProcessInput;
import process.swing.SwingProcessRunner;
import objectview.Viewable;
import quiz.ViewableGroup;
import quiz.transform.pipeline.ui.ViewStepsPanel;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.swing.SwingQuerySession;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural transform workbench over a {@link DomainModel} — a Wikidata snapshot or
 * a hand-written Viewable domain like Nobel / State / SportTeam
 * ({@link ReflectionDomain}). Pick a member class, then build its group tree — facet
 * and filter groups — whose members (the derived subdomain) render live on the right
 * via the shared card content view.
 *
 * <p>This is a THIN Swing view: all logic — the {@link WorkingDomain}, the group tree,
 * subclassing, saving — lives in {@link TransformController}. This class
 * owns only the widgets, forwards user actions to the controller, and turns the
 * controller's {@link ViewableGroup} result into cards.
 */
public final class TransformWorkbenchPanel extends JPanel implements AutoCloseable {

    private final TransformController controller;

    // The domain this workbench was opened for (e.g. "countries"), offered as the
    // default when re-saving, so an edit-and-save round-trips over the original.
    private final String domainName;

    private final JPanel renderHolder = new JPanel(new BorderLayout());
    private final JLabel scopeStatus = new JLabel("No rendered scope");
    private final JButton resolveButton = new JButton("Resolve identities…");
    // Applying resolved identities only mutates the in-memory curation; "Save identities"
    // persists it. So the user can inspect / fetch field data for the newly-identified
    // instances before committing, and a re-run isn't forced through a disk write.
    private final JButton saveIdentitiesButton = new JButton("Save identities");
    private final JButton forgetResultButton = new JButton("Forget result");
    private JPanel instanceScopeHeader;
    private quiz.transform.EditableGroup selectedGroup;
    // The group changes the instance scope, not the user's field choices. Dynamic
    // classes share a Java implementation, so the domain type name is the right key.
    private final java.util.Map<String, objectview.search.SearchPanel.ConfigState>
            instanceConfigsByType = new java.util.HashMap<>();
    // Single-result convention: after a resolve, its result is PENDING (applied in memory)
    // until the user saves or forgets it. While pending, the resolve button re-opens the
    // retained review instead of starting a new resolve; save persists it, forget reverts.
    private boolean identitiesDirty;
    private java.util.List<
            quiz.enrichment.ResolveIdentitiesReviewRequest.InstanceIdentity>
            lastReviewItems = List.of();
    private String lastReviewTitle = "Resolve identities";
    private String lastReviewPrompt = "";
    private java.util.List<quiz.enrichment.ResolveIdentitiesDecision.Resolved>
            lastApplied = List.of();
    private final WikidataSparqlClient requestClient = new WikidataSparqlClient(
            "QuizProject/1.0 (ggyepesi@gmail.com)", 2,
            WikidataSparqlClient.DBPEDIA_ENDPOINT);
    // SPARQL points at DBpedia (enrichment); the API client is Wikidata's (the identity
    // search runs in API mode via context.api()) — different transports, both needed.
    private final SwingQuerySession queries = new SwingQuerySession(new QueryContext(
            requestClient, new WikidataApiClient("QuizProject/1.0 (ggyepesi@gmail.com)")));
    private final JButton cancelQueryButton = new JButton("Cancel request");
    // Runs the identity-resolution process (off-EDT, with a review pause). Shares the
    // session's query context + log window, so its searches show in "Query logs…".
    private final SwingProcessRunner resolveRunner = new SwingProcessRunner(
            queries.runner().context(), queries.runner().logListener(), this::handleProcessInput);

    private ViewStepsPanel viewStepsPanel;
    private JDialog explorerDialog;

    // Bumped on every render() (EDT-only). A background render swaps its cards in
    // only if it's still the latest — so a slow earlier render can't overwrite a
    // newer one that finished first.
    private int renderGeneration;
    private RenderedScope renderedScope;
    private boolean closed;

    /** The exact immutable result successfully swapped into the right-hand browser. */
    private record RenderedScope(
            int generation, String selectedType, List<Viewable> members) {
        private RenderedScope {
            members = List.copyOf(members);
        }
    }

    public TransformWorkbenchPanel(DomainModel domain) {
        this(domain, null, null);
    }

    public TransformWorkbenchPanel(DomainModel domain, DomainWriter writer) {
        this(domain, writer, null);
    }

    public TransformWorkbenchPanel(DomainModel domain, DomainWriter writer, String domainName) {
        this.controller = new TransformController(domain, writer);
        this.domainName = domainName;
        setLayout(new BorderLayout(8, 8));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), buildRight());
        split.setResizeWeight(0.42);
        add(split, BorderLayout.CENTER);

        // ViewStepsPanel seeds the controller (and mirrors it into its controls);
        // render the seeded result once the panel is wired.
        render();
    }

    private JComponent buildLeft() {
        JPanel left = new JPanel(new BorderLayout(6, 6));

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (controller.domain() instanceof SchemaView) {
            top.add(button("Schema…", this::showSchema));
        }
        if (controller.canSave()) {
            top.add(button("Save as domain…", this::saveAsDomain));
        }
        top.add(button("New field…", this::addNewField));
        top.add(button("Explore…", this::showExplorer));
        top.add(button("Validate…", this::showValidation));
        top.add(button("Query logs…", () -> queries.showLogs(this)));
        queries.runner().registerCancelButton(cancelQueryButton);
        queries.runner().cancelAction(requestClient::cancelCurrentQuery);
        top.add(cancelQueryButton);
        toolbar.add(top);

        if (controller.domain() instanceof quiz.curation.Curatable c && c.curation() != null) {
            JPanel curationActions = new JPanel(
                    new FlowLayout(FlowLayout.LEFT, 4, 2));
            curationActions.setAlignmentX(Component.LEFT_ALIGNMENT);
            curationActions.add(new JLabel("Curation:"));
            curationActions.add(button("Curate data…",
                    () -> openCuration(null)));
            curationActions.add(button("Merge duplicates…",
                    () -> openMerge(c.curation())));
            curationActions.add(button("Overview…",
                    () -> openCurationOverview(c.curation())));
            toolbar.add(curationActions);
        }
        if (toolbar.getComponentCount() > 0) {
            left.add(toolbar, BorderLayout.NORTH);
        }

        viewStepsPanel = new ViewStepsPanel(
                controller, this::render, this::addFilterGroup);
        left.add(viewStepsPanel, BorderLayout.CENTER);

        return left;
    }

    /** Scope-level actions live beside the instances they operate on, not among pipeline tools. */
    private JComponent buildRight() {
        JPanel right = new JPanel(new BorderLayout(4, 4));
        JPanel scope = new JPanel(new BorderLayout(8, 2));
        scope.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        scope.add(scopeStatus, BorderLayout.CENTER);
        resolveButton.addActionListener(e -> {
            if (resolveRunner.isRunning()) {
                resolveButton.setText("Cancelling identity resolution…");
                resolveButton.setEnabled(false);
                resolveRunner.cancel();
            } else if (!lastReviewItems.isEmpty()) {
                recallLastResult();   // a result is pending — show it, don't re-resolve
            } else {
                resolveRenderedIdentities();
            }
        });
        resolveButton.setToolTipText(
                "Resolve unresolved Wikidata identities in the exact rendered scope");
        resolveButton.setEnabled(false);
        saveIdentitiesButton.setToolTipText(
                "Persist the pending identities to the curation store");
        saveIdentitiesButton.setEnabled(false);
        saveIdentitiesButton.addActionListener(e -> saveIdentities());
        forgetResultButton.setToolTipText(
                "Revert the pending identities (remove the links applied in memory)");
        forgetResultButton.setEnabled(false);
        forgetResultButton.addActionListener(e -> forgetLastResult());
        JPanel scopeButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        scopeButtons.add(forgetResultButton);
        scopeButtons.add(saveIdentitiesButton);
        scopeButtons.add(resolveButton);
        scope.add(scopeButtons, BorderLayout.EAST);
        instanceScopeHeader = scope;
        right.add(renderHolder, BorderLayout.CENTER);
        return right;
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Declare a new empty field on the selected class (a schema act). It appears in the
     *  field pool + at 0% coverage; open Validate to fill it (e.g. via Find Data). */
    private void addNewField() {
        String type = controller.selectedType();
        if (type == null) {
            JOptionPane.showMessageDialog(this, "Pick a member type first.");
            return;
        }
        wikidata.explore.workbench.FieldDefinitionPanel editor =
                new wikidata.explore.workbench.FieldDefinitionPanel();
        editor.availableTargetTypes(controller.types());
        editor.edit(new wikidata.explore.model.FieldDefinition(
                "", wikidata.explore.model.FieldType.STRING, "",
                wikidata.explore.model.FieldCardinality.SINGLE,
                wikidata.explore.model.FieldRenderMode.AUTO));
        int ok = JOptionPane.showConfirmDialog(this, editor, "New field on " + type,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        String invalid = editor.validationError();
        if (invalid != null) {
            JOptionPane.showMessageDialog(this, invalid);
            return;
        }
        wikidata.explore.model.FieldDefinition definition = editor.definition();
        if (controller.fieldSchema(type) != null
                && controller.fieldSchema(type).field(definition.name()) != null) {
            JOptionPane.showMessageDialog(this,
                    "Field already exists: " + type + "." + definition.name());
            return;
        }
        objectview.field.FieldRef field = FieldDefinitions.toFieldRef(definition);
        if (controller.addField(type, field)) {
            viewStepsPanel.refreshFields();
            render();
            openCurationPanel(type, definition.name());
        } else {
            JOptionPane.showMessageDialog(this,
                    "New field is only supported for snapshot-backed domains.");
        }
    }

    /** General Wikidata investigation, independent of identity resolution and of the
     * currently rendered group. Reuses ModelBuilder's relation/graph explorer without
     * exposing its model-mutating actions. */
    private void showExplorer() {
        if (explorerDialog == null) {
            wikidata.explore.workbench.ExploreByExamplePanel explorer =
                    new wikidata.explore.workbench.ExploreByExamplePanel();
            explorer.explorationOnly();
            explorer.setQueryRunner(queries.runner());
            explorerDialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                    "Explore Wikidata", Dialog.ModalityType.MODELESS);
            explorerDialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            explorerDialog.add(explorer, BorderLayout.CENTER);
            explorerDialog.setSize(900, 760);
            explorerDialog.setLocationRelativeTo(this);
        }
        explorerDialog.setVisible(true);
        explorerDialog.toFront();
    }

    /** Resolve the Wikidata identity of the SHOWN instances — the current scope (all /
     *  filtered / grouped members of the selected class). Field-independent, so it lives
     *  here over the view rather than in the field-drill Validate panel. */
    private void resolveRenderedIdentities() {
        RenderedScope scope = renderedScope;
        if (scope == null) {
            JOptionPane.showMessageDialog(this, "Wait for the current view to finish rendering.");
            return;
        }
        resolveIdentities(scope.members(),
                (scope.selectedType() == null ? "View" : scope.selectedType())
                        + " rendered instances");
    }

    /** Resolve an explicit immutable list. Callers may be the rendered view, Validate's
     *  current gap, a filtered/grouped result, or a future manual selection. */
    private void resolveIdentities(List<Viewable> requested, String scopeLabel) {
        if (resolveRunner.isRunning()) {
            JOptionPane.showMessageDialog(this,
                    "An identity resolution is already running. Cancel it from the "
                            + "rendered-scope header before starting another.");
            return;
        }
        quiz.curation.ManualCuration curation = curation();
        if (curation == null) {
            JOptionPane.showMessageDialog(this,
                    "This domain has no curation store to record identities.");
            return;
        }
        List<Viewable> members = List.copyOf(requested == null ? List.of() : requested);
        if (members.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No instances are currently shown.");
            return;
        }
        List<quiz.enrichment.ResolveIdentitiesProcess.Subject> subjects = new ArrayList<>();
        for (Viewable member : members) {
            String memberType = member.typeName();
            subjects.add(new quiz.enrichment.ResolveIdentitiesProcess.Subject(
                    memberType, member.getIdentifier(), member.getDisplayName(),
                    currentQid(curation, memberType, member)));
        }
        setResolveRunning(true, scopeLabel, members.size());
        resolveRunner.run(
                new quiz.enrichment.ResolveIdentitiesProcess(subjects, 12),
                outcome -> SwingUtilities.invokeLater(() -> {
                    setResolveRunning(false, scopeLabel, members.size());
                    quiz.enrichment.ResolveIdentitiesDecision decision = outcome.result();
                    if (decision != null) {
                        applyResolvedIdentities(curation, decision);
                    }
                    if (outcome.status() == ProcessStatus.FAILED && outcome.error() != null) {
                        JOptionPane.showMessageDialog(TransformWorkbenchPanel.this,
                                "Resolve failed: " + outcome.error().getMessage());
                    }
                }),
                ex -> SwingUtilities.invokeLater(() -> {
                    setResolveRunning(false, scopeLabel, members.size());
                    JOptionPane.showMessageDialog(this,
                            "Resolve failed: " + ex.getMessage());
                }));
    }

    private void setResolveRunning(boolean running, String scopeLabel, int size) {
        if (running) {
            scopeStatus.setText("Resolving identities · " + size + " in " + scopeLabel
                    + " · Query logs remain available");
            resolveButton.setText("Cancel identity resolution");
            resolveButton.setEnabled(true);
        } else {
            updateScopeStatus();
        }
    }

    /** The instance's current qid: its own id when it IS a qid, else an approved Wikidata
     *  identity link — so already-resolved members are skipped by the process. */
    private static String currentQid(
            quiz.curation.ManualCuration curation, String type, Viewable member) {
        String id = member.getIdentifier();
        if (id != null && id.matches("Q\\d+")) {
            return id;
        }
        Object sourceQid = objectview.field.FieldAccess.getPath(member, "source.qid");
        if (sourceQid instanceof String text) {
            String qid = text.split("\\|", 2)[0].strip();
            if (qid.matches("Q\\d+")) return qid;
        }
        return curation.identityLinks().stream()
                .filter(link -> type.equals(link.type()) && id != null
                        && id.equals(link.targetId())
                        && "Wikidata".equalsIgnoreCase(link.sourceKind()))
                .map(quiz.curation.IdentityLink::sourceId)
                .findFirst().orElse(null);
    }

    private void applyResolvedIdentities(
            quiz.curation.ManualCuration curation, quiz.enrichment.ResolveIdentitiesDecision decision) {
        if (decision.resolved().isEmpty()) {
            return;
        }
        // In-memory only — the user inspects / fetches data for the newly identified
        // instances and then persists explicitly via "Save identities".
        java.util.Map<String, quiz.enrichment.ResolveIdentitiesDecision.Resolved> pending =
                new java.util.LinkedHashMap<>();
        for (quiz.enrichment.ResolveIdentitiesDecision.Resolved applied : lastApplied) {
            pending.put(identityKey(applied.type(), applied.targetId()), applied);
        }
        int changed = 0;
        for (quiz.enrichment.ResolveIdentitiesDecision.Resolved r : decision.resolved()) {
            if (hasIdentityLink(curation, r)) {
                continue;
            }
            quiz.curation.IdentityLink link = new quiz.curation.IdentityLink(
                    r.type(), r.targetId(), "Wikidata", r.qid(),
                    "https://www.wikidata.org/wiki/" + r.qid(), r.label(), "wikidata");
            curation.putIdentityLink(link);
            controller.domain().instances().stream()
                    .filter(member -> java.util.Objects.equals(member.typeName(), r.type())
                            && java.util.Objects.equals(member.getIdentifier(), r.targetId()))
                    .findFirst().ifPresent(member -> quiz.curation.IdentitySources.apply(
                            member, link));
            pending.put(identityKey(r.type(), r.targetId()), r);
            changed++;
        }
        if (changed == 0) {
            // Re-applying the retained, already-applied rows is a no-op. In
            // particular, a review containing only No match rows creates no dirty state.
            return;
        }
        lastApplied = List.copyOf(pending.values());
        identitiesDirty = !lastApplied.isEmpty();
        updateScopeStatus();   // pending state on the scope buttons, immediately
        render();
        JOptionPane.showMessageDialog(this,
                changed + " identity(ies) applied in memory —"
                        + " inspect them, then \"Save identities\" or \"Forget result\".");
    }

    private static boolean hasIdentityLink(
            quiz.curation.ManualCuration curation,
            quiz.enrichment.ResolveIdentitiesDecision.Resolved resolved) {
        return curation.identityLinks().stream().anyMatch(link ->
                java.util.Objects.equals(link.type(), resolved.type())
                        && java.util.Objects.equals(link.targetId(), resolved.targetId())
                        && "Wikidata".equalsIgnoreCase(link.sourceKind())
                        && java.util.Objects.equals(link.sourceId(), resolved.qid()));
    }

    private static String identityKey(String type, String targetId) {
        return String.valueOf(type) + '\u0000' + targetId;
    }

    /** Re-open the pending result's review so the user can inspect / re-decide it.
     *  Applying again updates the in-memory identities; it does not persist. */
    private void recallLastResult() {
        if (lastReviewItems.isEmpty()) {
            return;
        }
        quiz.enrichment.ui.ResolveIdentitiesReviewPanel.showModeless(
                this, lastReviewTitle, lastReviewPrompt, lastReviewItems, lastApplied,
                decision -> {
                    quiz.curation.ManualCuration curation = curation();
                    if (curation != null && decision != null
                            && !decision.resolved().isEmpty()) {
                        applyResolvedIdentities(curation, decision);
                    }
                });
    }

    /** Forget the pending result: remove the links it applied in memory. Since a pending
     *  result is by definition unsaved, this restores the pre-resolve state. */
    private void forgetLastResult() {
        quiz.curation.ManualCuration curation = curation();
        if (identitiesDirty && curation != null) {
            for (quiz.enrichment.ResolveIdentitiesDecision.Resolved r : lastApplied) {
                curation.removeIdentityLink(r.type(), r.targetId(), "Wikidata");
                controller.domain().instances().stream()
                        .filter(member -> java.util.Objects.equals(
                                member.typeName(), r.type())
                                && java.util.Objects.equals(
                                member.getIdentifier(), r.targetId()))
                        .findFirst().ifPresent(member ->
                                quiz.curation.IdentitySources.refresh(member, curation));
            }
        }
        clearPendingResult();
        render();
    }

    private void clearPendingResult() {
        identitiesDirty = false;
        lastReviewItems = List.of();
        lastApplied = List.of();
        saveIdentitiesButton.setEnabled(false);
        forgetResultButton.setEnabled(false);
        updateScopeStatus();
    }

    /** Persist the in-memory curation (identities applied since the last save).
     *  Returns false if the write failed, so the close flow can keep the window open. */
    private boolean saveIdentities() {
        quiz.curation.ManualCuration curation = curation();
        if (curation == null) {
            return true;
        }
        java.util.Set<String> appliedKeys = lastApplied.stream()
                .map(resolved -> identityKey(resolved.type(), resolved.targetId()))
                .collect(java.util.stream.Collectors.toSet());
        List<quiz.enrichment.ResolveIdentitiesReviewRequest.InstanceIdentity> unresolvedReview =
                lastReviewItems.stream()
                        .filter(item -> !appliedKeys.contains(
                                identityKey(item.type(), item.targetId())))
                        .toList();
        boolean keepReview = false;
        if (!unresolvedReview.isEmpty()) {
            Object[] options = {"Keep unresolved review", "Forget unresolved review", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this,
                    lastApplied.size() + " applied identity(ies) will be saved.\n"
                            + unresolvedReview.size() + " unresolved review item(s) remain.\n\n"
                            + "Keep their candidates and No match results for continued review?",
                    "Save identities", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (choice < 0 || choice == 2) return false;
            keepReview = choice == 0;
        }
        try {
            curation.save();
            identitiesDirty = false;
            lastApplied = List.of();
            lastReviewItems = keepReview ? List.copyOf(unresolvedReview) : List.of();
            saveIdentitiesButton.setEnabled(false);
            updateScopeStatus();
            JOptionPane.showMessageDialog(this, keepReview
                    ? "Identities saved. " + unresolvedReview.size()
                            + " unresolved review item(s) retained."
                    : "Identities saved.");
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            return false;
        }
    }

    private quiz.curation.ManualCuration curation() {
        return controller.domain() instanceof quiz.curation.Curatable c
                ? c.curation() : null;
    }

    /** True while identities have been applied in memory but not yet saved. */
    public boolean hasUnsavedIdentities() {
        return identitiesDirty && !lastApplied.isEmpty();
    }

    /** Renders the identity-resolution review pause; other input requests are unsupported. */
    private <T> T handleProcessInput(
            ProcessInputRequest<T> request, CancellationToken cancellation) throws Exception {
        if (request instanceof quiz.enrichment.ResolveIdentitiesReviewRequest resolve) {
            // Retain the review so the pending result can be re-opened (recall).
            lastReviewItems = resolve.instances();
            lastReviewTitle = resolve.title();
            lastReviewPrompt = resolve.prompt();
            quiz.enrichment.ResolveIdentitiesDecision answer =
                    SwingProcessInput.await(cancellation, completed ->
                    quiz.enrichment.ui.ResolveIdentitiesReviewPanel.showModeless(
                            this, resolve.title(), resolve.prompt(),
                            resolve.instances(), completed));
            return request.responseType().cast(answer);
        }
        return ProcessInputHandler.unsupported().request(request, cancellation);
    }

    /** Consistency validation over the full working schema (base + derived + facets):
     *  per-field coverage; drill a gap into the members missing it. Front of curate. */
    private void showValidation() {
        RenderedScope scope = renderedScope;
        if (scope == null) {
            JOptionPane.showMessageDialog(this, "Wait for the current view to finish rendering.");
            return;
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Validate — consistency / coverage", Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        // Validate the SHOWN view — the filtered + grouped members of the selected class —
        // not the whole pool.
        dialog.add(new ValidationPanel(controller.domain(), scope.members(),
                queries.runner(), this::render, this::resolveIdentities), BorderLayout.CENTER);
        dialog.setSize(1080, 620);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** Curate data on the full-population ValidationPanel — the single surface for
     * validation, identity, manual edits and Find Data. Coverage % is over ALL instances
     * (domain.instances()), never a filtered subset. Optionally drill a field on open. */
    private void openCuration(String initialField) {
        String type = renderedScope == null ? null : renderedScope.selectedType();
        openCurationPanel(type, initialField);
    }

    private ValidationPanel openCurationPanel(String type, String initialField) {
        // No explicit instance subset: coverage % is over domain.instances() (the panel's
        // default) — the fix for the workspace wrapper's wrong per-field percentages.
        ValidationPanel panel = new ValidationPanel(controller.domain(), null,
                queries.runner(), this::render, this::resolveIdentities);
        JDialog dialog = new JDialog(quiz.ui.Dialogs.owner(this),
                "Curate data — coverage over all instances", Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.add(panel, BorderLayout.CENTER);
        dialog.setSize(1160, 720);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        if (type != null) {
            SwingUtilities.invokeLater(() -> {
                if (initialField != null) {
                    panel.selectField(type, initialField, quiz.curation.ScopeFilter.MISSING);
                } else {
                    panel.selectType(type);
                }
            });
        }
        return panel;
    }

    /** Open the merge panel: fold a duplicate instance into a primary. Re-render after. */
    private void openMerge(quiz.curation.ManualCuration curation) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Merge — fold a duplicate instance into a primary", Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.add(new quiz.curation.ui.MergePanel(controller.domain(), curation, this::render),
                BorderLayout.CENTER);
        dialog.setSize(1000, 720);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** Inspect every current overlay directive without changing the sidecar. */
    private void openCurationOverview(quiz.curation.ManualCuration curation) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Curation overview — current directives",
                Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.add(new quiz.curation.ui.CurationOverviewPanel(
                controller.domain(), curation), BorderLayout.CENTER);
        dialog.setSize(1180, 760);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Viewable> sampleClass(Viewable q) {
        return (Class<? extends Viewable>) q.getClass();
    }

    /** Compile + group OFF the EDT (a big domain can be slow), then swap in the
     *  rendered cards on the EDT. */
    private void render() {
        // Capture the generation + inputs on the EDT so a slow render can be
        // discarded if superseded, and the worker never reads the pipeline while
        // it's being mutated.
        final int generation = ++renderGeneration;
        final String type = controller.selectedType();
        renderedScope = null;
        if (resolveRunner.isRunning()) {
            resolveButton.setText("Cancel identity resolution");
            resolveButton.setEnabled(true);
        } else {
            scopeStatus.setText("Rendering " + (type == null ? "view" : type) + "…");
            resolveButton.setEnabled(false);
        }

        renderHolder.removeAll();
        renderHolder.add(new JLabel("  Rendering…"), BorderLayout.NORTH);
        renderHolder.revalidate();
        renderHolder.repaint();

        new SwingWorker<objectview.group.ViewableGroup<?>, Void>() {
            @Override protected objectview.group.ViewableGroup<?> doInBackground() {
                return controller.groupRoot(type);
            }
            @Override protected void done() {
                if (generation != renderGeneration) {
                    return;   // a newer render started — don't overwrite it
                }
                try {
                    objectview.group.ViewableGroup<?> root = get();
                    renderHolder.removeAll();
                    renderHolder.add(groupView(
                            root, memberType(root, type), generation, type),
                            BorderLayout.CENTER);
                    updateScopeStatus();
                } catch (Exception ex) {
                    renderedScope = null;
                    scopeStatus.setText("Render failed");
                    resolveButton.setEnabled(false);
                    renderHolder.removeAll();
                    renderHolder.add(new JLabel("  Render failed: " + ex.getMessage()),
                            BorderLayout.NORTH);
                }
                renderHolder.revalidate();
                renderHolder.repaint();
            }
        }.execute();
    }

    /** A group's scope is exactly its explicit membership. Children never contribute
     *  members implicitly; hierarchy and membership are independent application choices. */
    private static List<Viewable> explicitMembers(
            objectview.group.ViewableGroup<?> group) {
        if (group == null || group.getMembers() == null) {
            return List.of();
        }
        return group.getMembers().stream()
                .filter(java.util.Objects::nonNull)
                .map(Viewable.class::cast)
                .toList();
    }

    private void updateScopeStatus() {
        if (resolveRunner.isRunning()) {
            return;
        }
        RenderedScope scope = renderedScope;
        if (scope == null) return;
        quiz.curation.ManualCuration curation =
                controller.domain() instanceof quiz.curation.Curatable c ? c.curation() : null;
        long identified = curation == null ? 0 : scope.members().stream()
                .filter(member -> currentQid(curation, member.typeName(), member) != null)
                .count();
        long unresolved = scope.members().size() - identified;
        scopeStatus.setText((scope.selectedType() == null ? "View" : scope.selectedType())
                + " · " + scope.members().size() + " shown · "
                + identified + " identified · " + unresolved + " unresolved");
        resolveButton.setText(unresolved == 0
                ? "All identified" : "Resolve " + unresolved + " identities…");
        resolveButton.setEnabled(curation != null && unresolved > 0 && !resolveRunner.isRunning());
        // A retained review takes over the button: saving accepted rows does not
        // silently discard ambiguous / No match work.
        if (!lastReviewItems.isEmpty()) {
            resolveButton.setText(identitiesDirty
                    ? "Show pending result…"
                    : "Continue unresolved review (" + lastReviewItems.size() + ")…");
            resolveButton.setEnabled(true);
        }
        saveIdentitiesButton.setEnabled(identitiesDirty);
        forgetResultButton.setText(identitiesDirty
                ? "Forget result" : "Forget unresolved review");
        forgetResultButton.setEnabled(!lastReviewItems.isEmpty());
    }

    /** A flat result: members grouped by type — a single searchable instance view
     *  for one type, or a per-class {@link MultiView} for several. */
    private JComponent flatView(List<Viewable> members, String type) {
        boolean oneHierarchy = type != null && members.stream()
                .allMatch(member -> controller.isInstanceOf(member, type));
        if (oneHierarchy) {
            Viewable sample = controller.configSample(type);
            java.util.List<objectview.search.SearchPanel.SubtypeConfig> subtypes =
                    new java.util.ArrayList<>();
            for (String subtype : controller.subtypesOf(type)) {
                java.util.Set<String> additional = controller.additionalFields(subtype);
                if (additional.isEmpty()) continue;
                subtypes.add(new objectview.search.SearchPanel.SubtypeConfig(
                        subtype, controller.baseType(subtype),
                        controller.configSample(subtype),
                        controller.fieldTypes(subtype), additional,
                        member -> controller.isInstanceOf(member, subtype)));
            }
            return objectview.search.SearchableCardView.builder(members)
                    .sample(sample)
                    .hiddenFields(controller.structuralFields(type))
                    .fieldTypes(controller.fieldTypes(type))
                    .fieldSchemas(q -> controller.fieldSchema(
                            controller.mostSpecificClass(q, type)))
                    .subtypeConfigs(subtypes)
                    .configState(instanceConfigsByType.get(type))
                    .configListener(config -> instanceConfigsByType.put(type, config))
                    .collapsible(true)
                    .build();
        }

        java.util.Map<String, List<Viewable>> byType = new java.util.LinkedHashMap<>();
        for (Viewable m : members) {
            if (m != null) {
                byType.computeIfAbsent(m.typeName(), k -> new ArrayList<>()).add(m);
            }
        }

        if (byType.size() <= 1) {
            String renderedType = byType.isEmpty()
                    ? type : byType.keySet().iterator().next();
            Viewable sample = controller.configSample(renderedType);
            return objectview.search.SearchableCardView.builder(members)
                    .sample(sample)
                    .hiddenFields(controller.structuralFields(renderedType))
                    .fieldTypes(controller.fieldTypes(renderedType))
                    .fieldSchemas(q -> controller.fieldSchema(q.typeName()))
                    .configState(instanceConfigsByType.get(renderedType))
                    .configListener(config ->
                            instanceConfigsByType.put(renderedType, config))
                    .collapsible(true)
                    .build();
        }

        MultiView mv = new MultiView();
        mv.context().setCollapsibleCards(true);
        mv.context().setFieldSchemaResolver(
                q -> controller.fieldSchema(q.typeName()));
        for (java.util.Map.Entry<String, List<Viewable>> e : byType.entrySet()) {
            String t = e.getKey();
            List<Viewable> objs = e.getValue();
            mv.addSection(t, sampleClass(objs.get(0)), objs,
                    objs.get(0), controller.structuralFields(t), controller.fieldTypes(t),
                    instanceConfigsByType.get(t),
                    config -> instanceConfigsByType.put(t, config));
        }
        mv.build(1);
        return mv;
    }

    /** A grouped (facet) result: a role-aware collapsible outline of the buckets —
     *  category ▸ year ▸ members — with a search / sort / fields bar above it (the
     *  data-centric counterpart to the flat view's SearchPanel). */
    private static String memberType(
            objectview.group.ViewableGroup<?> root, String fallback) {
        for (Viewable member : root.getMembers()) {
            if (!(member instanceof objectview.group.ViewableGroup<?>)) {
                return member.typeName();
            }
        }
        return fallback;
    }

    private JComponent groupView(
            objectview.group.ViewableGroup<?> root,
            String memberType,
            int generation,
            String selectedType) {
        GroupTreeView groups = new GroupTreeView(root);
        JPanel instances = new JPanel(new BorderLayout());

        java.util.function.Consumer<objectview.group.ViewableGroup<?>> show = group -> {
            List<Viewable> members = explicitMembers(group);
            renderedScope = new RenderedScope(generation, selectedType, members);
            instances.removeAll();
            instances.add(titledInstancePanel(
                    "Members of " + group.getDisplayName(), members, memberType),
                    BorderLayout.CENTER);
            instances.revalidate();
            instances.repaint();
            updateScopeStatus();
        };
        groups.setShowGroupHandler(show);
        groups.setSelectionHandler(group -> {
            selectedGroup = group instanceof quiz.transform.EditableGroup editable
                    ? editable : null;
            groups.setStatusText(selectedGroupStatus(group));
            if (viewStepsPanel != null) viewStepsPanel.reflectGroupRule(group);
        });
        groups.addControl("Add facet group", () -> addFacetGroup(selectedType, root));
        groups.addControl("Add filter group", viewStepsPanel::requestAddFilterGroup);
        groups.addControl("Add manual group", () -> addManualGroup(selectedType, root));
        groups.addControl("Create subclass…", () ->
                createSubclassFromGroup(selectedType, root));
        groups.addControl("Remove", () -> removeSelectedGroup(selectedType, root));
        groups.getTree().setSelectionRow(0);
        selectedGroup = root instanceof quiz.transform.EditableGroup editable
                ? editable : null;
        show.accept(root);

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, instances, groups);
        split.setResizeWeight(0.7);
        split.setOneTouchExpandable(true);
        return split;
    }

    private static String selectedGroupStatus(
            objectview.group.ViewableGroup<?> group) {
        if (group == null) return " ";
        String name = group instanceof quiz.transform.EditableGroup editable
                ? editable.name() : group.getDisplayName();
        String status = "Selected group: " + name;
        return group instanceof quiz.transform.ProducedGroup produced
                ? status + " — " + produced.ruleDescription() : status;
    }

    private JComponent titledInstancePanel(
            String scope, List<Viewable> members, String type) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(
                scope + " · " + members.size()));
        if (instanceScopeHeader != null) {
            panel.add(instanceScopeHeader, BorderLayout.NORTH);
        }
        panel.add(flatView(members, type), BorderLayout.CENTER);
        return panel;
    }

    private quiz.transform.EditableGroup selectedOrRoot(
            objectview.group.ViewableGroup<?> root) {
        return selectedGroup != null ? selectedGroup
                : root instanceof quiz.transform.EditableGroup editable ? editable : null;
    }

    private void addFacetGroup(String type, objectview.group.ViewableGroup<?> root) {
        DomainField field = viewStepsPanel.selectedDomainField();
        if (field == null) {
            JOptionPane.showMessageDialog(this, "Select a field first.");
            return;
        }
        String name = JOptionPane.showInputDialog(this,
                "Name the new facet group:", "By " + field.path());
        if (name == null || name.isBlank()) return;
        controller.addFacetGroup(type, selectedOrRoot(root), name.trim(), field);
        render();
    }

    private void addManualGroup(String type, objectview.group.ViewableGroup<?> root) {
        String name = JOptionPane.showInputDialog(this,
                "Name the new manual group:", "New group");
        if (name == null || name.isBlank()) return;
        controller.addManualGroup(selectedOrRoot(root), name.trim());
        render();
    }

    private void createSubclassFromGroup(
            String selectedType, objectview.group.ViewableGroup<?> root) {
        objectview.group.ViewableGroup<?> source = selectedOrRoot(root);
        if (source == null) return;
        JTextField name = new JTextField(18);
        JComboBox<String> base = new JComboBox<>(
                controller.types().toArray(String[]::new));
        base.setSelectedItem(selectedType);
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Subclass name:"));
        form.add(name);
        form.add(new JLabel("Base class:"));
        form.add(base);
        int result = JOptionPane.showConfirmDialog(this, form,
                "Create subclass from “" + source.getDisplayName() + "”",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION || name.getText().isBlank()) return;
        try {
            String subtype = name.getText().trim();
            int total = source.getMembers().size();
            int assigned = controller.createSubclassFromGroup(
                    subtype, (String) base.getSelectedItem(), source);
            selectedGroup = null;
            // Creating a class must not navigate away from the group tree that supplied
            // its members. Staying on the current/base view also presents the new class's
            // fields as a nested subtype section instead of a standalone flat config.
            viewStepsPanel.refreshTypes(selectedType != null
                    ? selectedType : (String) base.getSelectedItem());
            render();
            if (assigned < total) {
                JOptionPane.showMessageDialog(this,
                        assigned + " of " + total + " members are instances of "
                                + base.getSelectedItem() + "; " + (total - assigned)
                                + " were skipped.",
                        "Subclass created", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(),
                    "Cannot create subclass", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addFilterGroup(
            String name, quiz.transform.pipeline.ui.FilterCondition condition) {
        String type = controller.selectedType();
        quiz.transform.EditableGroup root =
                (quiz.transform.EditableGroup) controller.groupRoot(type);
        controller.addFilterGroup(type, selectedOrRoot(root), name, condition);
        render();
    }

    private void removeSelectedGroup(
            String type, objectview.group.ViewableGroup<?> root) {
        quiz.transform.EditableGroup selected = selectedOrRoot(root);
        if (selected == null || selected == root) {
            JOptionPane.showMessageDialog(this, "The root group cannot be removed.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Remove group \"" + selected.getDisplayName()
                        + "\" and all its nested groups?\n\n"
                        + "The instances themselves will not be deleted.",
                "Remove group", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION && controller.removeGroup(type, selected)) {
            selectedGroup = null;
            render();
        }
    }

    /** Persist the current view's members (the filtered / projected result) as a
     *  first-class domain — a snapshot + registry entry the web serves and the
     *  navigator lists. */
    private void saveAsDomain() {
        String type = controller.selectedType();
        if (type == null) {
            return;
        }
        if (!controller.canSave()) {
            JOptionPane.showMessageDialog(this,
                    "No domain writer configured for this session.");
            return;
        }
        // Default to the original domain name so an edit-and-save round-trips over it
        // (e.g. curate countries → Save → "countries"); fall back to the type otherwise.
        String suggested = domainName != null && !domainName.isBlank()
                ? domainName
                : type;
        String name = JOptionPane.showInputDialog(this,
                "Save the current result as a domain named:", suggested);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            JOptionPane.showMessageDialog(this, controller.saveAsDomain(name));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Show the compiled-schema inspector (ModelClass ↔ ProductClass) in a dialog. */
    private void showSchema() {
        JComponent view = controller.domain() instanceof SchemaView sv ? sv.schemaView() : null;
        if (view == null) {
            return;
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Schema — ModelClass ↔ ProductClass", Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.add(view, BorderLayout.CENTER);
        dialog.setSize(900, 560);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** Open the workbench in a frame over any domain. */
    public static void launch(DomainModel domain, String title) {
        launch(domain, title, null);
    }

    public static void launch(DomainModel domain, String title, DomainWriter writer) {
        SwingUtilities.invokeLater(() -> openFrame(domain, title, writer));
    }

    /** Build + show the workbench frame and RETURN it (EDT-only), so a caller can track
     *  it (e.g. to focus an already-open domain instead of reloading). */
    public static JFrame openFrame(DomainModel domain, String title, DomainWriter writer) {
        JFrame f = new JFrame("Transform Workbench — " + title);
        f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        TransformWorkbenchPanel panel = new TransformWorkbenchPanel(domain, writer, title);
        f.add(panel);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (panel.hasUnsavedIdentities()) {
                    int choice = JOptionPane.showConfirmDialog(f,
                            "Identities have been applied in memory but not saved.\n"
                                    + "Save them before closing?",
                            "Unsaved identities", JOptionPane.YES_NO_CANCEL_OPTION);
                    if (choice == JOptionPane.CANCEL_OPTION
                            || choice == JOptionPane.CLOSED_OPTION) {
                        return;   // keep the window open
                    }
                    if (choice == JOptionPane.YES_OPTION && !panel.saveIdentities()) {
                        return;   // save failed — don't lose the work
                    }
                }
                f.dispose();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                panel.close();
            }
        });
        f.setSize(1400, 900);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
        return f;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (explorerDialog != null) {
            explorerDialog.dispose();
        }
        queries.runner().cancel();
        requestClient.close();
    }
}
