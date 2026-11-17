package wikidata.explore.workbench;

import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import wikidata.WikidataIds;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.query.logical.DiscoverCommonMembershipTypesQuery;
import wikidata.explore.query.logical.DiscoverSubtypesQuery;
import wikidata.explore.query.logical.PreviewMembershipTypeQuery;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.MembershipTypePreview;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.rule.RuleNode;
import wikidata.ui.WikidataLinks;
import workbench.ExploreByExamplePanel;
import workbench.SelectionsButton;
import workbench.WorkbenchSelections;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One membership-type discovery surface. A broad type searches downward through direct
 * subclasses; explicit examples search upward through P31/P279. Both routes produce the
 * same candidate cards, bounded member preview and explicit configuration actions.
 */
final class SubclassDiscoveryPanel extends JPanel {

    private static final String ADD_NEEDS_A_RELATION =
            "The selected Source class has no membership relation yet. Use one selected "
                    + "candidate as its membership type before adding additional types.";

    private final JTextField rootQid = new JTextField(10);
    private final JLabel rootLabel = new JLabel("No broad type selected");
    private final JButton findBroadType = new JButton("Find broad type…");
    private final JButton runSubtypes = new JButton("Find direct subtypes");
    private final JButton findExample = new JButton("Find example…");
    private final JButton findCommonTypes = new JButton("Find common types");
    private final JButton removeExamples = new JButton("Remove selected examples");
    private final JButton cancel = new JButton("Cancel");
    private final JButton preview = new JButton("Preview selected type members");
    private final JButton use = new JButton("Use selected as membership type");
    private final JButton add = new JButton("Add selected as additional membership types");
    private final JCheckBox previewDescendants =
            new JCheckBox("Class membership includes subclasses", false);
    private final JLabel status = new JLabel(" ");
    private final JPanel examplesHolder = new JPanel(new BorderLayout());
    private final JPanel selectionsHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel candidatesHolder = new JPanel(new BorderLayout());
    private final LinkedHashMap<String, WorkbenchSelections.Entity> examples =
            new LinkedHashMap<>();
    private List<MembershipEntityViewable> selectedExamples = List.of();
    private List<MembershipTypeCandidateViewable> candidates = List.of();
    private List<MembershipTypeCandidateViewable> selectedCandidates = List.of();
    private JComponent candidateView;
    private SwingQueryRunner runner;
    private WorkbenchSelections selections;
    private Consumer<String> onAddMembershipTarget = ignored -> { };
    private Function<WorkbenchSelections.Entity, String> onUseMembershipType =
            ignored -> "No membership editor is connected.";
    private String selectedClassName = "";
    private String broadTypeQid = "";
    private String broadTypeLabel = "";
    private String queriedBroadTypeQid = "";
    private int queriedExampleCount;
    private boolean addable;
    private boolean usable;
    private String useRefusal = "Select an editable Source class.";
    private String addRefusal = ADD_NEEDS_A_RELATION;

