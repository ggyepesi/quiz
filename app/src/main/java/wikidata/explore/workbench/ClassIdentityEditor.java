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
 * <p>What happens when a key cannot be COMPUTED is asked here too, beside the key it is
 * about. It existed on every class's canonical spec and nothing edited it; the only
 * control for it was on the aggregate editor, over a second enum of its own with a
 * different default — so one class answered the question twice, and Nobel's NobelPrize
 * answered it two different ways at once.
 *
 * <p>The key is an ORDERED list, not a set of ticks. That is not presentation: identity
 * is built by joining a key's values in order, so reordering a key changes every
 * instance's identifier. The checkbox grid rebuilt the key in FIELD order on every apply,
 * which silently rewrote any key authored differently — invisible only because all three
 * shipped models happen to have been authored in field order.
 */
final class ClassIdentityEditor extends JPanel {

    // The shared control: a list of chosen things plus a chooser of what could be
    // added, with clicking a row an inspection rather than an edit. Ordered, because an
    // identifier joins a key's values IN order.
    private final OrderedChoiceList<String> key = new OrderedChoiceList<>(true);
    private final JPanel reductions = new JPanel(new GridBagLayout());
    private final JComboBox<canonical.MissingKeyPolicy> missingKey =
            new JComboBox<>(canonical.MissingKeyPolicy.values());
    private final Map<String, JComboBox<Reduction>> reducerBoxes = new LinkedHashMap<>();
    private final JLabel proposal = new JLabel(" ");
    private final JButton accept = new JButton(" ");
    private GeneratedClassModel clazz;
    private boolean showing;
    private Consumer<Void> afterChange = ignored -> { };

    ClassIdentityEditor() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createTitledBorder("Identity"));

        key.onChange(this::writeKey);

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

        missingKey.setToolTipText("<html>What becomes of a candidate whose key cannot be "
                + "computed — a different question from two candidates sharing one, and "
                + "the difference is scope: this decides whether a candidate takes part "
                + "AT ALL.<br><b>INCOMPLETE_GROUP</b>: group them together and say so."
                + "<br><b>REJECT_CANDIDATE</b>: leave them out, and count what was left "
                + "out.<br><b>FAIL</b>: for this class, a candidate without a key means "
                + "the run is wrong.</html>");
        missingKey.addActionListener(event -> {
            if (showing || clazz == null) return;
            clazz.canonical().missingKeyPolicy(
                    (canonical.MissingKeyPolicy) missingKey.getSelectedItem());
            afterChange.accept(null);
        });
        JPanel missing = new JPanel(new GridBagLayout());
        GridBagConstraints missingRow = new GridBagConstraints();
        missingRow.insets = new Insets(2, 4, 2, 4);
        missingRow.anchor = GridBagConstraints.WEST;
        missingRow.fill = GridBagConstraints.HORIZONTAL;
        GridBagUtils.labeledRow(
                missing, missingRow, 0, "When a key cannot be computed:", missingKey);

        JPanel keyRules = new JPanel(new BorderLayout());
        keyRules.add(missing, BorderLayout.NORTH);
        keyRules.add(reductions, BorderLayout.CENTER);

        // No "What this would do" box. It ran the real canonicalization against
        // sampled instances, which is a genuine question — what a coarser key would
        // merge — but only two of the four kinds ever fed it, so on the others it could
        // show nothing but an invitation to sample. Sampling is the Sample tab's, and
        // the report belongs where the instances are rather than inside a configuration
        // piece. CanonicalizationEngineAnswersAKeyChangeTest keeps the answer covered.
        add(top, BorderLayout.NORTH);
        add(keyRules, BorderLayout.CENTER);
    }



    void afterChange(Consumer<Void> consumer) {
        afterChange = consumer == null ? ignored -> { } : consumer;
    }


    void show(GeneratedClassModel value) {
        clazz = value;
        reducerBoxes.clear();
        reductions.removeAll();
        showing = true;
        try {
            missingKey.setEnabled(clazz != null);
            missingKey.setSelectedItem(clazz == null
                    ? canonical.MissingKeyPolicy.defaultPolicy()
                    : clazz.canonical().missingKeyPolicy());
        } finally {
            showing = false;
        }
        if (clazz == null) {
            revalidate();
            repaint();
            return;
        }

        CanonicalizationPlan plan = CanonicalizationPlans.of(clazz);
        List<String> chosen = plan.key().stream().map(Object::toString).toList();
        List<String> addable = new java.util.ArrayList<>();
        for (GeneratedFieldModel field : clazz.fields()) {
            if (field == null || field.name() == null || field.name().isBlank()) continue;
            addable.add(field.name());
        }
        key.show(chosen, addable);

        // Owner/site is mandatory. Source identity is a DEFAULT that adding replaces —
        // removing it would leave the class with no identity at all, which is not what
        // a reader means by removing a default.
        boolean sourceDefault = clazz.classKind()
                == wikidata.explore.model.ClassKind.SOURCE
                && clazz.canonical().keyFields().isEmpty();
        key.mode(sourceDefault ? OrderedChoiceList.Mode.REPLACED_BY_ADDING
                : plan.key().stream().anyMatch(KeyComponent::structural)
                        ? OrderedChoiceList.Mode.FIXED
                        : OrderedChoiceList.Mode.EDITABLE);

        showReductions(plan);
        showProposal();
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




    /** The list IS the key, in the order shown. */
    /** The list IS the key, in the order shown. */
    private void writeKey() {
        if (clazz == null) return;
        clazz.canonical().keyFields().clear();
        clazz.canonical().keyFields().addAll(key.chosen());
        show(clazz);
        afterChange.accept(null);
    }
}
