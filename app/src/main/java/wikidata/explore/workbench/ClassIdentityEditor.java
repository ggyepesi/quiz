package wikidata.explore.workbench;

import canonical.CanonicalizationPlan;
import canonical.KeyComponent;
import canonical.Reduction;
import objectview.utils.swing.GridBagUtils;
import wikidata.explore.compiled.CanonicalizationPlans;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.DefaultListModel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What identifies an instance, and what happens when two candidates share that.
 *
 * <p>One editor for every construct. There were three: a checkbox grid on a statement
 * class, a space-separated text field on an entity class, and nothing at all on an
 * aggregate — for one question that every class answers.
 *
 * <p>The key is an ORDERED list, not a set of ticks. That is not presentation: identity
 * is built by joining a key's values in order, so reordering a key changes every
 * instance's identifier. The checkbox grid rebuilt the key in FIELD order on every apply,
 * which silently rewrote any key authored differently — invisible only because all three
 * shipped models happen to have been authored in field order.
 */
final class ClassIdentityEditor extends JPanel {

    private final DefaultListModel<String> keyModel = new DefaultListModel<>();
    private final JList<String> keyList = new JList<>(keyModel);
    private final JComboBox<String> addable = new JComboBox<>();
    private final JPanel reductions = new JPanel(new GridBagLayout());
    private final Map<String, JComboBox<Reduction>> reducerBoxes = new LinkedHashMap<>();
    private final JLabel proposal = new JLabel(" ");
    private final JButton accept = new JButton(" ");
    private final JButton addKey = button("Add", event -> addSelected());
    private final JButton removeKey = button("Remove", event -> removeSelected());
    private final JButton moveKeyUp = button("Up", event -> move(-1));
    private final JButton moveKeyDown = button("Down", event -> move(1));

    private final JTextArea preview = new JTextArea(5, 40);
    private List<canonical.Candidate> sampled = List.of();
    private GeneratedClassModel clazz;
    private Consumer<Void> afterChange = ignored -> { };

