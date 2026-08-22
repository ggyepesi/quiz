package quiz.curation.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import objectview.Viewable;
import quiz.curation.Correction;
import quiz.curation.FieldDeclaration;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import quiz.curation.Merge;
import domain.DomainModel;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only inventory of the directives in one {@code *.curation.json} sidecar.
 * The sidecar is current overlay state, not an audit log: replaced values are not
 * available and no chronological ordering is implied.
 */
public final class CurationOverviewPanel extends JPanel {

    private static final String ALL_CLASSES = "All classes";
    private static final String UNSPECIFIED_CLASS = "Unspecified class";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DomainModel domain;
    private final ManualCuration curation;
    private List<Operation> allOperations;
    private final Map<EntityKey, String> labels;
    private final Map<String, String> uniqueLabelsById;

    private final JComboBox<String> classFilter = new JComboBox<>();
    private final JComboBox<KindFilter> kindFilter =
            new JComboBox<>(KindFilter.values());
    private final JTextField textFilter = new JTextField(18);
    private final JLabel summary = new JLabel();
    private final JButton promoteRule = new JButton("Promote rule to model…");
    private final JTree tree = new JTree();
    private final OperationTableModel tableModel = new OperationTableModel();
    private final JTable table = new JTable(tableModel);
    private final JPanel detail = new JPanel(new BorderLayout(6, 6));
    private List<Operation> displayedOperations = List.of();
    private Operation selectedOperation;

