package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.EntityBound;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One end of a statement triple — the subject or the object — configured the same way.
 *
 * <p>The caller supplies the word. Nothing else here knows which end it is, and that is
 * the point: the two ends ask the same question, so the two controls were the same
 * control written twice, drifting apart in wording and in what each could express. The
 * object could be bounded by a vocabulary and the subject could not; the subject could be
 * bounded by explicit QIDs and the object could not.
 *
 * <p>What differs between the ends is the CONSEQUENCE, not the configuration: bounding
 * the object restricts which statements are collected, bounding the subject restricts
 * whose statements are. The hint says so, in the caller's words.
 */
final class EntityEndEditor extends JPanel {

    private static final String ANY = "Anything";

    /**
     * What a value at this end IS, once acquired — its datatype and its class.
     *
     * <p>Read from the field that receives the end, which is where both are authored.
     * Giving the end its own copy would be a second place to say one thing, and the two
     * could then disagree about whether an object is a date.
     */
    private final JLabel modelledAs = new JLabel(" ");
    private static final String THESE_ENTITIES = "These QIDs";
    private static final String A_VOCABULARY = "A vocabulary";
    // Not "Instances of": the bound carries ANY property, so P279 (subclass of) is as
    // expressible as P31, and the old wording named one of them as though it were the
    // construct. It also took the FIRST typed QID and dropped P279 closure — three
    // things the model could say and the editor could not, silently reduced on every
    // apply because bound() is read whether or not anything was touched.
    private static final String PROPERTY_INTO = "Property + QIDs";

    private final String end;
    private final JLabel destination = new JLabel(" ");
    private final JComboBox<String> mode = new JComboBox<>(
            new String[] {ANY, THESE_ENTITIES, A_VOCABULARY, PROPERTY_INTO});
    private final JTextField qids = new JTextField(16);
    private final JTextField relationPid = new JTextField(6);
    private final JCheckBox includeDescendants =
            new JCheckBox("and their subclasses (P279)");
    private final JComboBox<String> vocabulary = new JComboBox<>();
    private final JPanel value = new JPanel(new CardLayout());

    /**
     * @param end the word for this end — "Subject" or "Object"
     * @param consequence what bounding this end restricts, in one clause
     */
    EntityEndEditor(String end, String consequence) {
        super(new GridBagLayout());
        this.end = end;
        setBorder(BorderFactory.createTitledBorder(end));

        value.add(new JPanel(), "none");
        JPanel qidRow = new JPanel(new GridBagLayout());
        GridBagConstraints qc = new GridBagConstraints();
        qc.insets = new Insets(0, 0, 0, 4);
        relationPid.setToolTipText("The property that must point into those QIDs — P31 "
                + "for instances, P279 for subclasses, or any other.");
        qidRow.add(relationPid, qc);
        qc.gridx = 1;
        qc.weightx = 1;
        qc.fill = GridBagConstraints.HORIZONTAL;
        qidRow.add(qids, qc);
        qc.gridx = 2;
        qc.weightx = 0;
        qc.fill = GridBagConstraints.NONE;
        includeDescendants.setToolTipText(
                "Follow P279 down from those QIDs as well, so a subclass counts.");
        qidRow.add(includeDescendants, qc);
        value.add(qidRow, "qids");
        value.add(vocabulary, "vocabulary");

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        // Three questions about one end, and they are not the same question. Having no
        // QIDs for an end is not the same as having no configuration for it: Nobel's
        // subject is bounded by nothing and modelled as Laureate, which is a configured
        // end with an unrestricted population. Tying the class to the field row said
        // those two together and left the bound row looking like the whole answer.
        modelledAs.setToolTipText(
                "What an entity at this end IS once acquired — the class it is modelled "
                        + "as. Independent of how many entities may occupy the end.");
        GridBagUtils.labeledRow(this, c, 0, "Modelled as:", modelledAs);
        destination.setToolTipText(
                "Structure: which field of each record receives this end.");
        GridBagUtils.labeledRow(this, c, 1, "Goes into field:", destination);
        JPanel bound = modeRow();
        bound.setToolTipText(
                "Population: which entities may occupy this end BEFORE acquisition. "
                        + "\"Anything\" restricts nothing; it does not mean unconfigured.");
        GridBagUtils.labeledRow(this, c, 2, "Entities allowed:", bound);

        JLabel hint = new JLabel("<html><i>" + consequence + "</i></html>");
        GridBagConstraints hintCell = (GridBagConstraints) c.clone();
        hintCell.gridx = 0;
        hintCell.gridy = 3;
        hintCell.gridwidth = 2;
        hintCell.anchor = GridBagConstraints.WEST;
        add(hint, hintCell);

        mode.addActionListener(event -> showValueForMode());
        includeDescendants.setOpaque(false);
        showValueForMode();
    }

    private JPanel modeRow() {
        JPanel row = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 6);
        row.add(mode, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        row.add(value, c);
        return row;
    }

