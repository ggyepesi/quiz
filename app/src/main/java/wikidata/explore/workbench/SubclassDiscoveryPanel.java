package wikidata.explore.workbench;

import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import wikidata.WikidataIds;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.logical.DiscoverCommonMembershipTypesQuery;
import wikidata.explore.query.logical.DiscoverSubtypesQuery;
import wikidata.explore.query.logical.PreviewMembershipTypeQuery;
import wikidata.explore.query.logical.SearchWikidataTextQuery;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.MembershipTypePreview;
import wikidata.explore.query.result.WikidataTextSearchResult;
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
    private final JButton findCommonTypes = new JButton("Find types shared by examples");
    private final JButton removeExamples = new JButton("Remove selected examples");
    private final JTextField textPhrase = new JTextField(24);
    private final JButton searchText = new JButton("Find exact phrase");
    private final JButton addTextExamples = new JButton("Add selected as examples");
    private final JButton cancel = new JButton("Cancel");
    private final JButton preview = new JButton("Preview selected type members");
    private final JButton use = new JButton("Use selected as membership type");
    private final JButton add = new JButton("Add selected as additional membership types");
    private final JCheckBox previewDescendants =
            new JCheckBox("Class membership includes subclasses", false);
    private final JLabel status = new JLabel(" ");
    private final JTabbedPane workflowStages = new JTabbedPane();
    private final JTabbedPane starts = new JTabbedPane();
    private final JPanel examplesHolder = new JPanel(new BorderLayout());
    private final JPanel selectionsHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel candidatesHolder = new JPanel(new BorderLayout());
    private final JPanel textResultsHolder = new JPanel(new BorderLayout());
    private final JPanel candidateActions = new JPanel(
            new objectview.utils.swing.WrapLayout(FlowLayout.LEFT, 6, 2));
    private final LinkedHashMap<String, WorkbenchSelections.Entity> examples =
            new LinkedHashMap<>();
    private List<MembershipEntityViewable> selectedExamples = List.of();
    private List<MembershipTypeCandidateViewable> candidates = List.of();
    private List<MembershipTypeCandidateViewable> selectedCandidates = List.of();
    private List<MembershipEntityViewable> textResults = List.of();
    private List<MembershipEntityViewable> selectedTextResults = List.of();
    private JComponent exampleView;
    private JComponent candidateView;
    private SwingQueryRunner runner;
    private WorkbenchSelections selections;
    private Consumer<String> onAddMembershipTarget = ignored -> { };
    private Function<WorkbenchSelections.Entity, String> onUseMembershipType =
            ignored -> "No membership editor is connected.";
    private String selectedClassName = "";
    private GeneratedProjectModel populationProject;
    private GeneratedClassModel populationClass;
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

        starts.addTab("From broad type", broadTypePanel());
        starts.addTab("From examples", examplesPanel());
        starts.addTab("From indexed text", textSearchPanel());
        workflowStages.addTab("1. Starting points", starts);

        showCandidates(List.of(), false);
        workflowStages.addTab("2. Candidate membership types", candidatesHolder);
        workflowStages.setEnabledAt(1, false);
        workflowStages.addChangeListener(event -> updateActions());
        add(workflowStages, BorderLayout.CENTER);

        preview.setEnabled(false);
        use.setEnabled(false);
        add.setEnabled(false);
        previewDescendants.setEnabled(false);
        candidateActions.add(previewDescendants);
        candidateActions.add(preview);
        candidateActions.add(use);
        candidateActions.add(add);
        JPanel runState = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        runState.add(cancel);
        runState.add(status);
        JPanel south = new JPanel(new BorderLayout());
        south.add(candidateActions, BorderLayout.NORTH);
        south.add(runState, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        findBroadType.addActionListener(event -> chooseBroadType());
        findExample.addActionListener(event -> chooseExample());
        removeExamples.addActionListener(event -> removeSelectedExamples());
        addTextExamples.addActionListener(event -> addTextResultsAsExamples());
        textPhrase.addActionListener(event -> searchText.doClick());
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

    private JComponent textSearchPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(new JLabel("<html>Search Wikidata's indexed entity text: labels, "
                + "descriptions, aliases in every language and some statements.<br>"
                + "The match may not appear in the English columns. Text hits nominate "
                + "examples; they do not establish membership.</html>"),
                BorderLayout.NORTH);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Exact phrase:"));
        controls.add(textPhrase);
        controls.add(searchText);
        addTextExamples.setEnabled(false);
        controls.add(addTextExamples);
        header.add(controls, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        textResultsHolder.add(new JLabel("No text search has been run."),
                BorderLayout.CENTER);
        panel.add(textResultsHolder, BorderLayout.CENTER);
        return panel;
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
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(new JLabel("<html>One example is enough to find its direct and inherited "
                + "types. Add more examples only to rank the types they share; adding an "
                + "example does not configure the class.</html>"), BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actions.add(findExample);
        actions.add(selectionsHolder);
        removeExamples.setEnabled(false);
        actions.add(removeExamples);
        actions.add(findCommonTypes);
        header.add(actions, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
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
        runner.wireButton(searchText, this::acceptTextResults, this::textSearchQuery,
                failure -> showError("Wikidata text search failed", failure));
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

    void showClass(GeneratedProjectModel project, GeneratedClassModel clazz) {
        selectedClassName = clazz == null ? "" : clazz.className();
        boolean editableSource = clazz != null
                && clazz.classKind() == wikidata.explore.model.ClassKind.SOURCE
                && !clazz.isImported();
        populationProject = editableSource ? project : null;
        populationClass = editableSource ? clazz : null;
        EntityBound.Kind membershipKind = clazz == null
                ? EntityBound.Kind.UNBOUNDED : clazz.membership().kind();
        boolean plainTypeMembership = clazz != null
                && membershipKind == EntityBound.Kind.RELATION
                && "P31".equalsIgnoreCase(clazz.membership().relationPid())
                && clazz.membership().qualifierCondition() == null;
        String populationSourceRefusal =
                PopulationSourceEditing.wikidataMembershipRefusal(clazz);
        boolean wikidataPopulation = populationSourceRefusal.isEmpty();
        usable = editableSource && wikidataPopulation
                && (membershipKind == EntityBound.Kind.UNBOUNDED
                || plainTypeMembership);
        addable = editableSource && wikidataPopulation && plainTypeMembership;
        useRefusal = wikidataPopulation
                ? useRefusal(clazz, editableSource, membershipKind)
                : populationSourceRefusal;
        addRefusal = !wikidataPopulation ? populationSourceRefusal
                : membershipKind == EntityBound.Kind.UNBOUNDED
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

    private SearchWikidataTextQuery textSearchQuery() {
        String phrase = textPhrase.getText() == null ? "" : textPhrase.getText().strip();
        if (phrase.isBlank()) {
            status.setText("Enter an exact Wikidata entity-text phrase.");
            return null;
        }
        status.setText("Searching Wikidata for the exact phrase “" + phrase + "”…");
        return new SearchWikidataTextQuery(phrase);
    }

    private PreviewMembershipTypeQuery previewQuery() {
        MembershipTypeCandidateViewable selected = oneSelectedCandidate();
        if (selected == null) {
            status.setText("Select exactly one candidate type to preview.");
            return null;
        }
        String qid = selected.candidate().qid();
        RuleNode classRule = populationRule();
        status.setText("Counting up to " + PreviewMembershipTypeQuery.COUNT_BOUND
                + " members of " + qid + "; populations up to "
                + PreviewMembershipTypeQuery.MEMBER_LIMIT
                + " will be shown completely"
                + (classRule == null ? "" : ", then counting those that pass "
                        + classRule.name() + "'s population filters")
                + "…");
        return new PreviewMembershipTypeQuery(qid, selected.candidate().label(),
                previewDescendants.isSelected(), classRule);
    }

    /** The same effective class rule generation compiles, including inherited
     * membership settings and required fields from the containing project. */
    RuleNode populationRule() {
        return populationClass == null ? null
                : wikidata.explore.rule.RuleTreeCompiler.compileClass(
                        populationClass, populationProject);
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
            showCandidates(all, true);
            status.setText((result == null ? 0 : result.size())
                    + " direct subtype(s) found; the selected broad type is also offered.");
        });
    }

    void acceptCommonTypes(List<MembershipTypeCandidate> result) {
        SwingUtilities.invokeLater(() -> {
            showCandidates(result == null ? List.of() : result, true);
            status.setText((result == null ? 0 : result.size())
                    + " candidate type(s) found from " + queriedExampleCount
                    + " example(s).");
        });
    }

    void acceptTextResults(WikidataTextSearchResult result) {
        SwingUtilities.invokeLater(() -> {
            selectedTextResults = List.of();
            textResults = result == null ? List.of() : result.candidates().stream()
                    .map(MembershipEntityViewable::new).toList();
            textResultsHolder.removeAll();
            if (textResults.isEmpty()) {
                textResultsHolder.add(new JLabel("No exact text matches found."),
                        BorderLayout.CENTER);
            } else {
                textResultsHolder.add(SearchableView.builder(textResults)
                        .mode(RenderingMode.TABLE)
                        .sample(textResults.getFirst())
                        .valueLinker(WikidataLinks.valueLinker())
                        .selectionSetListener(selected -> {
                            selectedTextResults = selected.stream()
                                    .filter(MembershipEntityViewable.class::isInstance)
                                    .map(MembershipEntityViewable.class::cast).toList();
                            updateActions();
                        }).build(), BorderLayout.CENTER);
            }
            textResultsHolder.revalidate();
            textResultsHolder.repaint();
            String count = result == null ? "0" : result.countText();
            status.setText(count + " text candidate(s) found; select those that should "
                    + "become membership examples.");
            updateActions();
        });
    }

    private void addTextResultsAsExamples() {
        int newCount = additionalTextExampleCount();
        if (examples.size() + newCount > DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES) {
            status.setText("Could not add " + newCount + " new example(s): that would make "
                    + (examples.size() + newCount) + "; choose at most "
                    + DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES + ".");
            updateActions();
            return;
        }
        int before = examples.size();
        for (MembershipEntityViewable viewable : selectedTextResults) {
            MembershipTypeCandidate.Member member = viewable.member();
            WorkbenchSelections.Entity entity = new WorkbenchSelections.Entity(
                    member.qid(), member.label(), member.description());
            examples.put(member.qid(), entity);
            if (selections != null) {
                selections.entity(member.qid(), member.label(), member.description());
            }
        }
        int added = examples.size() - before;
        int alreadyPresent = selectedTextResults.size() - added;
        showExamples();
        starts.setSelectedIndex(1);
        String duplicateText = alreadyPresent == 0 ? ""
                : " " + alreadyPresent + " selected candidate(s) were already examples.";
        status.setText(exampleStatus("Added " + added + " new text candidate(s) as examples."
                + duplicateText));
    }

    private int additionalTextExampleCount() {
        return (int) selectedTextResults.stream().map(MembershipEntityViewable::qid)
                .distinct().filter(qid -> !examples.containsKey(qid)).count();
    }

    private void acceptPreview(MembershipTypePreview result) {
        SwingUtilities.invokeLater(() -> {
            status.setText(previewSummary(result));
            JDialog dialog = new JDialog(quiz.ui.Dialogs.owner(this),
                    "Membership preview — " + typeName(result),
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
        List<PreviewMemberViewable> members = result.members().stream()
                .map(member -> new PreviewMemberViewable(member, result.classFilters()))
                .toList();
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
        boolean complete = !result.countCapped()
                && result.members().size() == result.boundedCount();
        return typeName(result) + ": " + count + " member(s)"
                + (result.includeSubclasses() ? " including subclasses" : " by direct P31")
                + (complete ? "; showing all " + result.members().size() + "."
                : "; preview limit " + PreviewMembershipTypeQuery.MEMBER_LIMIT
                        + ", showing " + result.members().size() + ".")
                + classFilterSummary(result);
    }

    /** What the selected class itself would generate from this type. */
    static String classFilterSummary(MembershipTypePreview result) {
        MembershipTypePreview.ClassFilters filters = result.classFilters();
        if (filters == null) return "";
        if (!filters.checked()) {
            return " " + filters.className() + " applies no population filter beyond its "
                    + "type" + limitNote(filters, result.boundedCount()) + ".";
        }
        String kept = filters.capped() ? filters.count() + "+"
                : Integer.toString(filters.count());
        return " " + kept + " pass " + filters.className() + "'s population filters ("
                + String.join("; ", filters.filters()) + ")"
                + limitNote(filters, filters.count()) + ".";
    }

    private static String limitNote(MembershipTypePreview.ClassFilters filters, int count) {
        return filters.classLimit() > 0 && count > filters.classLimit()
                ? "; its class limit keeps " + filters.classLimit() : "";
    }

    private static String typeName(MembershipTypePreview result) {
        return result.label().isBlank() || result.label().equals(result.qid())
                ? result.qid() : result.label() + " (" + result.qid() + ")";
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
        return prefix + " Click Find types shared by examples; nothing is configured yet.";
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
            exampleView = new JLabel("No examples selected.");
        } else {
            exampleView = SearchableView.builder(values)
                    .mode(RenderingMode.TABLE)
                    .sample(values.getFirst())
                    .valueLinker(WikidataLinks.valueLinker())
                    .selectionSetListener(selected -> {
                        selectedExamples = selected.stream()
                                .filter(MembershipEntityViewable.class::isInstance)
                                .map(MembershipEntityViewable.class::cast).toList();
                        updateActions();
                    }).build();
        }
        examplesHolder.add(exampleView, BorderLayout.CENTER);
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

    private void showCandidates(List<MembershipTypeCandidate> found,
            boolean discoveryComplete) {
        selectedCandidates = List.of();
        candidates = found == null ? List.of() : found.stream()
                .map(MembershipTypeCandidateViewable::new).toList();
        candidatesHolder.removeAll();
        if (candidates.isEmpty()) {
            candidateView = new JLabel(discoveryComplete
                    ? "No candidate membership types found. Return to Starting points to try "
                            + "different examples or a different broad type."
                    : "Candidate membership types will appear here after discovery.");
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
        if (workflowStages.getTabCount() > 1) {
            workflowStages.setEnabledAt(1, discoveryComplete);
            if (discoveryComplete) workflowStages.setSelectedIndex(1);
        }
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
        searchText.setEnabled(!running);
        textPhrase.setEnabled(!running);
        boolean textExamplesFit = examples.size() + additionalTextExampleCount()
                <= DiscoverCommonMembershipTypesQuery.MAX_EXAMPLES;
        addTextExamples.setEnabled(!running && !selectedTextResults.isEmpty()
                && textExamplesFit);
        addTextExamples.setToolTipText(textExamplesFit ? null
                : "Adding these candidates would exceed the 20-example limit.");
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
        boolean candidatesVisible = workflowStages.getTabCount() > 1
                && workflowStages.getSelectedIndex() == 1;
        candidateActions.setVisible(candidatesVisible);
        cancel.setVisible(running);
        preview.setEnabled(!running && candidatesVisible && one != null);
        use.setEnabled(!running && candidatesVisible && usable && one != null);
        add.setEnabled(!running && candidatesVisible && addable
                && !selectedCandidates.isEmpty());
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
    JComponent exampleView() { return exampleView; }
    JTabbedPane workflowStages() { return workflowStages; }
    JTabbedPane startingPointTabs() { return starts; }
    int exampleCount() { return examples.size(); }
    int textResultCount() { return textResults.size(); }
    boolean addTextExamplesEnabled() { return addTextExamples.isEnabled(); }
    JTextField textPhrase() { return textPhrase; }
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
    void selectTextRows(int... selected) {
        List<MembershipEntityViewable> chosen = new ArrayList<>();
        for (int row : selected) {
            if (row >= 0 && row < textResults.size()) chosen.add(textResults.get(row));
        }
        selectedTextResults = List.copyOf(chosen);
        updateActions();
    }
    void addSelectedTextRowsAsExamples() { addTextResultsAsExamples(); }
}
