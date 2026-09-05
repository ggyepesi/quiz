package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import workbench.SimpleDocumentListener;
import wikidata.ui.WikidataLinks;
import wikidata.explore.model.EntityBound;
import wikidata.explore.rule.RuleNode;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

/**
 * The triple a class describes: subject · property · object.
 *
 * <p>Source, Statement and Owned classes all describe one. {@code RuleDirection} makes it
 * literal — it has a method called {@code triplePattern} and emits exactly the two
 * arrangements, so a source class's membership is not "a type filter" but a triple with a
 * direction saying which end its members occupy. What differs by kind is only which tags
 * are authored here and which are settled elsewhere.
 *
 * <p>Both ends ask the same question and are the same control; see {@link
 * EntityEndEditor}. The subject's POPULATION lives here too rather than beside the box:
 * naming the class whose members are the subjects is a way of bounding the subject end,
 * and it was the one leg of the triple with a control of its own outside the triple.
 *
 * <p>An OWNED class is shown its triples rather than asked them ({@link #producedAt}).
 * Its property and object are settled by which field, on which class, declares the
 * ownership — so they are authored there and only read here, and a class produced at
 * several sites occupies several triples.
 *
 * <p>A SOURCE class occupies one END of its triple ({@link #membership}): its members
 * are the subject, and it authors the property and the objects. Those were three
 * controls — "Relation property", "Wikidata type/class" and "Also include types" — over
 * what is one ordered list, and the second box was labelled by asking whether the
 * property was P31. One list, one row.
 */
final class TripleEditor extends JPanel {

    /** One place a class is produced: the owning class and the field that produces it. */
    record Site(String ownerClass, String fieldName) { }

    private final EntityEndEditor subject = new EntityEndEditor("Subject",
            "Bounding the subject restricts WHOSE statements are collected.");
    private final EntityEndEditor object = new EntityEndEditor("Object",
            "Bounding the object restricts WHICH statements are collected.");
    private final JTextField property = new JTextField(6);
    private final JComboBox<String> population = new JComboBox<>();

