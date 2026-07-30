package quiz.transform.ui;

import objectview.demo.GroupTreeBrowser;
import objectview.demo.MultiView;
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
 * ({@link ReflectionDomain}). Pick a member class, then build a pipeline of
 * operations: each operation's SIGNATURE narrows the fields pane to only the fields
 * that can be its argument (per shape), and every operation compiles to a real
 * {@link quiz.transform.View} — filters and facet groupings — whose grouped result
 * (the derived subdomain) renders live on the right via the shared card content view.
 *
 * <p>This is a THIN Swing view: all logic — the {@link WorkingDomain}, the pipeline,
 * compiling the result, saving — lives in {@link TransformController}. This class
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

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        if (controller.domain() instanceof SchemaView) {
            top.add(button("Schema…", this::showSchema));
        }
        if (controller.canSave()) {
            top.add(button("Save as domain…", this::saveAsDomain));
        }
        top.add(button("New field…", this::addNewField));
        top.add(button("Validate…", this::showValidation));
        top.add(button("Query logs…", () -> queries.showLogs(this)));
        queries.runner().registerCancelButton(cancelQueryButton);
        queries.runner().cancelAction(requestClient::cancelCurrentQuery);
        top.add(cancelQueryButton);
        if (controller.domain() instanceof quiz.curation.Curatable c && c.curation() != null) {
            top.add(button("Curate…", () -> openCuration(c.curation())));
            top.add(button("Merge…", () -> openMerge(c.curation())));
        }
        if (top.getComponentCount() > 0) {
            left.add(top, BorderLayout.NORTH);
        }

        viewStepsPanel = new ViewStepsPanel(controller, this::render);
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
            } else if (identitiesDirty) {
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
        right.add(scope, BorderLayout.NORTH);
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
        JTextField nameField = new JTextField(16);
        JComboBox<String> kindCombo =
                new JComboBox<>(new String[] {"Number", "Text", "Date", "Media"});
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
        form.add(new JLabel("Field name:"));
        form.add(nameField);
        form.add(new JLabel("Kind:"));
        form.add(kindCombo);
        int ok = JOptionPane.showConfirmDialog(this, form, "New field on " + type,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        String name = nameField.getText();
        if (name == null || name.isBlank()) {
            return;
        }
        objectview.field.FieldKind kind = switch ((String) kindCombo.getSelectedItem()) {
            case "Text" -> objectview.field.FieldKind.TEXT;
            case "Date" -> objectview.field.FieldKind.ORDERED;
            case "Media" -> objectview.field.FieldKind.MEDIA;
            default -> objectview.field.FieldKind.ORDERED;   // Number
        };
        if (controller.addField(type, name.trim(), kind)) {
            viewStepsPanel.refreshFields();
            render();
            JOptionPane.showMessageDialog(this, "Added field \"" + name.trim() + "\" to "
                    + type + ". Open Validate to fill it (Find Data).");
        } else {
            JOptionPane.showMessageDialog(this,
                    "New field is only supported for snapshot-backed domains.");
        }
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
        for (quiz.enrichment.ResolveIdentitiesDecision.Resolved r : decision.resolved()) {
            curation.putIdentityLink(new quiz.curation.IdentityLink(
                    r.type(), r.targetId(), "Wikidata", r.qid(),
                    "https://www.wikidata.org/wiki/" + r.qid(), r.label(), "wikidata"));
        }
        lastApplied = decision.resolved();
        identitiesDirty = true;
        updateScopeStatus();   // pending state on the scope buttons, immediately
        render();
        JOptionPane.showMessageDialog(this,
                decision.resolved().size() + " identity(ies) applied in memory —"
                        + " inspect them, then \"Save identities\" or \"Forget result\".");
    }

    /** Re-open the pending result's review so the user can inspect / re-decide it.
     *  Applying again updates the in-memory identities; it does not persist. */
    private void recallLastResult() {
        if (lastReviewItems.isEmpty()) {
            return;
        }
        quiz.enrichment.ui.ResolveIdentitiesReviewPanel.showModeless(
                this, lastReviewTitle, lastReviewPrompt, lastReviewItems,
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
        if (curation != null) {
            for (quiz.enrichment.ResolveIdentitiesDecision.Resolved r : lastApplied) {
                curation.removeIdentityLink(r.type(), r.targetId(), "Wikidata");
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
        try {
            curation.save();
            clearPendingResult();
            JOptionPane.showMessageDialog(this, "Identities saved.");
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
        return identitiesDirty;
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

    /** Open the manual-curation panel over this domain; re-render on any change. */
    private void openCuration(quiz.curation.ManualCuration curation) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Curate — fill missing field values", Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.add(new quiz.curation.ui.CurationPanel(controller.domain(), curation, this::render),
                BorderLayout.CENTER);
        dialog.setSize(1000, 720);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
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
        final List<OperationSpec> ops = controller.pipeline();
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

        new SwingWorker<ViewableGroup, Void>() {
            @Override protected ViewableGroup doInBackground() {
                return controller.compileResult(type, ops);
            }
            @Override protected void done() {
                if (generation != renderGeneration) {
                    return;   // a newer render started — don't overwrite it
                }
                try {
                    ViewableGroup root = get();
                    List<Viewable> visible = renderedMembers(root);
                    renderedScope = new RenderedScope(generation, type, visible);
                    renderHolder.removeAll();
                    List<? extends objectview.group.ViewableGroup<?>> declaredRoots =
                            controller.groupRoots(type);
                    objectview.group.ViewableGroup<?> existingGroups =
                            GroupHierarchyPresentation.rootOf(
                                    new ArrayList<>(declaredRoots), type);
                    // A grouped (facet) result keeps its group structure; a flat one
                    // shows its members as per-class instance sections, like the
                    // modelbuilder — except when those members ARE an existing group
                    // hierarchy, which uses the specialized group-tree renderer.
                    renderHolder.add(existingGroups != null
                            ? groupView(existingGroups, memberType(existingGroups, type))
                            : root.getChildren().isEmpty()
                                    ? flatView(visible, type)
                                    : groupView(root, type), BorderLayout.CENTER);
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

    private static List<Viewable> renderedMembers(ViewableGroup root) {
        java.util.LinkedHashSet<Viewable> members = new java.util.LinkedHashSet<>();
        collectRenderedMembers(root, members);
        return List.copyOf(members);
    }

    private static void collectRenderedMembers(
            ViewableGroup group, java.util.Set<Viewable> members) {
        if (group == null) return;
        for (objectview.Viewable member : group.getMembers()) {
            if (member instanceof Viewable viewable) {
                members.add(viewable);
            }
        }
        for (ViewableGroup child : group.getChildren()) {
            collectRenderedMembers(child, members);
        }
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
        // A pending (unsaved) result takes over the button: re-open it, don't re-resolve.
        if (identitiesDirty) {
            resolveButton.setText("Show pending result…");
            resolveButton.setEnabled(true);
        }
        saveIdentitiesButton.setEnabled(identitiesDirty);
        forgetResultButton.setEnabled(identitiesDirty);
    }

    /** A flat result: members grouped by type — a single searchable instance view
     *  for one type, or a per-class {@link MultiView} for several. */
    private JComponent flatView(List<Viewable> members, String type) {
        java.util.Map<String, List<Viewable>> byType = new java.util.LinkedHashMap<>();
        for (Viewable m : members) {
            if (m != null) {
                byType.computeIfAbsent(m.typeName(), k -> new ArrayList<>()).add(m);
            }
        }

        if (byType.size() <= 1) {
            String renderedType = byType.isEmpty()
                    ? type : byType.keySet().iterator().next();
            Viewable sample = controller.sampleOf(renderedType);
            return InstanceBrowser.create(
                    members, sample, controller.structuralFields(renderedType),
                    controller.fieldTypes(renderedType),
                    q -> controller.fieldSchema(q.typeName()), null);
        }

        MultiView mv = new MultiView();
        mv.context().setCollapsibleCards(true);
        mv.context().setFieldSchemaResolver(
                q -> controller.fieldSchema(q.typeName()));
        for (java.util.Map.Entry<String, List<Viewable>> e : byType.entrySet()) {
            String t = e.getKey();
            List<Viewable> objs = e.getValue();
            mv.addSection(t, sampleClass(objs.get(0)), objs,
                    objs.get(0), controller.structuralFields(t), controller.fieldTypes(t));
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
            objectview.group.ViewableGroup<?> root, String type) {
        Viewable sample = controller.sampleOf(type);
        Class<? extends Viewable> cls = sample != null ? sampleClass(sample) : Viewable.class;
        return new GroupTreeBrowser(root, cls, sample,
                controller.structuralFields(type), controller.fieldTypes(type),
                q -> controller.fieldSchema(q.typeName()));
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
                : type + (controller.pipeline().isEmpty() ? "" : " view");
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
        queries.runner().cancel();
        requestClient.close();
    }
}