    public CurationOverviewPanel(DomainModel domain, ManualCuration curation) {
        this.domain = java.util.Objects.requireNonNull(domain);
        this.curation = java.util.Objects.requireNonNull(curation);
        this.labels = indexLabels(domain);
        this.uniqueLabelsById = uniqueLabels(labels);
        this.allOperations = buildOperations();

        setLayout(new BorderLayout(6, 6));
        add(toolbar(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        installFilters();
        rebuildTree();
    }

    private JComponent toolbar() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        filters.add(new JLabel("Class:"));
        classFilter.addItem(ALL_CLASSES);
        LinkedHashSet<String> classes = new LinkedHashSet<>(domain.types());
        allOperations.stream().map(Operation::typeLabel).forEach(classes::add);
        classes.stream().filter(value -> value != null && !value.isBlank())
                .forEach(classFilter::addItem);
        filters.add(classFilter);
        filters.add(new JLabel("Directive:"));
        filters.add(kindFilter);
        filters.add(new JLabel("Find:"));
        filters.add(textFilter);
        JButton clear = new JButton("Clear filters");
        clear.addActionListener(e -> {
            classFilter.setSelectedItem(ALL_CLASSES);
            kindFilter.setSelectedItem(KindFilter.ALL);
            textFilter.setText("");
        });
        actions.add(clear);
        JButton refresh = new JButton("Refresh");
        refresh.setToolTipText("Read the current in-memory curation state again");
        refresh.addActionListener(e -> {
            allOperations = buildOperations();
            rebuildTree();
        });
        actions.add(refresh);
        promoteRule.setEnabled(false);
        promoteRule.addActionListener(e -> promoteSelectedRule());
        actions.add(promoteRule);
        actions.add(summary);
        panel.add(filters);
        panel.add(actions);
        return panel;
    }

    private JComponent content() {
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.addTreeSelectionListener(e -> showBranch(
                branchAt(tree.getSelectionPath())));

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                showDetail(null);
                return;
            }
            int modelRow = table.convertRowIndexToModel(viewRow);
            showDetail(tableModel.operationAt(modelRow));
        });

        JPanel right = new JPanel(new BorderLayout(6, 6));
        workbench.WikidataLinks.installOnColumn(table, 3);
        JScrollPane operations = new JScrollPane(table);
        operations.setBorder(BorderFactory.createTitledBorder("Curation directives"));
        detail.setBorder(BorderFactory.createTitledBorder("Directive details"));
        showDetail(null);
        JSplitPane vertical = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, operations, detail);
        vertical.setResizeWeight(0.58);
        vertical.setOneTouchExpandable(true);
        right.add(vertical, BorderLayout.CENTER);

        JScrollPane navigation = new JScrollPane(tree);
        navigation.setBorder(BorderFactory.createTitledBorder(
                "Class → directive type → entity"));
        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, navigation, right);
        split.setResizeWeight(0.3);
        split.setOneTouchExpandable(true);
        return split;
    }

    private JComponent footer() {
        String path = curation.file() == null
                ? "No curation file" : curation.file().getAbsolutePath();
        JLabel label = new JLabel("Current overlay state · " + path);
        label.setForeground(Color.GRAY);
        label.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        label.setToolTipText(
                "The sidecar stores current directives, not replaced values or timestamps.");
        return label;
    }

    private void installFilters() {
        classFilter.addActionListener(e -> rebuildTree());
        kindFilter.addActionListener(e -> rebuildTree());
        textFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuildTree(); }
            @Override public void removeUpdate(DocumentEvent e) { rebuildTree(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuildTree(); }
        });
    }

    private void rebuildTree() {
        List<Operation> filtered = allOperations.stream()
                .filter(this::matchesFilters)
                .sorted(Comparator.comparing(Operation::typeLabel)
                        .thenComparing(this::entityLabel)
                        .thenComparing(op -> op.kind().ordinal())
                        .thenComparing(Operation::title))
                .toList();

        Branch rootBranch = new Branch(
                "Curation (" + countSummary(filtered) + ")", filtered);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootBranch);
        Map<String, Map<Kind, Map<EntityKey, List<Operation>>>> grouped =
                new LinkedHashMap<>();
        for (Operation operation : filtered) {
            grouped.computeIfAbsent(operation.typeLabel(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(operation.kind(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(operation.entity(), ignored -> new ArrayList<>())
                    .add(operation);
        }

        grouped.forEach((type, directives) -> {
            List<Operation> typeOperations = directives.values().stream()
                    .flatMap(entities -> entities.values().stream())
                    .flatMap(Collection::stream).toList();
            DefaultMutableTreeNode typeNode = new DefaultMutableTreeNode(
                    new Branch(type + " (" + typeOperations.size() + ")", typeOperations));
            root.add(typeNode);
            directives.forEach((kind, entities) -> {
                List<Operation> kindOperations = entities.values().stream()
                        .flatMap(Collection::stream).toList();
                DefaultMutableTreeNode kindNode = new DefaultMutableTreeNode(
                        new Branch(kind.branchLabel + " (" + kindOperations.size() + ")",
                                kindOperations));
                typeNode.add(kindNode);
                entities.forEach((entity, operations) -> {
                    DefaultMutableTreeNode entityNode = new DefaultMutableTreeNode(
                            new Branch(entityLabel(entity) + " (" + operations.size() + ")",
                                    List.copyOf(operations)));
                    kindNode.add(entityNode);
                    for (Operation operation : operations) {
                        entityNode.add(new DefaultMutableTreeNode(
                                new Branch(operation.title(), List.of(operation))));
                    }
                });
            });
        });

        tree.setModel(new DefaultTreeModel(root));
        tree.expandPath(new TreePath(root.getPath()));
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode typeNode =
                    (DefaultMutableTreeNode) root.getChildAt(i);
            tree.expandPath(new TreePath(typeNode.getPath()));
        }
        tree.setSelectionRow(0);
        showBranch(rootBranch);
        updateSummary(filtered);
    }

    private boolean matchesFilters(Operation operation) {
        String selectedClass = (String) classFilter.getSelectedItem();
        if (selectedClass != null && !ALL_CLASSES.equals(selectedClass)
                && !selectedClass.equals(operation.typeLabel())) return false;
        KindFilter selectedKind = (KindFilter) kindFilter.getSelectedItem();
        if (selectedKind != null && selectedKind.kind != null
                && selectedKind.kind != operation.kind()) return false;
        String needle = textFilter.getText().trim().toLowerCase(Locale.ROOT);
        return needle.isEmpty() || operation.searchText(this).contains(needle);
    }

    private void updateSummary(List<Operation> shown) {
        summary.setText("   " + countSummary(shown));
    }

    private static String countSummary(List<Operation> operations) {
        long fields = operations.stream()
                .filter(op -> op.kind() == Kind.FIELD_DEFINITION).count();
        long corrections = operations.stream()
                .filter(op -> op.kind() == Kind.CORRECTION).count();
        long merges = operations.stream()
                .filter(op -> op.kind() == Kind.MERGE).count();
        long identities = operations.stream()
                .filter(op -> op.kind() == Kind.IDENTITY).count();
        return fields + " field definitions · " + corrections + " corrections · "
                + merges + " merges · "
                + identities + " identity links";
    }

    private void showBranch(Branch branch) {
        displayedOperations = branch == null ? List.of() : branch.operations();
        tableModel.setOperations(displayedOperations);
        table.clearSelection();
        showDetail(displayedOperations.size() == 1 ? displayedOperations.get(0) : null);
    }

    private static Branch branchAt(TreePath path) {
        if (path == null || !(path.getLastPathComponent()
                instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof Branch branch)) return null;
        return branch;
    }

    private void showDetail(Operation operation) {
        selectedOperation = operation;
        updatePromotionAction(operation);
        detail.removeAll();
        if (operation == null) {
            detail.add(new JLabel(
                    "  Select a directive to inspect its saved values."),
                    BorderLayout.NORTH);
        } else {
            detail.add(detailHeader(operation), BorderLayout.NORTH);
            if (operation.source() instanceof Merge merge) {
                detail.add(mergeDetails(merge), BorderLayout.CENTER);
            } else {
                JTextArea value = new JTextArea(operation.detailText());
                value.setEditable(false);
                value.setLineWrap(true);
                value.setWrapStyleWord(true);
                value.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                value.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                detail.add(new JScrollPane(value), BorderLayout.CENTER);
            }
        }
        detail.revalidate();
        detail.repaint();
    }

    private void updatePromotionAction(Operation operation) {
        quiz.curation.FieldRulePromoter promoter =
                domain.capability(quiz.curation.FieldRulePromoter.class);
        if ((promoter == null)
                || operation == null
                || !(operation.source() instanceof Correction correction)) {
            promoteRule.setEnabled(false);
            promoteRule.setToolTipText(
                    "Select a sourced field correction from a model-backed dataset.");
            return;
        }
        quiz.curation.FieldRulePromoter.PromotionPreview preview =
                promoter.previewPromotion(correction);
        promoteRule.setEnabled(preview.eligible());
        promoteRule.setToolTipText(preview.eligible()
                ? "Add this reusable class/field source rule to the ModelBuilder model."
                : preview.reason());
    }

    private void promoteSelectedRule() {
        quiz.curation.FieldRulePromoter promoter =
                domain.capability(quiz.curation.FieldRulePromoter.class);
        if ((promoter == null)
                || selectedOperation == null
                || !(selectedOperation.source() instanceof Correction correction)) {
            return;
        }
        quiz.curation.FieldRulePromoter.PromotionPreview preview =
                promoter.previewPromotion(correction);
        if (!preview.eligible()) {
            JOptionPane.showMessageDialog(this, preview.reason());
            updatePromotionAction(selectedOperation);
            return;
        }
        String action = preview.addsField()
                ? "Add the field and its source rule"
                : "Update the field definition and source rule"
                        + (preview.previousProperty().isBlank() ? ""
                        : " (currently " + preview.previousProperty() + ")");
        JTextArea explanation = new JTextArea(
                action + "\n\n"
                        + "Model: " + preview.modelPath() + "\n"
                        + "Target: " + preview.targetType() + "." + preview.field() + "\n"
                        + "Field type: " + preview.fieldType() + "\n"
                        + "Source: " + preview.sourceKind() + " "
                        + blankAsDash(preview.sourceEntity()) + " / "
                        + preview.sourceProperty() + "\n\n"
                        + "The rule will run for every applicable instance on the next "
                        + "ModelBuilder generation. The current snapshot is not regenerated now, "
                        + "and the reviewed correction remains in curation.");
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setColumns(72);
        explanation.setRows(11);
        int answer = JOptionPane.showConfirmDialog(this, new JScrollPane(explanation),
                "Promote rule to ModelBuilder model",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try {
            promoter.promote(correction);
            JOptionPane.showMessageDialog(this,
                    "Promoted " + preview.targetType() + "." + preview.field()
                            + " ← " + preview.sourceProperty()
                            + ".\nRegenerate the dataset in ModelBuilder to apply it broadly.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Promotion failed: " + ex.getMessage(),
                    "Promotion failed", JOptionPane.ERROR_MESSAGE);
        }
        updatePromotionAction(selectedOperation);
    }

    private JComponent detailHeader(Operation operation) {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 3));
        addDetailRow(panel, "Directive", operation.kind().label);
        addDetailRow(panel, "Class", operation.typeLabel());
        addDetailRow(panel, "Entity", entityLabel(operation));
        addDetailRow(panel, "Origin", blankAsDash(operation.origin()));
        if (operation.source() instanceof Correction correction) {
            addDetailRow(panel, "Application policy",
                    correction.effectivePolicy().toString());
            if (correction.source() != null && correction.source().isPresent()) {
                addDetailRow(panel, "Value source", sourceSummary(correction));
            }
        }
        if (operation.source() instanceof FieldDeclaration) {
            addDetailRow(panel, "Scope", "Class schema");
        }
        return panel;
    }

    private JComponent mergeDetails(Merge merge) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JPanel identities = new JPanel(new GridLayout(0, 2, 8, 3));
        addDetailRow(identities, "Primary", entityLabel(
                new EntityKey(normalizeType(merge.type()), merge.primary())));
        addDetailRow(identities, "Duplicate", entityLabel(
                new EntityKey(normalizeType(merge.type()), merge.duplicate())));
        panel.add(identities, BorderLayout.NORTH);
        if (merge.fieldSource().isEmpty()) {
            panel.add(new JLabel(
                    "  Default resolution: fill empty primary values, union collections/maps, "
                            + "otherwise keep primary."), BorderLayout.CENTER);
        } else {
            String[] columns = {"Field", "Approved source"};
            Object[][] rows = merge.fieldSource().entrySet().stream()
                    .map(entry -> new Object[]{entry.getKey(), entry.getValue()})
                    .toArray(Object[][]::new);
            JTable decisions = new JTable(rows, columns);
            decisions.setAutoCreateRowSorter(true);
            panel.add(new JScrollPane(decisions), BorderLayout.CENTER);
        }
        return panel;
    }

    private static void addDetailRow(JPanel panel, String name, String value) {
        JLabel key = new JLabel(name + ":");
        key.setFont(key.getFont().deriveFont(Font.BOLD));
        panel.add(key);
        panel.add(new JLabel(value == null ? "—" : value));
    }

    private List<Operation> buildOperations() {
        List<Operation> result = new ArrayList<>();
        for (FieldDeclaration field : curation.fieldDeclarations()) {
            String type = normalizeType(field.type());
            result.add(new Operation(
                    Kind.FIELD_DEFINITION,
                    new EntityKey(type, "Class schema"),
                    "Declare field “" + field.name() + "”",
                    field.typeLabel()
                            + (field.targetType() == null || field.targetType().isBlank()
                            ? "" : " → " + field.targetType()),
                    "schema",
                    field));
        }
        for (Correction correction : curation.corrections()) {
            String type = normalizeType(correction.type());
            result.add(new Operation(
                    Kind.CORRECTION,
                    new EntityKey(type, correction.qid()),
                    correction.field(),
                    "Field “" + correction.field() + "” = "
                            + oneLine(correction.value())
                            + sourceSuffix(correction),
                    correction.origin(),
                    correction));
        }
        for (Merge merge : curation.merges()) {
            String type = normalizeType(merge.type());
            result.add(new Operation(
                    Kind.MERGE,
                    new EntityKey(type, merge.primary()),
                    "Merge " + labelFor(type, merge.duplicate()) + " → "
                            + labelFor(type, merge.primary()),
                    merge.fieldSource().isEmpty()
                            ? "Default field resolution"
                            : merge.fieldSource().size() + " explicit field decisions",
                    merge.origin(),
                    merge));
        }
        for (IdentityLink link : curation.identityLinks()) {
            String type = normalizeType(link.type());
            result.add(new Operation(
                    Kind.IDENTITY,
                    new EntityKey(type, link.targetId()),
                    blankAsDash(link.sourceKind()) + " identity",
                    blankAsDash(link.sourceId())
                            + (link.canonicalName() == null || link.canonicalName().isBlank()
                            ? "" : " · " + link.canonicalName()),
                    link.origin(),
                    link));
        }
        return List.copyOf(result);
    }

    private String entityLabel(Operation operation) {
        return entityLabel(operation.entity());
    }

    /** The row's QID as a bare id so it renders as a Wikidata link: a resolved identity
     *  link's target when present, else the entity's own id when Wikidata-shaped. */
    private String entityQid(Operation operation) {
        if (operation.source() instanceof IdentityLink link
                && workbench.WikidataLinks.isId(link.sourceId())) {
            return link.sourceId();
        }
        String id = operation.entity().id();
        return workbench.WikidataLinks.isId(id) ? id : "";
    }

    private String entityLabel(EntityKey entity) {
        return labelFor(entity.type(), entity.id());
    }

    private String labelFor(String type, String id) {
        String label = labels.get(new EntityKey(type, id));
        if (label == null) label = uniqueLabelsById.get(id);
        if (label == null || label.isBlank() || label.equals(id)) return blankAsDash(id);
        return label + " (" + id + ")";
    }

    private static Map<EntityKey, String> indexLabels(DomainModel domain) {
        Map<EntityKey, String> result = new LinkedHashMap<>();
        for (Viewable value : domain.instances()) {
            if (value == null || value.getIdentifier() == null) continue;
            String displayName = value.getDisplayName();
            if (displayName == null || displayName.isBlank()) {
                displayName = value.getIdentifier();
            }
            Set<String> types = new LinkedHashSet<>(domain.directClasses(value));
            if (value.typeName() != null) types.add(value.typeName());
            for (String type : types) {
                result.putIfAbsent(
                        new EntityKey(normalizeType(type), value.getIdentifier()),
                        displayName);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> uniqueLabels(Map<EntityKey, String> labels) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> ambiguous = new java.util.HashSet<>();
        labels.forEach((key, label) -> {
            String previous = result.putIfAbsent(key.id(), label);
            if (previous != null && !java.util.Objects.equals(previous, label)) {
                ambiguous.add(key.id());
            }
        });
        ambiguous.forEach(result::remove);
        return Map.copyOf(result);
    }

    private static String normalizeType(String type) {
        return type == null || type.isBlank() ? UNSPECIFIED_CLASS : type;
    }

    private static String blankAsDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String oneLine(Object value) {
        if (value == null) return "null";
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private static String pretty(Object value) {
        if (value == null) return "null";
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private static String evidenceDetail(
            List<datasource.evidence.ExtractedClaim> claims) {
        if (claims == null || claims.isEmpty()) return "\nEvidence: —";
        String ids = claims.stream().map(datasource.evidence.ExtractedClaim::assessmentId)
                .map(id -> id.substring(0, Math.min(12, id.length())))
                .collect(java.util.stream.Collectors.joining(", "));
        int fragments = claims.stream().mapToInt(c -> c.evidence().size()).sum();
        return "\nEvidence: " + claims.size() + " claim(s), " + fragments + " fragment(s)"
                + "\nAssessment IDs: " + ids
                + "\nEvidence state: accepted at recorded source version; "
                + "refresh source to revalidate";
    }

    private static String sourceSuffix(Correction correction) {
        return correction.source() == null || !correction.source().isPresent()
                ? "" : " · " + sourceSummary(correction);
    }

    private static String sourceSummary(Correction correction) {
        quiz.curation.ValueSource source = correction.source();
        if (source == null) return "—";
        List<String> parts = new ArrayList<>();
        if (source.kind() != null && !source.kind().isBlank()) parts.add(source.kind());
        if (source.entityId() != null && !source.entityId().isBlank()) {
            parts.add(source.entityId());
        }
        if (source.propertyId() != null && !source.propertyId().isBlank()) {
            parts.add(source.propertyId());
        }
        return parts.isEmpty() ? blankAsDash(source.recordUrl()) : String.join(" · ", parts);
    }

    int operationCount() {
        return allOperations.size();
    }

    List<String> navigationLabels() {
        Object root = tree.getModel().getRoot();
        if (!(root instanceof DefaultMutableTreeNode node)) return List.of();
        return java.util.Collections.list(node.breadthFirstEnumeration()).stream()
                .map(Object::toString)
                .toList();
    }

    private enum Kind {
        FIELD_DEFINITION("Field definition", "Field definitions"),
        CORRECTION("Correction", "Corrections"),
        MERGE("Merge", "Merges"),
        IDENTITY("Identity link", "Identity links");
        private final String label;
        private final String branchLabel;
        Kind(String label, String branchLabel) {
            this.label = label;
            this.branchLabel = branchLabel;
        }
    }

    private enum KindFilter {
        ALL("All directives", null),
        FIELD_DEFINITIONS("Field definitions", Kind.FIELD_DEFINITION),
        CORRECTIONS("Corrections", Kind.CORRECTION),
        MERGES("Merges", Kind.MERGE),
        IDENTITIES("Identity links", Kind.IDENTITY);

        private final String label;
        private final Kind kind;
        KindFilter(String label, Kind kind) { this.label = label; this.kind = kind; }
        @Override public String toString() { return label; }
    }

    private record EntityKey(String type, String id) { }

    private record Branch(String label, List<Operation> operations) {
        private Branch {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
        @Override public String toString() { return label; }
    }

    private record Operation(
            Kind kind,
            EntityKey entity,
            String title,
            String summary,
            String origin,
            Object source) {

        String typeLabel() { return entity.type(); }

        String detailText() {
            if (source instanceof Correction correction) {
                return "Field: " + correction.field()
                        + "\nPolicy: " + correction.effectivePolicy()
                        + "\nSource kind: " + (correction.source() == null ? "—"
                                : blankAsDash(correction.source().kind()))
                        + "\nSource entity: " + (correction.source() == null ? "—"
                                : blankAsDash(correction.source().entityId()))
                        + "\nSource property: " + (correction.source() == null ? "—"
                                : blankAsDash(correction.source().propertyId()))
                        + "\nSource URL: " + (correction.source() == null ? "—"
                                : blankAsDash(correction.source().recordUrl()))
                        + evidenceDetail(correction.source() == null ? List.of()
                                : correction.source().evidence())
                        + "\nValue kind: " + blankAsDash(correction.valueKind())
                        + "\nSaved value:\n" + pretty(correction.value());
            }
            if (source instanceof IdentityLink link) {
                return "Source: " + blankAsDash(link.sourceKind())
                        + "\nSource ID: " + blankAsDash(link.sourceId())
                        + "\nCanonical name: " + blankAsDash(link.canonicalName())
                        + "\nRecord URL: " + blankAsDash(link.recordUrl())
                        + evidenceDetail(link.evidence());
            }
            if (source instanceof FieldDeclaration field) {
                return "Field: " + field.name()
                        + "\nType: " + blankAsDash(field.typeLabel())
                        + "\nKind: " + field.kind()
                        + "\nElement kind: " + field.valueKind()
                        + "\nCardinality: " + (field.collection() ? "collection" : "single")
                        + "\nReference target: " + blankAsDash(field.targetType())
                        + "\nDisplay: " + (field.inline() ? "inline"
                                : field.annotatedReference() ? "reference" : "auto");
            }
            return summary;
        }

        String searchText(CurationOverviewPanel owner) {
            String extra = source instanceof Merge merge
                    ? merge.duplicate() + " " + merge.fieldSource()
                    : source instanceof Correction correction
                    ? oneLine(correction.value()) + " " + correction.valueKind()
                            + " " + correction.effectivePolicy() + " "
                            + sourceSummary(correction) + " "
                            + evidenceSearch(correction.source() == null ? List.of()
                                    : correction.source().evidence())
                    : source instanceof IdentityLink link
                    ? link.sourceKind() + " " + link.sourceId() + " "
                            + link.canonicalName() + " " + link.recordUrl() + " "
                            + evidenceSearch(link.evidence())
                    : source instanceof FieldDeclaration field
                    ? field.name() + " " + field.typeLabel() + " " + field.targetType()
                    : "";
            return (typeLabel() + " " + entity.id() + " " + owner.entityLabel(this)
                    + " " + kind.label + " " + title + " " + summary + " "
                    + origin + " " + extra).toLowerCase(Locale.ROOT);
        }
    }

    private static String evidenceSearch(
            List<datasource.evidence.ExtractedClaim> claims) {
        if (claims == null) return "";
        return claims.stream().flatMap(claim -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(claim.semanticProperty(), claim.claimId(),
                                claim.assessmentId(), String.valueOf(claim.proposedValue()),
                                claim.extractionMethod(), claim.recipeVersion()),
                        claim.evidence().stream().flatMap(fragment -> java.util.stream.Stream.of(
                                fragment.excerpt(), fragment.section(),
                                fragment.document().sourceId(), fragment.document().url()))))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private final class OperationTableModel extends AbstractTableModel {
        private final String[] columns =
                {"Directive", "Class", "Entity", "QID", "Summary", "Origin"};
        private List<Operation> operations = List.of();

        void setOperations(List<Operation> value) {
            operations = value == null ? List.of() : List.copyOf(value);
            fireTableDataChanged();
        }

        Operation operationAt(int row) {
            return row < 0 || row >= operations.size() ? null : operations.get(row);
        }

        @Override public int getRowCount() { return operations.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            Operation operation = operations.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> operation.kind().label;
                case 1 -> operation.typeLabel();
                case 2 -> entityLabel(operation);
                case 3 -> entityQid(operation);
                case 4 -> operation.summary();
                case 5 -> blankAsDash(operation.origin());
                default -> "";
            };
        }
    }
}