    private final JPanel authored = new JPanel(new GridBagLayout());
    private final JPanel produced = new JPanel();
    private final JPanel membership = new JPanel(new GridBagLayout());
    private final JTextField membershipProperty = new JTextField(6);
    private final JLabel membershipPropertyLabel = new JLabel(" ");
    private final JTextField membershipTargets = new JTextField(22);
    private final JLabel membershipTargetLabel = new JLabel("(not selected)");
    private final JPanel membershipPropertyActions =
            new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    private final JPanel membershipObjectActions =
            new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));

    TripleEditor(String title) {
        super(new CardLayout());
        setBorder(BorderFactory.createTitledBorder(title));
        produced.setLayout(new BoxLayout(produced, BoxLayout.Y_AXIS));
        add(authored, "authored");
        add(produced, "produced");
        add(membership, "membership");
        buildMembershipRows();

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        GridBagConstraints wide = (GridBagConstraints) c.clone();
        wide.gridx = 0;
        wide.gridwidth = 2;
        wide.fill = GridBagConstraints.HORIZONTAL;
        wide.weightx = 1;

        wide.gridy = 0;
        authored.add(subject, wide);
        population.setToolTipText(
                "Optional: the already-extracted class whose statements are read, "
                        + "outgoing from its members. Leave blank to discover subjects "
                        + "incoming from the property instead — which then requires the "
                        + "objects to be bounded, since they become the starting set.");
        GridBagUtils.labeledRow(authored, c, 1, "Subject population:", population);
        property.setToolTipText("The property this triple is about.");
        GridBagUtils.labeledRow(authored, c, 2, "Property:", property);
        GridBagConstraints objectCell = (GridBagConstraints) wide.clone();
        objectCell.gridy = 3;
        authored.add(object, objectCell);
    }

    /**
     * The triples this class's members occupy, none of them authored here.
     *
     * <p>Named the same way as the authored ones, because they are the same construct:
     * the members are one end, the production site is the property, and the owner is the
     * other end. Where each is authored is said, because that is the only place it can
     * be changed.
     */
    void producedAt(List<Site> sites) {
        produced.removeAll();
        List<Site> shown = sites == null ? List.of() : sites;
        if (shown.isEmpty()) {
            produced.add(new JLabel("<html><i>Produced nowhere yet. Add an ENTITY field "
                    + "to the owning class and select this class as its target.</i></html>"));
        }
        for (Site site : shown) {
            JPanel one = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(2, 4, 2, 4);
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            GridBagUtils.labeledRow(one, c, 0, "Subject:",
                    new JLabel("<html><i>this class's members</i></html>"));
            GridBagUtils.labeledRow(one, c, 1, "Property:",
                    new JLabel("<html><b>" + site.ownerClass() + "." + site.fieldName()
                            + "</b> — <i>authored on " + site.ownerClass()
                            + ", where the field is</i></html>"));
            GridBagUtils.labeledRow(one, c, 2, "Object:",
                    new JLabel("<html><b>" + site.ownerClass()
                            + "</b> — <i>the owner each part is a view of</i></html>"));
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.add(one, BorderLayout.CENTER);
            produced.add(wrapper);
        }
        ((CardLayout) getLayout()).show(this, "produced");
        revalidate();
        repaint();
    }

    private void buildMembershipRows() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        GridBagUtils.labeledRow(membership, c, 0, "Subject:",
                new JLabel("<html><i>this class's members</i></html>"));

        JPanel propertyRow = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 4, 0));
        membershipProperty.setToolTipText("<html>The property that puts an entity in "
                + "this class. <b>P31</b> = instance of; any property works, e.g. "
                + "<b>P166</b> = award received, <b>P39</b> = position held.</html>");
        propertyRow.add(membershipProperty);
        propertyRow.add(membershipPropertyActions);
        propertyRow.add(membershipPropertyLabel);
        GridBagUtils.labeledRow(membership, c, 1, "Property:", propertyRow);

        // One row, whatever the property is. The QIDs used to be two boxes — one
        // "type/class" and one "also include" — and the first was relabelled "Relation
        // target" when the property was not P31, which is the P31 literal answering a
        // question about wording. They are the objects this triple points at.
        JPanel objectRow = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 4, 0));
        membershipTargets.setToolTipText("<html>The entities this property must point "
                + "into, space-separated. An entity is a member when the property "
                + "reaches ANY of them.</html>");
        objectRow.add(membershipTargets);
        objectRow.add(membershipObjectActions);
        objectRow.add(membershipTargetLabel);
        GridBagUtils.labeledRow(membership, c, 2, "Objects:", objectRow);

        membershipPropertyActions.setOpaque(false);
        membershipObjectActions.setOpaque(false);
        WikidataLinks.linkify(membershipTargetLabel,
                () -> firstTarget().isBlank() ? "" : firstTarget());
        WikidataLinks.linkify(membershipPropertyLabel,
                () -> RuleNode.cleanPid(membershipProperty.getText()));
        // A hand-edited PID is no longer the property whose label is shown.
        membershipProperty.getDocument().addDocumentListener(
                SimpleDocumentListener.of(() -> membershipPropertyLabel.setText(" ")));
    }

    /** The buttons that fill these rows; their actions belong to the owning panel. */
    void membershipActions(List<javax.swing.JComponent> propertyActions,
            List<javax.swing.JComponent> objectActions) {
        membershipPropertyActions.removeAll();
        membershipObjectActions.removeAll();
        for (javax.swing.JComponent action : propertyActions) {
            membershipPropertyActions.add(action);
        }
        for (javax.swing.JComponent action : objectActions) {
            membershipObjectActions.add(action);
        }
    }

    /**
     * A source class's triple: its members are the subject, and it authors the property
     * and the objects.
     */
    void membership(String propertyPid, String propertyLabel, List<String> targets,
            String targetLabel) {
        membershipProperty.setText(propertyPid == null || propertyPid.isBlank()
                ? "P31" : propertyPid);
        membershipPropertyLabel.setText(
                propertyLabel == null || propertyLabel.isBlank() ? " " : propertyLabel);
        membershipTargets.setText(String.join(" ",
                targets == null ? List.<String>of() : targets));
        membershipTargetLabel.setText(targetLabel == null || targetLabel.isBlank()
                ? "(not selected)" : targetLabel);
        ((CardLayout) getLayout()).show(this, "membership");
    }

    /** The property, defaulted to P31 the way the membership rule reads a blank one. */
    String membershipProperty() {
        String pid = RuleNode.cleanPid(membershipProperty.getText());
        return pid.isBlank() ? "P31" : pid;
    }

    void membershipProperty(String pid, String label) {
        membershipProperty.setText(pid == null ? "" : pid);
        membershipPropertyLabel.setText(label == null || label.isBlank() ? " " : label);
    }

    String membershipPropertyLabel() {
        return membershipPropertyLabel.getText() == null
                ? "" : membershipPropertyLabel.getText().trim();
    }

    /** Every QID typed into the objects row, in order, without repeats. */
    List<String> membershipTargets() {
        java.util.List<String> qids = new java.util.ArrayList<>();
        for (String token : membershipTargets.getText().trim().split("[,;\\s]+")) {
            String qid = RuleNode.cleanQid(token);
            if (!qid.isBlank() && !qids.contains(qid)) qids.add(qid);
        }
        return qids;
    }

    void membershipTargets(List<String> qids, String targetLabel) {
        membershipTargets.setText(String.join(" ",
                qids == null ? List.<String>of() : qids));
        if (targetLabel != null) {
            membershipTargetLabel.setText(
                    targetLabel.isBlank() ? "(not selected)" : targetLabel);
        }
    }

    String firstTarget() {
        List<String> qids = membershipTargets();
        return qids.isEmpty() ? "" : qids.get(0);
    }

    void vocabularies(List<String> names) {
        List<String> offered = names == null ? List.of() : names;
        subject.vocabularies(() -> offered);
        object.vocabularies(() -> offered);
    }

    void subjectDestination(String fieldName, String targetClass, String valueKind,
            String howItIsFilled, boolean required) {
        subject.destination(fieldName, targetClass, valueKind, howItIsFilled, required);
    }

    void objectDestination(String fieldName, String targetClass, String valueKind,
            String howItIsFilled, boolean required) {
        object.destination(fieldName, targetClass, valueKind, howItIsFilled, required);
    }

    /**
     * The classes whose members could be this triple's subjects, and the one chosen.
     *
     * <p>A name the list does not offer is added rather than dropped: what the control
     * shows is what gets written, so silently landing on something else would delete the
     * reference on the next apply.
     */
    void subjectPopulation(List<String> classes, String selected) {
        population.removeAllItems();
        population.addItem("");
        for (String name : classes == null ? List.<String>of() : classes) {
            if (name != null && !name.isBlank()) population.addItem(name);
        }
        String chosen = selected == null ? "" : selected.trim();
        if (!chosen.isBlank()) {
            boolean offered = false;
            for (int i = 0; i < population.getItemCount(); i++) {
                if (chosen.equals(population.getItemAt(i))) offered = true;
            }
            if (!offered) population.addItem(chosen);
            population.setSelectedItem(chosen);
        }
    }

    void show(String propertyPid, EntityBound subjectBound, EntityBound objectBound) {
        property.setText(propertyPid == null ? "" : propertyPid);
        subject.show(subjectBound);
        object.show(objectBound);
    }

    void clear() {
        property.setText("");
        population.removeAllItems();
        subject.show(null);
        object.show(null);
    }

    String propertyPid() {
        return RuleNode.cleanPid(property.getText());
    }

    String subjectPopulation() {
        Object selected = population.getSelectedItem();
        return selected == null ? "" : selected.toString().trim();
    }

    EntityBound subjectBound() {
        return subject.bound();
    }

    EntityBound objectBound() {
        return object.bound();
    }
}
