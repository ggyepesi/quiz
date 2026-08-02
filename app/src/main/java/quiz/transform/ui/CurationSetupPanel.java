package quiz.transform.ui;

import objectview.Viewable;
import quiz.curation.CurationPlan;
import quiz.curation.CurationTask;
import quiz.curation.ScopeFilter;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Defines the two explicit parts of curation: the immutable instance scope and one
 * or more field tasks. Each field is processed independently after this choice. */
public final class CurationSetupPanel extends JPanel {
    private final DomainModel domain;
    private final List<Viewable> scope;
    private final JComboBox<String> type = new JComboBox<>();
    private final DefaultListModel<TaskChoice> choices = new DefaultListModel<>();
    private final JList<TaskChoice> fields = new JList<>(choices);
    private final JComboBox<ScopeFilter> state =
            new JComboBox<>(ScopeFilter.values());
    private final JLabel scopeLabel = new JLabel();

    public CurationSetupPanel(DomainModel domain,
                              Collection<? extends Viewable> scope,
                              String initialType) {
        super(new BorderLayout(8, 8));
        this.domain = domain;
        this.scope = scope == null ? List.of() : scope.stream()
                .filter(java.util.Objects::nonNull).map(Viewable.class::cast).toList();
        for (String candidate : domain.types()) {
            if (this.scope.stream().anyMatch(member -> domain.isInstanceOf(member, candidate))) {
                type.addItem(candidate);
            }
        }
        type.addActionListener(e -> refreshFields());
        fields.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fields.setVisibleRowCount(14);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Instance scope:"));
        top.add(scopeLabel);
        top.add(new JLabel("Class:"));
        top.add(type);
        top.add(new JLabel("Values:"));
        top.add(state);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(fields), BorderLayout.CENTER);
        add(new JLabel("Choose Identity and/or one or more fields. Each becomes a separate task."),
                BorderLayout.SOUTH);
        if (initialType != null) type.setSelectedItem(initialType);
        if (type.getSelectedItem() == null && type.getItemCount() > 0) type.setSelectedIndex(0);
        refreshFields();
    }

    private void refreshFields() {
        choices.clear();
        String selectedType = (String) type.getSelectedItem();
        long memberCount = selectedType == null ? 0 : scope.stream()
                .filter(member -> domain.isInstanceOf(member, selectedType)).count();
        scopeLabel.setText(scope.size() + " shown instance(s); " + memberCount
                + " eligible for " + selectedType);
        if (selectedType == null) return;
        choices.addElement(new TaskChoice(true, "source.qid", "Identity (source.qid)"));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (DomainField field : domain.fields(selectedType)) {
            if (field == null || field.field() == null
                    || "source.qid".equals(field.field())
                    || domain.structuralFields(selectedType).contains(field.field())
                    || !seen.add(field.field())) continue;
            choices.addElement(new TaskChoice(false, field.field(), field.toString()));
        }
    }

    public CurationPlan plan() {
        String selectedType = (String) type.getSelectedItem();
        ScopeFilter selectedState = (ScopeFilter) state.getSelectedItem();
        List<CurationTask> tasks = new ArrayList<>();
        for (TaskChoice choice : fields.getSelectedValuesList()) {
            tasks.add(choice.identity
                    ? CurationTask.identity(selectedType, selectedState)
                    : CurationTask.field(selectedType, choice.path, selectedState));
        }
        if (tasks.isEmpty()) return null;
        return new CurationPlan(selectedType + " curation", scope, tasks);
    }

    public void selectField(String path) {
        for (int i = 0; i < choices.size(); i++) {
            if (java.util.Objects.equals(path, choices.get(i).path)) {
                fields.setSelectedIndex(i);
                fields.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    private record TaskChoice(boolean identity, String path, String label) {
        @Override public String toString() { return label; }
    }
}