    SubclassDiscoveryPanel() {
        super(new BorderLayout(6, 6));

        JTabbedPane starts = new JTabbedPane();
        starts.addTab("From broad type", broadTypePanel());
        starts.addTab("From examples", examplesPanel());
        starts.setPreferredSize(new Dimension(760, 210));
        add(starts, BorderLayout.NORTH);

        candidatesHolder.setBorder(BorderFactory.createTitledBorder("Candidate membership types"));
        showCandidates(List.of());
        add(candidatesHolder, BorderLayout.CENTER);

        JPanel actions = new JPanel(new objectview.utils.swing.WrapLayout(
                FlowLayout.LEFT, 6, 2));
        preview.setEnabled(false);
        use.setEnabled(false);
        add.setEnabled(false);
        previewDescendants.setEnabled(false);
        actions.add(previewDescendants);
        actions.add(preview);
        actions.add(use);
        actions.add(add);
        actions.add(cancel);
        actions.add(status);
        add(actions, BorderLayout.SOUTH);

        findBroadType.addActionListener(event -> chooseBroadType());
        findExample.addActionListener(event -> chooseExample());
        removeExamples.addActionListener(event -> removeSelectedExamples());
        use.addActionListener(event -> useSelected());
        add.addActionListener(event -> addSelected());
        cancel.setEnabled(false);
        rootQid.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) {
                showBroadType();
            }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) {
                showBroadType();
            }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) {
                showBroadType();
            }
        });
        updateActions();
    }

    private JComponent broadTypePanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(new JLabel("<html>Find a broad Wikidata class, then inspect its direct "
                + "subtypes, counts and representative members. Inspection does not change "
                + "the selected Source class.</html>"), BorderLayout.NORTH);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(findBroadType);
        controls.add(new JLabel("or broad type QID:"));
        controls.add(WikidataIdLines.qid(rootQid));
        controls.add(runSubtypes);
        controls.add(rootLabel);
        panel.add(controls, BorderLayout.CENTER);
        return panel;
    }

    private JComponent examplesPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actions.add(findExample);
        actions.add(selectionsHolder);
        removeExamples.setEnabled(false);
        actions.add(removeExamples);
        actions.add(findCommonTypes);
        panel.add(actions, BorderLayout.NORTH);
        showExamples();
        panel.add(examplesHolder, BorderLayout.CENTER);
        return panel;
    }

    void selections(WorkbenchSelections selections) {
        this.selections = selections;
        selectionsHolder.removeAll();
        if (selections != null) {
            selectionsHolder.add(new SelectionsButton(selections).useEntities(
                    "Use selected entities as membership examples",
                    SelectionsButton.Cardinality.MULTIPLE, this::setExamples));
        }
        selectionsHolder.revalidate();
        selectionsHolder.repaint();
    }

    void setQueryRunner(SwingQueryRunner runner) {
        if (runner == null || this.runner != null) return;
        this.runner = runner;
        runner.registerCancelButton(cancel);
        runner.wireButton(runSubtypes, this::acceptSubtypes, this::subtypesQuery,
                failure -> showError("Subtype discovery failed", failure));
        runner.wireButton(findCommonTypes, this::acceptCommonTypes, this::commonTypesQuery,
                failure -> showError("Common-type discovery failed", failure));
        runner.wireButton(preview, this::acceptPreview, this::previewQuery,
                failure -> showError("Membership preview failed", failure));
        runner.onRunningChanged(ignored -> updateActions());
        updateActions();
    }

    void onAddMembershipTarget(Consumer<String> handler) {
        onAddMembershipTarget = handler == null ? ignored -> { } : handler;
    }

    void onUseMembershipType(Function<WorkbenchSelections.Entity, String> handler) {
        onUseMembershipType = handler == null
                ? ignored -> "No membership editor is connected." : handler;
    }

    void showClass(GeneratedClassModel clazz) {
        selectedClassName = clazz == null ? "" : clazz.className();
        boolean editableSource = clazz != null
                && clazz.classKind() == wikidata.explore.model.ClassKind.SOURCE
                && !clazz.isImported();
        EntityBound.Kind membershipKind = clazz == null
                ? EntityBound.Kind.UNBOUNDED : clazz.membership().kind();
        boolean plainTypeMembership = clazz != null
                && membershipKind == EntityBound.Kind.RELATION
                && "P31".equalsIgnoreCase(clazz.membership().relationPid())
                && clazz.membership().qualifierCondition() == null;
        usable = editableSource && (membershipKind == EntityBound.Kind.UNBOUNDED
                || plainTypeMembership);
        addable = editableSource && plainTypeMembership;
        useRefusal = useRefusal(clazz, editableSource, membershipKind);
        addRefusal = membershipKind == EntityBound.Kind.UNBOUNDED
                ? ADD_NEEDS_A_RELATION
                : plainTypeMembership ? ""
                : "Additional membership types require an editable plain P31 membership.";
        String qid = clazz == null || clazz.membership().qids().isEmpty()
                ? "" : clazz.membership().qids().getFirst();
        broadTypeQid = qid;
        rootQid.setText(qid);
        broadTypeLabel = clazz == null ? "" : clazz.instanceMapping().sourceLabel();
        showBroadType();
        previewDescendants.setSelected(clazz != null
                && clazz.membership().includeDescendants());
        previewDescendants.setToolTipText(
                "Read-only: preview uses the selected class's saved membership setting.");
        use.setToolTipText(usable ? null : useRefusal);
        add.setToolTipText(addable ? null : addRefusal);
        status.setText(selectedClassName.isBlank()
                ? "Select a Source class before using a candidate."
                : usable && addable
                        ? "Candidates can be used for " + selectedClassName + "."
                        : usable ? selectedClassName + " has no membership relation yet; "
                                + "use one selected candidate as its membership type first."
                : "Use unavailable for " + selectedClassName + ": " + useRefusal);
        updateActions();
    }

    private static String useRefusal(GeneratedClassModel clazz, boolean editableSource,
            EntityBound.Kind membershipKind) {
        if (clazz == null) return "Select an editable Source class.";
        if (!editableSource) return "The selected class is not an editable Source class.";
        if (membershipKind == EntityBound.Kind.EXPLICIT) {
            return "This class uses explicit seed QIDs; membership discovery will not "
                    + "replace them.";
        }
        if (membershipKind == EntityBound.Kind.VOCABULARY) {
            return "This class uses a named selection; membership discovery will not "
                    + "replace it.";
        }
        if (membershipKind == EntityBound.Kind.RELATION
                && (!"P31".equalsIgnoreCase(clazz.membership().relationPid())
                        || clazz.membership().qualifierCondition() != null)) {
            return "This class does not use a plain P31 membership; a P31 type preview "
                    + "will not replace it.";
        }
        return "";
    }

    private void chooseBroadType() {
        if (runner == null) {
            status.setText("A Wikidata query connection is required to find a broad type.");
            return;
        }
        ExploreByExamplePanel.showPicker(this, runner, broadTypeLabel, false,
                (qid, label) -> SwingUtilities.invokeLater(() -> {
                    broadTypeQid = qid;
                    rootQid.setText(qid);
                    broadTypeLabel = label == null ? "" : label;
                    showBroadType();
                    status.setText("Selected broad type " + broadTypeLabel + " (" + qid
                            + "). Click Find direct subtypes; nothing is configured yet.");
                }));
    }

    private void chooseExample() {
        if (runner == null) {
            status.setText("A Wikidata query connection is required to find an example.");
            return;
        }
        ExploreByExamplePanel.showPicker(this, runner, "", false,
                (qid, label) -> SwingUtilities.invokeLater(() -> {
                    WorkbenchSelections.Entity entity =
                            new WorkbenchSelections.Entity(qid, label);
                    if (selections != null) selections.entity(qid, label);
                    examples.put(qid, entity);
                    showExamples();
                    status.setText(exampleStatus("Added " + label + " (" + qid
                            + ") as an example."));
                }));
    }

    private DiscoverSubtypesQuery subtypesQuery() {
        String qid = RuleNode.cleanQid(rootQid.getText());
        if (!WikidataIds.isQid(qid)) {
            status.setText("Choose a broad type or enter its Wikidata QID.");
            return null;
        }
        queriedBroadTypeQid = qid;
        status.setText("Finding direct subclasses of " + qid + "…");
        return new DiscoverSubtypesQuery(qid, 200);
    }

    private DiscoverCommonMembershipTypesQuery commonTypesQuery() {
        if (examples.isEmpty()) {
            status.setText("Add or choose at least one example entity.");
            return null;
        }
        if (examples.size() > DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES) {
            status.setText("Choose at most "
                    + DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES
                    + " examples; " + examples.size() + " are selected.");
            return null;
        }
        queriedExampleCount = examples.size();
        status.setText("Finding direct and inherited types shared by "
                + examples.size() + " example(s)…");
        return new DiscoverCommonMembershipTypesQuery(examples.values().stream()
                .map(value -> new MembershipTypeCandidate.Member(
                        value.qid(), value.label(), value.description())).toList());
    }

    private PreviewMembershipTypeQuery previewQuery() {
        MembershipTypeCandidateViewable selected = oneSelectedCandidate();
        if (selected == null) {
            status.setText("Select exactly one candidate type to preview.");
            return null;
        }
        String qid = selected.candidate().qid();
        status.setText("Counting up to " + PreviewMembershipTypeQuery.COUNT_BOUND
                + " and fetching up to " + PreviewMembershipTypeQuery.SAMPLE_LIMIT
                + " members of " + qid + "…");
        return new PreviewMembershipTypeQuery(qid, previewDescendants.isSelected());
    }

    void acceptSubtypes(List<MembershipTypeCandidate> result) {
        SwingUtilities.invokeLater(() -> {
            String qid = WikidataIds.isQid(queriedBroadTypeQid)
                    ? queriedBroadTypeQid : RuleNode.cleanQid(rootQid.getText());
            List<MembershipTypeCandidate> all = new ArrayList<>();
            if (WikidataIds.isQid(qid)) {
                all.add(new MembershipTypeCandidate(qid, broadTypeName(qid),
                        MembershipTypeCandidate.Origin.BROAD_TYPE, "",
                        0, 0, 0, -1, List.of()));
            }
            if (result != null) all.addAll(result);
            showCandidates(all);
            status.setText((result == null ? 0 : result.size())
                    + " direct subtype(s) found; the selected broad type is also offered.");
        });
    }

    void acceptCommonTypes(List<MembershipTypeCandidate> result) {
        SwingUtilities.invokeLater(() -> {
            showCandidates(result == null ? List.of() : result);
            status.setText((result == null ? 0 : result.size())
                    + " candidate type(s) found from " + queriedExampleCount
                    + " example(s).");
        });
    }

    private void acceptPreview(MembershipTypePreview result) {
        SwingUtilities.invokeLater(() -> {
            status.setText(previewSummary(result));
            JDialog dialog = new JDialog(quiz.ui.Dialogs.owner(this),
                    "Membership preview — " + result.qid(),
                    Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.add(previewView(result));
            dialog.setSize(900, 650);
            dialog.setLocationRelativeTo(this);
            quiz.ui.Dialogs.raiseOnOpen(dialog);
            dialog.setVisible(true);
        });
    }

    static JComponent previewView(MembershipTypePreview result) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel(previewSummary(result)), BorderLayout.NORTH);
        List<MembershipEntityViewable> members = result.members().stream()
                .map(MembershipEntityViewable::new).toList();
        if (members.isEmpty()) {
            panel.add(new JLabel("No members found."), BorderLayout.CENTER);
        } else {
            panel.add(SearchableView.builder(members)
                    .mode(RenderingMode.TABLE)
                    .sample(members.getFirst())
                    .valueLinker(WikidataLinks.valueLinker())
                    .build(), BorderLayout.CENTER);
        }
        return panel;
    }

    static String previewSummary(MembershipTypePreview result) {
        String count = result.countCapped()
                ? result.boundedCount() + "+" : Integer.toString(result.boundedCount());
        return count + " member(s)"
                + (result.includeSubclasses() ? " including subclasses" : " by direct P31")
                + "; showing " + result.members().size() + ".";
    }

    private void setExamples(List<WorkbenchSelections.Entity> selected) {
        examples.clear();
        if (selected != null) selected.forEach(value -> examples.put(value.qid(), value));
        showExamples();
        status.setText(exampleStatus("Selected " + examples.size() + " example(s)."));
    }

    private String exampleStatus(String prefix) {
        if (examples.size() > DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES) {
            return prefix + " Choose at most "
                    + DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES
                    + "; remove " + (examples.size()
                    - DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES) + ".";
        }
        return prefix + " Click Find common types; nothing is configured yet.";
    }

    private void showExamples() {
        selectedExamples = List.of();
        examplesHolder.removeAll();
        List<MembershipEntityViewable> values = examples.values().stream()
                .map(value -> new MembershipEntityViewable(
                        new MembershipTypeCandidate.Member(
                                value.qid(), value.label(), value.description())))
                .toList();
        if (values.isEmpty()) {
            examplesHolder.add(new JLabel("No examples selected."), BorderLayout.NORTH);
        } else {
            examplesHolder.add(SearchableView.builder(values)
                    .mode(RenderingMode.TABLE)
                    .sample(values.getFirst())
                    .valueLinker(WikidataLinks.valueLinker())
                    .selectionSetListener(selected -> {
                        selectedExamples = selected.stream()
                                .filter(MembershipEntityViewable.class::isInstance)
                                .map(MembershipEntityViewable.class::cast).toList();
                        updateActions();
                    }).build(), BorderLayout.CENTER);
        }
        removeExamples.setEnabled(false);
        examplesHolder.revalidate();
        examplesHolder.repaint();
        updateActions();
    }

    private void removeSelectedExamples() {
        selectedExamples.forEach(value -> examples.remove(value.qid()));
        showExamples();
        status.setText(examples.size() + " example(s) remain; nothing is configured.");
    }

    private void showCandidates(List<MembershipTypeCandidate> found) {
        selectedCandidates = List.of();
        candidates = found == null ? List.of() : found.stream()
                .map(MembershipTypeCandidateViewable::new).toList();
        candidatesHolder.removeAll();
        if (candidates.isEmpty()) {
            candidateView = new JLabel("Run broad-type or example-driven discovery.");
        } else {
            candidateView = SearchableView.builder(candidates)
                    .mode(RenderingMode.TABLE)
                    .sample(candidates.getFirst())
                    .valueLinker(WikidataLinks.valueLinker())
                    .selectionSetListener(selected -> {
                        selectedCandidates = selected.stream()
                                .filter(MembershipTypeCandidateViewable.class::isInstance)
                                .map(MembershipTypeCandidateViewable.class::cast).toList();
                        updateActions();
                    }).build();
        }
        candidatesHolder.add(candidateView, BorderLayout.CENTER);
        candidatesHolder.revalidate();
        candidatesHolder.repaint();
        updateActions();
    }

    private void useSelected() {
        MembershipTypeCandidateViewable selected = oneSelectedCandidate();
        if (selected == null || !usable) return;
        MembershipTypeCandidate candidate = selected.candidate();
        String refusal = onUseMembershipType.apply(new WorkbenchSelections.Entity(
                candidate.qid(), candidate.label()));
        status.setText(refusal == null || refusal.isBlank()
                ? "Used " + candidate.label() + " (" + candidate.qid()
                        + ") as the leading membership type of " + selectedClassName + "."
                : "Could not use " + candidate.label() + " (" + candidate.qid()
                        + "): " + refusal);
    }

    private void addSelected() {
        if (!addable) return;
        selectedCandidates.stream().map(MembershipTypeCandidateViewable::candidate)
                .map(MembershipTypeCandidate::qid).distinct()
                .forEach(onAddMembershipTarget);
        status.setText("Added " + selectedCandidates.size()
                + " additional membership type(s) to " + selectedClassName + ".");
    }

    private MembershipTypeCandidateViewable oneSelectedCandidate() {
        return selectedCandidates.size() == 1 ? selectedCandidates.getFirst() : null;
    }

    private void showBroadType() {
        String qid = RuleNode.cleanQid(rootQid.getText());
        String label = broadTypeName(qid);
        rootLabel.setText(WikidataIds.isQid(qid)
                ? label + (label.equals(qid) ? "" : " (" + qid + ")")
                : "No broad type selected");
        updateActions();
    }

    private String broadTypeName(String qid) {
        return qid != null && qid.equals(broadTypeQid)
                && broadTypeLabel != null && !broadTypeLabel.isBlank()
                ? broadTypeLabel : qid;
    }

    private void updateActions() {
        boolean running = runner != null && runner.isRunning();
        findBroadType.setEnabled(!running);
        findExample.setEnabled(!running);
        rootQid.setEnabled(!running);
        selectionsHolder.setEnabled(!running);
        for (Component component : selectionsHolder.getComponents()) {
            component.setEnabled(!running);
        }
        removeExamples.setEnabled(!running && !selectedExamples.isEmpty());
        runSubtypes.setEnabled(!running
                && WikidataIds.isQid(RuleNode.cleanQid(rootQid.getText())));
        boolean validExampleCount = !examples.isEmpty()
                && examples.size() <= DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES;
        findCommonTypes.setEnabled(!running && validExampleCount);
        findCommonTypes.setToolTipText(examples.size()
                > DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES
                        ? "Choose at most "
                                + DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES
                                + " examples; " + examples.size() + " are selected."
                        : null);
        MembershipTypeCandidateViewable one = oneSelectedCandidate();
        preview.setEnabled(!running && one != null);
        use.setEnabled(!running && usable && one != null);
        add.setEnabled(!running && addable && !selectedCandidates.isEmpty());
    }

    private void showError(String title, Exception failure) {
        SwingUtilities.invokeLater(() -> {
            String message = failure == null || failure.getMessage() == null
                    ? String.valueOf(failure) : failure.getMessage();
            status.setText(title + ": " + message);
            JOptionPane.showMessageDialog(this, quiz.ui.Dialogs.wrapped(message), title,
                    JOptionPane.ERROR_MESSAGE);
        });
    }

    // Behavioral/UI-test accessors.
    int resultCount() { return candidates.size(); }
    boolean addEnabled() { return add.isEnabled(); }
    boolean useEnabled() { return use.isEnabled(); }
    boolean findCommonTypesEnabled() { return findCommonTypes.isEnabled(); }
    boolean previewIncludesSubclasses() { return previewDescendants.isSelected(); }
    boolean previewSubclassSettingEditable() { return previewDescendants.isEnabled(); }
    String addRefusal() { return add.getToolTipText(); }
    String useRefusal() { return use.getToolTipText(); }
    String statusText() { return status.getText(); }
    JComponent candidateView() { return candidateView; }
    int exampleCount() { return examples.size(); }
    void setExamplesForTest(List<WorkbenchSelections.Entity> values) { setExamples(values); }
    void selectRows(int... selected) {
        List<MembershipTypeCandidateViewable> chosen = new ArrayList<>();
        for (int row : selected) {
            if (row >= 0 && row < candidates.size()) chosen.add(candidates.get(row));
        }
        selectedCandidates = List.copyOf(chosen);
        updateActions();
    }
    void addSelectedRows() { addSelected(); }
    void useSelectedRow() { useSelected(); }
}
