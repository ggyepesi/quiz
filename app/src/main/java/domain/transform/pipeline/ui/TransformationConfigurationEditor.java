package domain.transform.pipeline.ui;

import domain.DomainField;
import domain.DomainModel;
import domain.config.ClassConfigurationProjection;
import domain.config.ConfigurationNode;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.TransformationClassSource;
import domain.config.TransformationStep;
import domain.transform.FilterCondition;
import objectview.field.FieldPath;
import objectview.utils.swing.WrapLayout;
import process.swing.Dialogs;

import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;

/** Detail for the transformation branch of the shared class configuration tree. */
public final class TransformationConfigurationEditor extends JPanel {
    /** The local field catalogue and value choices of the host's loaded instance set. */
    public record Fields(DomainModel domain,
            BiFunction<String, FieldPath, DomainModel.ValueSelection> values,
            BiFunction<String, FieldPath, Object> sample) { }

    private final JLabel input = new JLabel();
    private final JLabel detail = new JLabel();
    private final JLabel status = new JLabel();
    private final JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JButton add = new JButton("Add filter…");
    private final JButton edit = new JButton("Edit filter…");
    private final JButton remove = new JButton("Remove filter");
    private final JButton up = new JButton("Move up");
    private final JButton down = new JButton("Move down");
    private final JButton apply = new JButton("Apply transformation");
    private final JButton discard = new JButton("Discard changes");
    private final Map<GeneratedClassModel, TransformationClassSource> drafts =
            new IdentityHashMap<>();
    private final Set<GeneratedClassModel> appliedOutputs =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Runnable refreshTree;
    private final IntConsumer selectStep;
    private final Consumer<GeneratedClassModel> applied;
    private Fields fields;
    private GeneratedProjectModel project;
    private GeneratedClassModel output;
    private ConfigurationNode node;

    public TransformationConfigurationEditor(Runnable refreshTree, IntConsumer selectStep,
            Consumer<GeneratedClassModel> applied) {
        this.refreshTree = refreshTree;
        this.selectStep = selectStep;
        this.applied = applied;
        status.setName("transformation.status");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        for (var row : java.util.List.of(input, detail, actions, status)) {
            row.setAlignmentX(LEFT_ALIGNMENT);
            add(row);
        }
        for (JButton button : java.util.List.of(add, edit, remove, up, down, apply, discard)) {
            actions.add(button);
        }
        add.addActionListener(event -> editFilter(-1));
        edit.addActionListener(event -> editFilter(index()));
        remove.addActionListener(event -> changeSteps(steps -> steps.remove(index())));
        up.addActionListener(event -> move(-1));
        down.addActionListener(event -> move(1));
        apply.addActionListener(event -> apply());
        discard.addActionListener(event -> {
            drafts.remove(output);
            refreshTree.run();
        });
    }

    public void fields(Fields fields) { this.fields = fields; }

    public void showClass(GeneratedProjectModel project, GeneratedClassModel output) {
        this.project = project;
        this.output = output;
    }

    public TransformationClassSource transformation() {
        return output == null ? null : drafts.getOrDefault(output, output.transformationSource());
    }

    public boolean hasDrafts() { return !drafts.isEmpty(); }

    public void selection(ConfigurationNode node) {
        this.node = node;
        refresh();
    }

    public void refresh() {
        var source = transformation();
        int index = index();
        int size = source == null ? 0 : source.steps().size();
        boolean editing = canEdit();
        boolean step = index >= 0 && index < size;
        boolean pending = output != null && drafts.containsKey(output);
        input.setText(source == null ? "No transformation configured"
                : "Input class: " + source.inputClassName());
        detail.setText(step && node != null ? node.caption() : size + " filter step(s)");
        actions.setVisible(editing);
        add.setEnabled(editing);
        edit.setEnabled(editing && step);
        remove.setEnabled(editing && step);
        up.setEnabled(editing && step && index > 0);
        down.setEnabled(editing && step && index + 1 < size);
        apply.setEnabled(editing && pending && source.configured());
        discard.setEnabled(editing && pending);
        status.setText(!editing ? "Edit the population transformation in TransformApp."
                : pending && !source.configured() ? "A transformation needs at least one filter."
                : pending ? "Apply the filter changes to the working model."
                : appliedOutputs.contains(output)
                        ? "<html>Applied to the working model. Save, then Build project in ModelBuilder "
                                + "to run this recipe. The loaded instances still show the previous result.</html>"
                        : "Select a filter to edit it, or add a filter to the transformation.");
    }

    private int index() { return ClassConfigurationProjection.transformationStepIndex(node); }

    private boolean canEdit() {
        return fields != null && output != null && !output.isImported()
                && project.classes().contains(output) && transformation() != null;
    }

    private void editFilter(int index) {
        if (!canEdit()) return;
        var source = transformation();
        var existing = index < 0 ? null : source.steps().get(index);
        var inputClass = project.resolveClass(source.inputClassId(), source.inputClassName());
        if (inputClass == null) return;
        var available = new ArrayList<>(fields.domain().fields(inputClass.className()));
        DomainField previous = existing == null ? null : available.stream()
                .filter(field -> field.field().equals(existing.fieldPath()))
                .findFirst().orElseGet(() -> new DomainField(
                        inputClass.className(), existing.fieldPath(), false, false));
        if (previous != null && !available.contains(previous)) available.add(previous);
        JComboBox<DomainField> chosen = new JComboBox<>(available.toArray(DomainField[]::new));
        chosen.setRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int row, boolean selected,
                    boolean focus) {
                return super.getListCellRendererComponent(list,
                        value instanceof DomainField field ? field.displayPath() : value,
                        row, selected, focus);
            }
        });
        chosen.setSelectedItem(previous);
        var editor = new FilterConditionEditor(fields.values(), fields.sample());
        Runnable selectField = () -> {
            var field = (DomainField) chosen.getSelectedItem();
            editor.edit(field, field != null && field.equals(previous)
                    ? new FilterCondition(field, existing.operator(),
                            existing.value(), existing.value2()) : null);
        };
        selectField.run();
        chosen.addActionListener(event -> selectField.run());
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JPanel fieldRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fieldRow.add(new JLabel("Field:"));
        fieldRow.add(chosen);
        form.add(fieldRow);
        form.add(editor);
        if (Dialogs.confirmResizable(this, form, existing == null ? "Add filter" : "Edit filter",
                new Dimension(640, 220), new Dimension(480, 180)) != JOptionPane.OK_OPTION) return;
        FilterCondition condition = editor.condition();
        if (condition == null) {
            Dialogs.showMessage(this, "Select a field for the filter.",
                    "Filter unchanged", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        var step = TransformationStep.filter(condition.field().field(), condition.operator(),
                condition.value(), condition.value2());
        changeSteps(steps -> {
            if (index < 0) steps.add(step);
            else steps.set(index, step);
        });
    }

    private void move(int offset) {
        if (!canEdit()) return;
        int current = index();
        int target = current + offset;
        changeSteps(steps -> Collections.swap(steps, current, target));
        selectStep.accept(target);
    }

    private void changeSteps(Consumer<ArrayList<TransformationStep>> change) {
        if (!canEdit()) return;
        var draft = drafts.computeIfAbsent(output, key -> key.transformationSource().copy());
        var steps = new ArrayList<>(draft.steps());
        change.accept(steps);
        draft.steps(steps);
        refreshTree.run();
    }

    private void apply() {
        if (!canEdit() || !drafts.containsKey(output) || !transformation().configured()) return;
        output.transformationSource(drafts.remove(output));
        appliedOutputs.add(output);
        applied.accept(output);
        refreshTree.run();
    }
}