    ClassIdentityEditor() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createTitledBorder("Identity"));

        JPanel key = new JPanel(new BorderLayout(4, 4));
        keyList.setVisibleRowCount(4);
        key.add(new JScrollPane(keyList), BorderLayout.CENTER);
        key.add(keyButtons(), BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(new JLabel("<html><i>What tells two instances apart. The order is part of "
                + "the identity, because an identifier joins these values in it.</i></html>"),
                BorderLayout.NORTH);
        top.add(key, BorderLayout.CENTER);

        accept.addActionListener(event -> acceptProposal());
        JPanel offered = new JPanel(new BorderLayout());
        offered.add(proposal, BorderLayout.CENTER);
        offered.add(accept, BorderLayout.EAST);
        top.add(offered, BorderLayout.SOUTH);

        reductions.setBorder(BorderFactory.createTitledBorder("When the same key occurs"));

        preview.setEditable(false);
        preview.setOpaque(false);
        preview.setBorder(BorderFactory.createTitledBorder("What this would do"));

        JPanel below = new JPanel(new BorderLayout(4, 4));
        below.add(reductions, BorderLayout.NORTH);
        below.add(new JScrollPane(preview), BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(below, BorderLayout.CENTER);
    }

    private JPanel keyButtons() {
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.add(addKey);
        buttons.add(removeKey);
        buttons.add(moveKeyUp);
        buttons.add(moveKeyDown);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(addable);
        return buttons;
    }

    private static JButton button(String text, java.awt.event.ActionListener action) {
        JButton button = new JButton(text);
        button.addActionListener(action);
        return button;
    }

    void afterChange(Consumer<Void> consumer) {
        afterChange = consumer == null ? ignored -> { } : consumer;
    }

    /**
     * Instances to try the configuration against, so a change can be SEEN before it is
     * applied.
     *
     * <p>These are already-reduced instances, which is the point rather than a
     * limitation: each is its own partition under the key that produced it, so the
     * preview reads "nothing would change" until the key is edited — and then it says
     * exactly what a coarser one would merge. That is the question a modeller has when
     * they touch a key, and the only place it could otherwise be answered is a
     * regenerated snapshot.
     */
    void previewAgainst(List<canonical.Candidate> candidates) {
        sampled = candidates == null ? List.of() : List.copyOf(candidates);
        showPreview();
    }

    private void showPreview() {
        if (clazz == null || sampled.isEmpty()) {
            preview.setText("Sample this class to see what the configuration would do "
                    + "to real instances.");
            return;
        }
        CanonicalizationPlan plan = CanonicalizationPlans.of(clazz);
        if (!plan.identified()) {
            preview.setText("Nothing identifies this class yet, so there is nothing to "
                    + "try: every instance would be the same one.");
            return;
        }
        try {
            var result = canonical.CanonicalizationEngine.canonicalize(
                    plan, sampled, wikidata.explore.transform.WikidataCandidates.stableForm());
            StringBuilder text = new StringBuilder(result.report());
            if (result.reducedPartitions() == 0 && result.conflicts().isEmpty()) {
                text.append("Nothing is combined: this key tells all ")
                        .append(sampled.size()).append(" of them apart.\n");
            }
            preview.setText(text.toString());
            preview.setCaretPosition(0);
        } catch (RuntimeException refused) {
            preview.setText(refused.getMessage() == null
                    ? "This configuration cannot be applied." : refused.getMessage());
        }
    }

    void show(GeneratedClassModel value) {
        clazz = value;
        keyModel.clear();
        reducerBoxes.clear();
        reductions.removeAll();
        addable.removeAllItems();
        if (clazz == null) {
            revalidate();
            repaint();
            return;
        }

        CanonicalizationPlan plan = CanonicalizationPlans.of(clazz);
        for (KeyComponent component : plan.key()) keyModel.addElement(component.toString());
        // Owner/site is mandatory. Source identity is a default and can be replaced by
        // a modeled field key; Add performs that explicit replacement rather than
        // silently mixing a structural component into CanonicalSpec's field list.
        boolean sourceDefault = clazz.classKind()
                == wikidata.explore.model.ClassKind.SOURCE
                && clazz.canonical().keyFields().isEmpty();
        boolean editable = sourceDefault
                || plan.key().stream().noneMatch(KeyComponent::structural);
        keyList.setEnabled(editable);
        addable.setEnabled(editable);
        addKey.setEnabled(editable);
        removeKey.setEnabled(editable && !sourceDefault);
        moveKeyUp.setEnabled(editable && !sourceDefault);
        moveKeyDown.setEnabled(editable && !sourceDefault);

        for (GeneratedFieldModel field : clazz.fields()) {
            if (field == null || field.name() == null || field.name().isBlank()) continue;
            if (!keyModel.contains(field.name())) addable.addItem(field.name());
        }

        showReductions(plan);
        showProposal();
        showPreview();
        revalidate();
        repaint();
    }

    /**
     * One row per field that is not part of the key, with the cardinality default
     * preselected — so an ordinary class asks for no decisions here at all.
     */
    private void showReductions(CanonicalizationPlan plan) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        int row = 0;
        Map<String, Reduction> defaulted = CanonicalizationPlans.defaultedFields(clazz);
        for (var entry : plan.reductionByField().entrySet()) {
            String field = entry.getKey();
            GeneratedFieldModel model = field(field);
            JComboBox<Reduction> box = new JComboBox<>(valid(model));
            box.setSelectedItem(entry.getValue());
            box.setToolTipText(defaulted.containsKey(field)
                    ? "Nobody chose this; it follows from the field's cardinality."
                    : "Chosen for this field.");
            box.addActionListener(event -> {
                clazz.canonical().reductions().put(field, (Reduction) box.getSelectedItem());
                // The preview follows the selection, and only the preview: applying is
                // still the explicit act it was. Inspecting a consequence must not be
                // how a configuration gets made.
                showPreview();
                afterChange.accept(null);
            });
            reducerBoxes.put(field, box);
            GridBagUtils.labeledRow(reductions, c, row++, field + ":", box);
        }
        if (row == 0) {
            GridBagUtils.wideRow(reductions, 0, new JLabel(
                    "<html><i>Every field is part of the key, so nothing is combined.</i></html>"));
        }
    }

    /**
     * Only what the field can actually hold. A union on a single-valued field would
     * produce a list the field cannot store — invalid, not merely unadvisable.
     */
    private static Reduction[] valid(GeneratedFieldModel field) {
        boolean many = field != null && field.cardinality() == FieldCardinality.COLLECTION;
        return many
                ? new Reduction[] {Reduction.UNION_DISTINCT, Reduction.REQUIRE_AGREEMENT}
                : new Reduction[] {Reduction.REQUIRE_AGREEMENT, Reduction.PREFER_NON_EMPTY};
    }

    private GeneratedFieldModel field(String name) {
        return clazz.fields().stream()
                .filter(candidate -> candidate != null && name.equals(candidate.name()))
                .findFirst().orElse(null);
    }

    private void showProposal() {
        List<String> proposed = wikidata.explore.model.StatementIdentity.proposedKey(clazz);
        accept.setVisible(!proposed.isEmpty());
        proposal.setVisible(!proposed.isEmpty());
        if (!proposed.isEmpty()) {
            proposal.setText("<html><i>Nothing identifies this yet.</i></html>");
            accept.setText("Use " + String.join(" + ", proposed));
        }
    }

    private void acceptProposal() {
        List<String> proposed = wikidata.explore.model.StatementIdentity.proposedKey(clazz);
        if (proposed.isEmpty()) return;
        clazz.canonical().keyFields().addAll(proposed);
        show(clazz);
        afterChange.accept(null);
    }

    private void addSelected() {
        Object chosen = addable.getSelectedItem();
        if (clazz == null || chosen == null) return;
        if (clazz.classKind() == wikidata.explore.model.ClassKind.SOURCE
                && clazz.canonical().keyFields().isEmpty()) {
            keyModel.clear(); // replace the displayed source-identity default
        }
        keyModel.addElement(chosen.toString());
        writeKey();
    }

    private void removeSelected() {
        int index = keyList.getSelectedIndex();
        if (index < 0) return;
        keyModel.remove(index);
        writeKey();
    }

    private void move(int by) {
        int index = keyList.getSelectedIndex();
        int target = index + by;
        if (index < 0 || target < 0 || target >= keyModel.size()) return;
        String moved = keyModel.remove(index);
        keyModel.add(target, moved);
        keyList.setSelectedIndex(target);
        writeKey();
    }

    /** The list IS the key, in the order shown. */
    private void writeKey() {
        if (clazz == null) return;
        List<String> key = new ArrayList<>();
        for (int i = 0; i < keyModel.size(); i++) key.add(keyModel.get(i));
        clazz.canonical().keyFields().clear();
        clazz.canonical().keyFields().addAll(key);
        show(clazz);
        afterChange.accept(null);
    }
}