    /** Offers the vocabularies this project has, keeping any current choice. */
    void vocabularies(Supplier<List<String>> names) {
        String selected = selected(vocabulary);
        vocabulary.removeAllItems();
        for (String name : names.get()) vocabulary.addItem(name);
        if (!selected.isBlank()) select(vocabulary, selected);
    }

    /**
     * Selects a name, adding it if the list does not offer it.
     *
     * <p>A bound may name a Selection this project cannot currently list — a model
     * mid-edit, or one whose vocabulary lives elsewhere. Silently leaving the box on
     * something else would then DELETE that reference on the next apply, because what
     * the control shows is what gets written.
     */
    private static void select(JComboBox<String> box, String name) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (name.equals(box.getItemAt(i))) {
                box.setSelectedIndex(i);
                return;
            }
        }
        box.addItem(name);
        box.setSelectedItem(name);
    }

    /**
     * Says which field receives this end and how, or that nothing does.
     *
     * <p>{@code howItIsFilled} is the route, not a guess at one. The subject has three
     * authored routes and this used to be told about one, so a class settling its
     * subject through a participants collection was reported as unconfigured and unable
     * to generate — of a domain that generates.
     */
    void destination(String fieldName, String targetClass, String valueKind,
            String howItIsFilled, boolean required) {
        String kind = valueKind == null || valueKind.isBlank() ? "" : valueKind;
        modelledAs.setText(targetClass == null || targetClass.isBlank()
                ? "<html>" + (kind.isEmpty() ? "" : "<b>" + kind + "</b> — ")
                        + "<i>no class named</i>, served as a bare reference</html>"
                : "<html><b>" + targetClass + "</b>"
                        + (kind.isEmpty() ? "" : " <i>(" + kind + ")</i>") + "</html>");
        if (fieldName == null || fieldName.isBlank()) {
            // Not projected is a legitimate END STATE in a model, which states shape and
            // never acquires: the class is a placeholder yielding a reference, and
            // specialization giving it fields is optional. Only a project that acquires
            // has to settle it — the same condition the validator gates on, so the two
            // cannot tell a reader different things about one model.
            destination.setText(required
                    ? "<html><i>Not projected</i> — " + howItIsFilled
                            + ". Required before this domain can generate.</html>"
                    : "<html><i>Not projected</i> — served as a reference (identity and "
                            + "label). Optional in a model.</html>");
            return;
        }
        destination.setText("<html><b>" + fieldName + "</b> — <i>" + howItIsFilled
                + "</i></html>");
    }

    void show(EntityBound bound) {
        EntityBound shown = bound == null ? EntityBound.unbounded() : bound;
        mode.setSelectedItem(switch (shown.kind()) {
            case EXPLICIT -> THESE_ENTITIES;
            case VOCABULARY -> A_VOCABULARY;
            case RELATION -> PROPERTY_INTO;
            case UNBOUNDED -> ANY;
        });
        qids.setText(String.join(" ", shown.qids()));
        relationPid.setText(shown.relationPid());
        includeDescendants.setSelected(shown.includeDescendants());
        if (shown.kind() == EntityBound.Kind.VOCABULARY) {
            select(vocabulary, shown.selectionName());
        }
        showValueForMode();
    }

    /** What the controls currently say, as one value — never two that could compete. */
    EntityBound bound() {
        String chosen = selected(mode);
        if (THESE_ENTITIES.equals(chosen)) return EntityBound.explicit(typedQids());
        if (A_VOCABULARY.equals(chosen)) {
            return EntityBound.vocabulary(selected(vocabulary));
        }
        if (PROPERTY_INTO.equals(chosen)) {
            List<String> typed = typedQids();
            String pid = relationPid.getText() == null ? "" : relationPid.getText().trim();
            // Every QID and the property the reader actually chose. This used to build
            // instancesOf(first) — P31, one target, no closure — so a bound saying
            // "subclasses of these three" came back as "instances of that one".
            return typed.isEmpty() || !pid.matches("(?i)P\\d+")
                    ? EntityBound.unbounded()
                    : EntityBound.relation(pid, typed, includeDescendants.isSelected());
        }
        return EntityBound.unbounded();
    }

    private List<String> typedQids() {
        List<String> out = new ArrayList<>();
        for (String part : qids.getText().split("[,\\s]+")) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    private void showValueForMode() {
        String chosen = selected(mode);
        String card = THESE_ENTITIES.equals(chosen) || PROPERTY_INTO.equals(chosen) ? "qids"
                : A_VOCABULARY.equals(chosen) ? "vocabulary" : "none";
        ((CardLayout) value.getLayout()).show(value, card);
        boolean viaProperty = PROPERTY_INTO.equals(chosen);
        relationPid.setVisible(viaProperty);
        includeDescendants.setVisible(viaProperty);
        qids.setToolTipText(viaProperty
                ? "The QIDs the property points INTO, separated by spaces or commas."
                : "QIDs, separated by spaces or commas.");
    }

    private static String selected(JComboBox<String> box) {
        Object value = box.getSelectedItem();
        return value == null ? "" : value.toString();
    }
}
