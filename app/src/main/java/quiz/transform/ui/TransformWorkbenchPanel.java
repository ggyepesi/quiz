package quiz.transform.ui;

import datasource.schema.FieldType;

import objectview.demo.MultiView;
import objectview.render.GroupTreeView;
import objectview.render.GroupMembersView;
import work.CancellationToken;
import process.ProcessInputHandler;
import process.ProcessInputRequest;
import process.swing.SwingProcessInput;
import process.swing.SwingProcessRunner;
import objectview.Viewable;
import quiz.group.ViewableGroup;
import quiz.transform.pipeline.ui.ViewStepsPanel;
import quiz.curation.ScopeFilter;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.query.core.QueryFactory;
import wikidata.explore.query.swing.SwingQuerySession;
import wikidata.ui.IdentityChip;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import domain.DomainField;
import domain.DomainModel;

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
    private final JButton curateFieldButton = new JButton("Curate field…");
    // One header button runs identity resolution on the shared Plan → Run → Results → Apply
    // host; its Apply STAGES the accepted assignments. The two buttons beside it are the
    // staging controls: Save writes them to the sidecar, Forget discards them. They appear
    // only while something is staged, so "nothing pending" needs no reading.
    private final JButton identitiesButton = new JButton("Identities…");
    private final JButton saveIdentitiesButton = new JButton("Save staged identities");
    private final JButton forgetIdentitiesButton = new JButton("Forget staged");
    private JPanel instanceScopeHeader;
    private quiz.transform.EditableGroup selectedGroup;
    // The group changes the instance scope, not the user's field choices. Dynamic
    // classes share a Java implementation, so the domain type name is the right key.
    private final java.util.Map<String, objectview.search.SearchPanel.ConfigState>
            instanceConfigsByType = new java.util.HashMap<>();
    // Primary datasource is WDQS (Wikidata queries + Explore); the factory owns the
    // DBpedia binding (enrichment joins), and each SPARQL operation explicitly requests
    // its datasource from the shared context. requestClient is closed by this panel.
    private final WikidataSparqlClient requestClient = new WikidataSparqlClient(
            "QuizProject/1.0 (ggyepesi@gmail.com)", 2);
    private final QueryFactory queryFactory = new QueryFactory(
            requestClient, new WikidataApiClient("QuizProject/1.0 (ggyepesi@gmail.com)"),
            "QuizProject/1.0 (ggyepesi@gmail.com)");
    private final SwingQuerySession queries =
            new SwingQuerySession(queryFactory.newContext());
    private final JButton cancelQueryButton = new JButton("Cancel request");
    private final JButton cancelIdentityButton = new JButton("Cancel identity process");
    // Runs the identity-resolution process (off-EDT, with a review pause). Shares the
    // session's query context + log window, so its searches show in "Query logs…".
    private final SwingProcessRunner resolveRunner = new SwingProcessRunner(
            queries.runner().context(), queries.runner().logListener(), process.ProcessInputHandler.unsupported());

    private ViewStepsPanel viewStepsPanel;

    // Bumped on every render() (EDT-only). A background render swaps its cards in
    // only if it's still the latest — so a slow earlier render can't overwrite a
    // newer one that finished first.
    private int renderGeneration;
    private RenderedScope renderedScope;
    // The selected field is an action target even while the value scope is All. Keep it
    // separate from the optional Missing/Present restriction applied to visible instances.
    private DomainField selectedField;
    private ScopeFilter fieldScope = ScopeFilter.ALL;
    private enum RightMode { CARDS, CURATING_FIELD }
    private RightMode rightMode = RightMode.CARDS;
    private java.util.function.Consumer<objectview.group.ViewableGroup<?>> activeShow;
    private objectview.group.ViewableGroup<?> activeGroup;
    private boolean closed;

    /** One authoritative selection state. {@code baseMembers} is the selected group's
     *  explicit membership; {@code visibleMembers} is the exact list rendered after the
     *  optional field-value scope. Instance actions always use the latter. */
    private record RenderedScope(
            int generation,
            String selectedType,
            List<Viewable> baseMembers,
            List<Viewable> visibleMembers) {
        private RenderedScope {
            baseMembers = List.copyOf(baseMembers);
            visibleMembers = List.copyOf(visibleMembers);
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
        if (controller.domain().capability(SchemaView.class) != null) {
            top.add(button("Schema…", this::showSchema));
        }
        // "New field…" now sits next to the Class selector (it acts on that class), and
        // "Create subclass from group…" is a group-tree control (it acts on a group) — so the
        // toolbar stays narrow enough that Query logs stays visible on a laptop screen.
        if (controller.canSave()) {
            top.add(button("Save as domain…", this::saveAsDomain));
        }
        top.add(button("Query logs…", () -> queries.showLogs(this)));
        queries.runner().registerCancelButton(cancelQueryButton);
        queries.runner().cancelAction(requestClient::cancelCurrentQuery);
        top.add(cancelQueryButton);
        resolveRunner.registerCancelButton(cancelIdentityButton);
        top.add(cancelIdentityButton);
        toolbar.add(top);

        quiz.curation.Curatable c =
                controller.domain().capability(quiz.curation.Curatable.class);
        if (c != null && c.curation() != null) {
            JPanel curationActions = new JPanel(
                    new FlowLayout(FlowLayout.LEFT, 4, 2));
            curationActions.setAlignmentX(Component.LEFT_ALIGNMENT);
            curationActions.add(new JLabel("Curation:"));
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
                controller, this::render, this::addFilterGroup,
                () -> renderedScope == null
                        ? java.util.List.of() : renderedScope.baseMembers(),
                this::applyFieldScope, this::addNewField);
        left.add(viewStepsPanel, BorderLayout.CENTER);

        return left;
    }

    /** The right header describes the visible instances and hosts two actions on them: curate
     *  the selected field (in-pane) and an Identities button (which opens a small panel with
     *  resolve / save / forget). The left panel picks field + scope; the header acts on it. */
    private JComponent buildRight() {
        JPanel right = new JPanel(new BorderLayout(4, 4));
        JPanel scope = new JPanel(new BorderLayout(8, 2));
        scope.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        scope.add(scopeStatus, BorderLayout.CENTER);
        curateFieldButton.setToolTipText(
                "Fill the field selected on the left for the visible instances (in this pane)");
        curateFieldButton.setEnabled(false);
        curateFieldButton.addActionListener(e -> openFieldCuration());
        identitiesButton.setToolTipText(
                "Resolve Wikidata identities for the visible instances");
        identitiesButton.setEnabled(false);
        identitiesButton.addActionListener(e -> openIdentityActions());
        saveIdentitiesButton.setToolTipText(
                "Write the staged identities to the curation sidecar");
        saveIdentitiesButton.addActionListener(e -> saveIdentities());
        saveIdentitiesButton.setVisible(false);
        forgetIdentitiesButton.setToolTipText(
                "Discard the staged identities — nothing has been written yet");
        forgetIdentitiesButton.addActionListener(e -> forgetStagedIdentities());
        forgetIdentitiesButton.setVisible(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(curateFieldButton);
        actions.add(identitiesButton);
        actions.add(forgetIdentitiesButton);
        actions.add(saveIdentitiesButton);
        scope.add(actions, BorderLayout.EAST);
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
        workbench.FieldDefinitionPanel editor =
                new workbench.FieldDefinitionPanel();
        editor.availableTargetTypes(controller.types());
        editor.edit(new wikidata.explore.model.FieldDefinition(
                "", datasource.schema.FieldType.STRING, "",
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
            viewStepsPanel.selectField(definition.name(), ScopeFilter.MISSING);
            render();
        } else {
            JOptionPane.showMessageDialog(this,
                    "New field is only supported for snapshot-backed domains.");
        }
    }


    /** Open identity resolution for the visible scope on the shared Plan → Run → Results →
     *  Apply host: the plan shows which instances are already identified and which will be
     *  searched, the results stage the accepted assignments, and the header's Save is what
     *  writes them. */
    private void openIdentityActions() {   // header: the whole visible view
        RenderedScope scope = renderedScope;
        if (scope == null || scope.visibleMembers().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No visible instances.");
            return;
        }
        openIdentityActions(scope.visibleMembers(),
                scope.selectedType() == null ? "View" : scope.selectedType());
    }

    /** Identity resolution RESTRICTED to an explicit scope (e.g. a curation drill's
     *  instances) — the same workflow as the header action, just a narrower member set.
     *  This is the one identity UI; there is no separate per-member flow. */
    private void openScopedIdentities(List<Viewable> instances, String scopeLabel) {
        if (instances == null || instances.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No instances in this scope.");
            return;
        }
        String type = instances.get(0).typeName();
        openIdentityActions(List.copyOf(instances), type == null ? "View" : type);
    }

    private void openIdentityActions(List<Viewable> members, String type) {
        quiz.curation.ManualCuration curation = curation();
        if (curation == null) {
            JOptionPane.showMessageDialog(this,
                    "This domain has no curation store to record identities.");
            return;
        }
        if (resolveRunner.isRunning()) {
            JOptionPane.showMessageDialog(this,
                    "An identity resolution is already running. Cancel it from its "
                            + "curation window before starting another.");
            return;
        }
        // Who actually HAS an entity identity to resolve is one rule, stated once
        // (#102) — a statement is anchored by its statement id and has no label of its
        // own, an untyped instance has nothing to key a link under.
        quiz.curation.IdentitySubjects split =
                quiz.curation.IdentitySubjects.of(members,
                        member -> controller.domain().entityIdentity(member.typeName()));
        List<Viewable> statements = split.statements();
        List<Viewable> nonEntities = split.nonEntities();
        List<Viewable> untyped = split.untyped();
        List<Viewable> resolvable = split.resolvable();
        if (split.hasNothingToResolve()) {
            JOptionPane.showMessageDialog(this,
                    split.excludedSummary()
                            + " There is no identity to resolve in this scope.",
                    "Nothing to resolve", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        quiz.enrichment.IdentityResolutionPlan plan =
                quiz.enrichment.IdentityResolutionPlan.inspect(
                        type, resolvable,
                        member -> currentQid(curation, member.typeName(), member));
        // Every resolvable member is a subject: the process skips the ones that already
        // carry an identity (an accepted identity is authoritative), so the scope needs no
        // further pre-filtering and an all-identified scope still opens for inspection.
        List<quiz.enrichment.ResolveIdentitiesProcess.Subject> subjects = new ArrayList<>();
        for (Viewable member : resolvable) {
            // The link is written under the STABLE identity type, not the most-specific
            // class: a subclass or a role membership can move the latter, and the link
            // would then stop matching the very instance it identifies.
            String memberType = quiz.curation.IdentityLinks.stableType(member);
            subjects.add(new quiz.enrichment.ResolveIdentitiesProcess.Subject(
                    memberType, member.getIdentifier(), member.getDisplayName(),
                    currentQid(curation, memberType, member)));
        }
        quiz.enrichment.ResolveIdentitiesProcess search =
                new quiz.enrichment.ResolveIdentitiesProcess(subjects, 12);
        process.swing.workflow.ProcessWorkflowAction<
                quiz.enrichment.ResolveIdentitiesProcess.Result,
                quiz.enrichment.ResolveIdentitiesDecision.Resolved> action =
                new process.swing.workflow.ProcessWorkflowAction<>() {
                    @Override public String id() { return "resolve-identities"; }
                    @Override public process.swing.workflow.ProcessWorkflowPlan plan() {
                        return new process.swing.workflow.ProcessWorkflowPlan(
                                "Wikidata identities — " + plan.scope(),
                                plan.identified().size() + " instance(s) already have a Wikidata "
                                        + "identity and will not be searched or changed; "
                                        + plan.unresolved().size()
                                        + " unresolved instance(s) will be searched, one "
                                        + "request each. Nothing is written: the results are "
                                        + "staged for review and saved separately."
                                        + (statements.isEmpty() ? "" : " " + statements.size()
                                                + " statement(s) are anchored already and "
                                                + "cannot be searched at all.")
                                        + (nonEntities.isEmpty() ? "" : " " + nonEntities.size()
                                                + " derived/owned instance(s) have no "
                                                + "independent entity identity.")
                                        + (untyped.isEmpty() ? "" : " " + untyped.size()
                                                + " untyped instance(s) need a stable kind "
                                                + "before identity resolution."),
                                List.of(new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                                "Already identified",
                                                entries(plan.identified()),
                                                TransformWorkbenchPanel.this::identityChip),
                                        new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                                "Will search", entries(plan.unresolved())),
                                        // Shown, not hidden: a scope that silently dropped
                                        // members would make the counts unexplainable.
                                        new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                                "Statements (nothing to resolve)", statements),
                                        new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                                "Derived/owned (no entity identity)", nonEntities),
                                        new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                                "Untyped (needs a kind)", untyped)),
                                !plan.unresolved().isEmpty(),
                                "All instances are already identified");
                    }
                    @Override public process.Process<
                            quiz.enrichment.ResolveIdentitiesProcess.Result> process() {
                        return search;
                    }
                    @Override public process.swing.workflow.ProcessWorkflowResults<
                            quiz.enrichment.ResolveIdentitiesDecision.Resolved> results(
                                    process.ProcessOutcome<
                                            quiz.enrichment.ResolveIdentitiesProcess.Result>
                                            outcome) {
                        return quiz.enrichment.ui.IdentityResultCards.of(
                                outcome.result(), outcome.summary());
                    }
                    @Override public void apply(
                            List<quiz.enrichment.ResolveIdentitiesDecision.Resolved> decisions) {
                        applyResolvedIdentities(curation,
                                new quiz.enrichment.ResolveIdentitiesDecision(decisions));
                    }
                };
        process.swing.workflow.SwingProcessWorkflow.start(this, resolveRunner, action);
    }

    private static List<Viewable> entries(
            List<quiz.enrichment.IdentityResolutionPlan.Entry> entries) {
        return entries.stream().map(quiz.enrichment.IdentityResolutionPlan.Entry::member).toList();
    }

    private static int stagedIdentityCount(quiz.curation.ManualCuration curation) {
        quiz.curation.CurationStaging staging =
                quiz.curation.CurationStaging.forCuration(curation);
        return staging == null ? 0 : staging.identityLinks().size();
    }

    /** The instance's current qid: its own id when it IS a qid, else an approved Wikidata
     *  identity link — so already-resolved members are skipped by the process. Link keying
     *  lives in {@link quiz.curation.IdentityLinks}, which reads a link written under any
     *  type this instance carries. */
    private static String currentQid(
            quiz.curation.ManualCuration curation, String type, Viewable member) {
        String id = member.getIdentifier();
        if (quiz.source.WikidataSource.isQid(id)) {
            return id;
        }
        return quiz.curation.IdentityLinks.wikidataQid(curation, member);
    }

    /** The card-header identity chip for a member: its native or curated Wikidata QID as a
     *  link when known, else an "unidentified" marker. Identity exists independently of
     *  whether the domain has a curation sidecar; only resolved identity links require one. */
    private JComponent identityChip(Viewable member) {
        quiz.curation.ManualCuration curation = curation();
        if (member == null) {
            return null;
        }
        if (wikidata.WikidataIds.isStatementId(member.getIdentifier())) {
            return IdentityChip.statement(member.getIdentifier());
        }
        String qid = currentQid(curation, member.typeName(), member);
        // A native QID needs no curation sidecar. Conversely, do not label every member of a
        // non-curatable, non-Wikidata domain "unidentified" when identity is not actionable.
        return qid == null && curation == null ? null : IdentityChip.of(qid);
    }

    /** Stage the accepted assignments in memory. The staging session is the one record of
     *  what is pending, so re-staging an assignment it already holds is a no-op and the
     *  header's Save reads its count directly. The workflow host reports the outcome. */
    private void applyResolvedIdentities(
            quiz.curation.ManualCuration curation, quiz.enrichment.ResolveIdentitiesDecision decision) {
        int changed = 0;
        for (quiz.enrichment.ResolveIdentitiesDecision.Resolved r : decision.resolved()) {
            if (quiz.curation.IdentityLinks.alreadyLinked(curation, r.targetId(), r.qid())) {
                continue;
            }
            quiz.curation.IdentityLink link = new quiz.curation.IdentityLink(
                    r.type(), r.targetId(), quiz.curation.IdentityLinks.WIKIDATA, r.qid(),
                    "https://www.wikidata.org/wiki/" + r.qid(), r.label(), "wikidata");
            quiz.curation.CurationStaging.forCuration(curation).stage(link);
            changed++;
        }
        if (changed == 0) {
            return;
        }
        updateScopeStatus();   // pending state on the scope buttons, immediately
        render();
    }

    /** Forget the staged identities. A staged assignment is by definition unsaved, so
     *  discarding restores the state from before the resolve. */
    private void forgetStagedIdentities() {
        quiz.curation.ManualCuration curation = curation();
        if (stagedIdentityCount(curation) == 0) {
            return;
        }
        quiz.curation.CurationStaging.forCuration(curation).discardIdentityLinks();
        updateScopeStatus();
        render();
    }

    /** Write the staged identities to the curation sidecar — the only control on this
     *  panel that persists anything. Returns false if the write failed, so the close flow
     *  can keep the window open. */
    private boolean saveIdentities() {
        quiz.curation.ManualCuration curation = curation();
        int savedCount = stagedIdentityCount(curation);
        if (savedCount == 0) {
            return true;
        }
        try {
            quiz.curation.CurationStaging.forCuration(curation).applyIdentityLinks();
            updateScopeStatus();
            String where = curation.file() == null
                    ? "the curation sidecar" : curation.file().getPath();
            JOptionPane.showMessageDialog(this,
                    savedCount + (savedCount == 1 ? " identity" : " identities")
                            + " saved to\n" + where,
                    "Identities saved", JOptionPane.INFORMATION_MESSAGE);
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            return false;
        }
    }

    private quiz.curation.ManualCuration curation() {
        quiz.curation.Curatable c =
                controller.domain().capability(quiz.curation.Curatable.class);
        return c != null
                ? c.curation() : null;
    }

    /** True while identities are staged but not yet written. The staging session is the
     *  single record of what is pending, so nothing else needs to track it. */
    public boolean hasUnsavedIdentities() {
        return stagedIdentityCount(curation()) > 0;
    }



    /** Curate the field selected on the left for the visible instances, in-pane: swap the
     *  cards for the fixed-target {@link ValidationPanel} scoped to exactly the shown members.
     *  No window — "Back to cards" re-renders. The left panel already established field +
     *  scope, so this never re-asks class/field/scope. */
    private void openFieldCuration() {
        DomainField field = selectedField;
        RenderedScope scope = renderedScope;
        if (field == null) {
            JOptionPane.showMessageDialog(this, "Select a field on the left to curate.");
            return;
        }
        if (scope == null || scope.visibleMembers().isEmpty()) {
            JOptionPane.showMessageDialog(this, "The selected scope contains no instances.");
            return;
        }
        // Applying a value refreshes the left-panel coverage but keeps this pane open (fill
        // several instances in a row); the cards rebuild with new values only on Back.
        ValidationPanel panel = new ValidationPanel(controller.domain(), scope.visibleMembers(),
                queries.runner(),
                () -> { if (viewStepsPanel != null) viewStepsPanel.refreshWorkingSet(); },
                // Identity from the curation drill opens the ONE identity panel, scoped to
                // the drilled instances — not a direct batch run.
                this::openScopedIdentities);
        String scopeLabel = scope.visibleMembers().size() + " selected "
                + (scope.visibleMembers().size() == 1 ? "instance" : "instances")
                + " · " + fieldScope;
        panel.useFixedTarget(field.type(), field.field(), scopeLabel);

        JButton back = new JButton("← Back to cards");
        back.addActionListener(e -> render());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(back);
        bar.add(new JLabel("Curating " + field.displayPath()));

        renderHolder.removeAll();
        renderHolder.add(bar, BorderLayout.NORTH);
        renderHolder.add(panel, BorderLayout.CENTER);
        rightMode = RightMode.CURATING_FIELD;
        renderHolder.revalidate();
        renderHolder.repaint();
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
        rightMode = RightMode.CARDS;
        renderedScope = null;
        // A running resolve reports itself in its own workflow window, so this line is free
        // to describe the render.
        scopeStatus.setText("Rendering " + (type == null ? "view" : type) + "…");
        identitiesButton.setEnabled(false);

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
                    identitiesButton.setEnabled(false);
                    renderHolder.removeAll();
                    renderHolder.add(new JLabel("  Render failed: " + ex.getMessage()),
                            BorderLayout.NORTH);
                }
                renderHolder.revalidate();
                renderHolder.repaint();
            }
        }.execute();
    }

    /** Record both independent selections: the field targeted by actions and the optional
     *  All/Missing/Present restriction. Changing either invalidates a fixed curation target,
     *  so return to the card view before applying the new selection. */
    private void applyFieldScope(DomainField field, ScopeFilter filter) {
        selectedField = field;
        fieldScope = field == null || filter == null ? ScopeFilter.ALL : filter;
        if (rightMode == RightMode.CURATING_FIELD) {
            render();
            return;
        }
        if (activeShow != null && activeGroup != null) {
            activeShow.accept(activeGroup);
        }
    }

    /** Apply the explicit value mode and, for a subtype-owned field, restrict eligibility
     *  to that subtype. A USState field must never classify every ordinary State as missing. */
    private List<Viewable> applyFieldScope(List<Viewable> members) {
        if (selectedField == null || fieldScope == ScopeFilter.ALL) {
            return members;
        }
        return FieldCoverageColumns.select(controller.domain(), members,
                selectedField.type(), selectedField.fieldPath(), fieldScope);
    }

    private String instanceTitle(objectview.group.ViewableGroup<?> group) {
        String title = "Members of " + group.getDisplayName();
        String selection = viewStepsPanel == null ? null : viewStepsPanel.selectedSelection();
        if (selection != null) title += " ∩ "
                + quiz.transform.pipeline.ui.ViewStepsPanel.selectionDisplayName(selection);
        String second = viewStepsPanel == null
                ? null : viewStepsPanel.secondSelectedSelection();
        if (second != null) title += " ∩ "
                + quiz.transform.pipeline.ui.ViewStepsPanel.selectionDisplayName(second);
        if (selectedField == null || fieldScope == ScopeFilter.ALL) {
            return title;
        }
        return title + " · " + selectedField.displayPath() + " "
                + (fieldScope == ScopeFilter.MISSING ? "missing" : "present");
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

    private List<Viewable> applySelectionScope(List<Viewable> members) {
        String selection = viewStepsPanel == null ? null : viewStepsPanel.selectedSelection();
        List<Viewable> result = members;
        if (selection != null) {
            java.util.Set<Viewable> selected = new java.util.LinkedHashSet<>(
                    controller.domain().selectionMembers(selection));
            result = result.stream().filter(selected::contains).toList();
        }
        String second = viewStepsPanel.secondSelectedSelection();
        if (second == null) return result;
        java.util.Set<Viewable> also = new java.util.LinkedHashSet<>(
                controller.domain().selectionMembers(second));
        return result.stream().filter(also::contains).toList();
    }

    private void updateScopeStatus() {
        quiz.curation.ManualCuration curation = curation();
        updateStagedIdentityButtons(curation);
        RenderedScope scope = renderedScope;
        if (scope == null) {
            curateFieldButton.setEnabled(false);
            identitiesButton.setEnabled(false);
            return;
        }
        quiz.curation.IdentitySubjects identitySubjects = quiz.curation.IdentitySubjects.of(
                scope.visibleMembers(),
                member -> controller.domain().entityIdentity(member.typeName()));
        long statements = identitySubjects.statements().size();
        long nonEntities = identitySubjects.nonEntities().size();
        long identified = identitySubjects.resolvable().stream()
                .filter(member -> currentQid(curation, member.typeName(), member) != null)
                .count();
        long unresolved = identitySubjects.resolvable().size() - identified;
        java.util.Set<String> roleNames = new java.util.LinkedHashSet<>(
                controller.domain().selectionNames());
        long unknownKind = scope.visibleMembers().stream()
                .filter(member -> !wikidata.WikidataIds.isStatementId(member.getIdentifier()))
                .filter(member -> member.directClassNames().stream()
                        .noneMatch(direct -> !roleNames.contains(direct)))
                .count();
        String count = scope.visibleMembers().size() == scope.baseMembers().size()
                ? scope.visibleMembers().size() + " shown"
                : scope.visibleMembers().size() + " shown of "
                        + scope.baseMembers().size() + " group members";
        scopeStatus.setText((scope.selectedType() == null ? "View" : scope.selectedType())
                + " · " + count + " · "
                + identified + " identified · " + unresolved + " unresolved"
                + (statements == 0 ? "" : " · " + statements + " statements")
                + (nonEntities == 0 ? "" : " · " + nonEntities + " derived/owned")
                + (unknownKind == 0 ? "" : " · " + unknownKind + " unknown kind"));

        curateFieldButton.setText(selectedField == null ? "Curate field…"
                : "Curate " + selectedField.displayPath() + "…");
        curateFieldButton.setEnabled(selectedField != null && !scope.visibleMembers().isEmpty());

        // Resolution acts on the visible scope, so it needs one; the staging controls beside
        // it stay available even when the scope is empty (they act on what is already staged).
        boolean hasResolvable = !identitySubjects.resolvable().isEmpty();
        identitiesButton.setEnabled(curation != null && hasResolvable);
    }

    /** The staging controls read the staging session directly — it is the single record of
     *  what is pending, shared with every other curation surface over the same sidecar. */
    private void updateStagedIdentityButtons(quiz.curation.ManualCuration curation) {
        int staged = stagedIdentityCount(curation);
        saveIdentitiesButton.setText("Save " + staged + " staged identity"
                + (staged == 1 ? "" : "s"));
        saveIdentitiesButton.setVisible(staged > 0);
        forgetIdentitiesButton.setVisible(staged > 0);
        if (instanceScopeHeader != null) {
            instanceScopeHeader.revalidate();
            instanceScopeHeader.repaint();
        }
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
            return objectview.view.SearchableView.builder(members)
                    .sample(sample)
                    .hiddenFields(controller.structuralFields(type))
                    .fieldTypes(controller.fieldTypes(type))
                    .fieldSchemas(q -> controller.renderedFieldSchema(q, type))
                    .subtypeConfigs(subtypes)
                    .configState(instanceConfigsByType.get(type))
                    .configListener(config -> instanceConfigsByType.put(type, config))
                    .cardDecorator(this::identityChip)
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
            return objectview.view.SearchableView.builder(members)
                    .sample(sample)
                    .hiddenFields(controller.structuralFields(renderedType))
                    .fieldTypes(controller.fieldTypes(renderedType))
                    .fieldSchemas(q -> controller.renderedFieldSchema(q, renderedType))
                    .configState(instanceConfigsByType.get(renderedType))
                    .configListener(config ->
                            instanceConfigsByType.put(renderedType, config))
                    .cardDecorator(this::identityChip)
                    .collapsible(true)
                    .build();
        }

        MultiView mv = new MultiView();
        mv.context().setCollapsibleCards(true);
        mv.context().setFieldSchemaResolver(
                q -> controller.fieldSchema(q.typeName()));
        mv.context().setCardDecorator(this::identityChip);
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
        GroupMembersView grouped = new GroupMembersView(
                root, group -> {
            activeGroup = group;
            boolean schemaChanged = controller.selectGroup(group);
            if (schemaChanged && viewStepsPanel != null) viewStepsPanel.refreshSchema();
            List<Viewable> members = applySelectionScope(explicitMembers(group));
            List<Viewable> shown = applyFieldScope(members);
            renderedScope = new RenderedScope(generation, selectedType, members, shown);
            if (viewStepsPanel != null) {
                viewStepsPanel.refreshWorkingSet();
            }
            updateScopeStatus();
            return titledInstancePanel(instanceTitle(group), shown, memberType);
        }, JSplitPane.VERTICAL_SPLIT, true, 0.7, false);
        GroupTreeView groups = grouped.groups();
        activeShow = grouped::showGroup;
        grouped.setSelectionHandler(group -> {
            boolean schemaChanged = controller.selectGroup(group);
            if (schemaChanged && viewStepsPanel != null) viewStepsPanel.refreshSchema();
            selectedGroup = group instanceof quiz.transform.EditableGroup editable
                    ? editable : null;
            groups.setStatusText(selectedGroupStatus(group));
        });
        groups.addControl("Add facet group", () -> addFacetGroup(selectedType, root));
        groups.addControl("Add type-spec group", () -> addTypeSpecGroup(selectedType, root));
        groups.addControl("Show type spec", this::showSelectedTypeSpec);
        // "Add filter group" is NOT here: its condition is composed on the field panel's
        // operator row, and a control that commits a rule edited elsewhere reads as
        // unrelated to it. Facet and manual groups ask for their own input, so they stay.
        groups.addControl("Add manual group", () -> addManualGroup(selectedType, root));
        groups.addControl("Create subclass from group", this::createSubclassFromSelection);
        groups.addControl("Remove", () -> removeSelectedGroup(selectedType, root));
        groups.getTree().setSelectionRow(0);
        selectedGroup = root instanceof quiz.transform.EditableGroup editable
                ? editable : null;
        objectview.group.ViewableGroup<?> initial = activeGroup != null
                && belongsTo(root, activeGroup) ? activeGroup : root;
        if (!grouped.selectGroup(initial, true)) grouped.showGroup(root);
        return grouped;
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

    private static boolean belongsTo(objectview.group.ViewableGroup<?> root,
                                     objectview.group.ViewableGroup<?> candidate) {
        for (objectview.group.ViewableGroup<?> current = candidate; current != null;
             current = current.getParent()) if (current == root) return true;
        return false;
    }

    private void showSelectedTypeSpec() {
        quiz.transform.TypeSpec spec = controller.effectiveTypeSpec(selectedGroup);
        if (spec == null) {
            JOptionPane.showMessageDialog(this,
                    "The selected group has no type specification.", "Type specification",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder text = new StringBuilder("Instance class: ")
                .append(spec.instanceClass());
        spec.fieldClasses().forEach((path, types) -> text.append("\n")
                .append(path).append(": ").append(String.join(" | ", types)));
        JTextArea area = new JTextArea(text.toString(),
                Math.max(4, spec.fieldClasses().size() + 2), 44);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                "Effective type specification — " + selectedGroup.name(),
                JOptionPane.INFORMATION_MESSAGE);
    }

    private JComponent titledInstancePanel(
            String scope, List<Viewable> members, String type) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(
                scope + " · " + members.size()));
        if (instanceScopeHeader != null) {
            panel.add(instanceScopeHeader, BorderLayout.NORTH);
        }
        // Identity rides in each card's header as a chip (identityChip via cardDecorator),
        // so there is no separate identity index or toggle.
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
        // Bucketing by VALUE produces no bucket at all for a member whose field is
        // empty, so the records an EXPECTED field reports as missing stay invisible.
        // Present/missing is how that count becomes a set you can select (#96).
        Object[] choices = {"One bucket per value", "Present / missing"};
        int choice = JOptionPane.showOptionDialog(this,
                "How should '" + field.path() + "' bucket its members?",
                "Facet group", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        if (choice < 0) return;
        quiz.transform.FacetGroup.Bucketing bucketing = choice == 1
                ? quiz.transform.FacetGroup.Bucketing.PRESENCE
                : quiz.transform.FacetGroup.Bucketing.VALUE;
        String suggested = (bucketing
                == quiz.transform.FacetGroup.Bucketing.PRESENCE ? "Has " : "By ")
                + field.path();
        String name = JOptionPane.showInputDialog(this,
                "Name the new facet group:", suggested);
        if (name == null || name.isBlank()) return;
        controller.addFacetGroup(type, selectedOrRoot(root), name.trim(),
                field, bucketing);
        render();
    }

    private void addManualGroup(String type, objectview.group.ViewableGroup<?> root) {
        String name = JOptionPane.showInputDialog(this,
                "Name the new manual group:", "New group");
        if (name == null || name.isBlank()) return;
        controller.addManualGroup(selectedOrRoot(root), name.trim());
        render();
    }

    private void addTypeSpecGroup(String type, objectview.group.ViewableGroup<?> root) {
        quiz.transform.EditableGroup parent = selectedOrRoot(root);
        if (parent == null || type == null) return;

        JTextField name = new JTextField("Typed " + type, 22);
        // A group is authored inside one class root, exactly like USStates is bound to
        // USState. Show that class explicitly; choosing another would make the field rows
        // below belong to a different schema.
        JComboBox<String> instanceClass = new JComboBox<>(new String[]{type});
        instanceClass.setSelectedItem(type);
        // Base schema, not the selected group's refined projection: this dialog lists
        // the reference fields to CONSTRAIN, so it must see the plain class, not a
        // TypeSpecDomainView rewrite that may be active for the same type.
        java.util.List<DomainField> references = controller.domain().fields(type).stream()
                .filter(DomainField::reference).toList();
        javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
                new Object[]{"Instance / reference field", "Allowed class(es)"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return column == 1; }
        };
        references.forEach(field -> model.addRow(new Object[]{field.field(), ""}));
        JTable rules = new JTable(model);
        rules.setFillsViewportHeight(true);
        rules.getColumnModel().getColumn(0).setPreferredWidth(220);
        rules.getColumnModel().getColumn(1).setPreferredWidth(260);
        JComboBox<String> classPicker = new JComboBox<>(
                controller.admissionClasses().toArray(String[]::new));
        // One class can be picked directly; unions remain expressible as
        // "Film | MusicalWork" in the same editor. The choices come from the domain's
        // explicit modeled/stamped classes, never from sampling the field's values.
        classPicker.setEditable(true);
        classPicker.setSelectedItem("");
        rules.getColumnModel().getColumn(1).setCellEditor(
                new DefaultCellEditor(classPicker));
        rules.getColumnModel().getColumn(1).setCellRenderer(
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override public Component getTableCellRendererComponent(
                            JTable table, Object value, boolean selected, boolean focus,
                            int row, int column) {
                        super.getTableCellRendererComponent(
                                table, value, selected, focus, row, column);
                        if (value == null || value.toString().isBlank()) {
                            setText("Choose class(es)…");
                            if (!selected) setForeground(UIManager.getColor("Label.disabledForeground"));
                        }
                        return this;
                    }
                });

        JPanel form = new JPanel(new BorderLayout(6, 6));
        JPanel heading = new JPanel(new GridLayout(0, 2, 6, 6));
        heading.add(new JLabel("Group name:"));
        heading.add(name);
        heading.add(new JLabel("Instance class:"));
        heading.add(instanceClass);
        form.add(heading, BorderLayout.NORTH);
        form.add(new JScrollPane(rules), BorderLayout.CENTER);
        JLabel help = new JLabel("Enter one or more modeled classes separated by |. "
                + "All filled rows must match; alternatives within a row use OR, and every "
                + "value of a multi-valued field must match. Available: "
                + String.join(", ", controller.admissionClasses()));
        JButton preview = new JButton("Preview count");
        JLabel previewResult = new JLabel(" ");
        preview.addActionListener(event -> {
            stopTableEditing(rules);
            quiz.transform.TypeSpec candidate = typeSpecFrom(
                    (String) instanceClass.getSelectedItem(), model);
            previewResult.setText(admittedCount(candidate, parent) + " of "
                    + parent.getMembers().size() + " parent instances will be admitted");
        });
        JPanel footer = new JPanel(new BorderLayout(6, 4));
        footer.add(help, BorderLayout.NORTH);
        JPanel previewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        previewRow.add(preview);
        previewRow.add(previewResult);
        footer.add(previewRow, BorderLayout.SOUTH);
        form.add(footer, BorderLayout.SOUTH);
        form.setPreferredSize(new Dimension(700, Math.min(430, 150 + references.size() * 24)));

        int answer = JOptionPane.showConfirmDialog(this, form, "Add type-spec group",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION || name.getText().isBlank()
                || instanceClass.getSelectedItem() == null) return;

        stopTableEditing(rules);
        quiz.transform.TypeSpec spec = typeSpecFrom(
                (String) instanceClass.getSelectedItem(), model);
        // A rule that admits nothing is offered, not refused: it may be right ahead of the
        // data (kinds not stamped yet, referents not loaded), and the group fills itself in
        // when the scope is re-derived. Only the author can tell that from a typo, so show
        // the count and let them decide.
        if (admittedCount(spec, parent) == 0 && JOptionPane.showConfirmDialog(this,
                "This type specification admits 0 of " + parent.getMembers().size()
                        + " instances in " + parent.name() + ".\nCreate the group anyway?"
                        + "\n\nThe rule is kept and re-applied whenever the data changes.",
                "Nothing admitted yet", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        // No second opinion on what a valid class is: the controller validates the spec
        // and its message is shown below. A stricter copy here would reject rules the
        // rule engine accepts.
        try {
            quiz.transform.TypeSpecGroup created = controller.addTypeSpecGroup(
                    parent, name.getText().trim(), spec);
            activeGroup = created;
            selectedGroup = created;
            render();
        } catch (IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(),
                    "Cannot create group", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** The preview count: how many of the parent's members the rule admits right now. */
    private long admittedCount(
            quiz.transform.TypeSpec spec, quiz.transform.EditableGroup parent) {
        return parent.getMembers().stream()
                .filter(member -> spec.matches(member, controller.domain())).count();
    }

    private static void stopTableEditing(JTable table) {
        if (table != null && table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private static quiz.transform.TypeSpec typeSpecFrom(
            String instanceClass, javax.swing.table.TableModel model) {
        java.util.Map<String, java.util.Set<String>> fieldTypes = new java.util.LinkedHashMap<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            java.util.Set<String> accepted = parseTypeNames(model.getValueAt(row, 1));
            if (!accepted.isEmpty()) fieldTypes.put(
                    String.valueOf(model.getValueAt(row, 0)), accepted);
        }
        return new quiz.transform.TypeSpec(instanceClass, fieldTypes);
    }

    private static java.util.Set<String> parseTypeNames(Object value) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (value != null) for (String part : String.valueOf(value).split("[|,]")) {
            if (!part.isBlank()) result.add(part.trim());
        }
        return result;
    }

    private void createSubclassFromSelection() {
        if (activeGroup == null) {
            JOptionPane.showMessageDialog(this, "Select the source group first.");
            return;
        }
        createSubclassFromGroup(controller.selectedType(), activeGroup);
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
        SchemaView sv = controller.domain().capability(SchemaView.class);
        JComponent view = sv == null ? null : sv.schemaView();
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
                            "Identities are staged but have not been saved.\n"
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
        queries.runner().cancel();
        requestClient.close();
        queryFactory.close();
    }
}
