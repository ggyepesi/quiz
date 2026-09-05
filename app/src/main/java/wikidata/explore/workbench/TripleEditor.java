package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import workbench.SimpleDocumentListener;
import wikidata.WikidataIds;
import wikidata.ui.WikidataLinks;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.model.Selection;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.StatementFieldSemantics;
import wikidata.explore.model.VocabularySelection;
import wikidata.explore.rule.RuleNode;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * The triple a class describes: subject · property · object. One component, one title,
 * one set of rows, for every kind that describes one.
 *
 * <p>Every kind is shown the same rows and asked with the same two calls, {@link #show}
 * and {@link #applyEdits}. What differs is only which elements the kind gets to ANSWER,
 * and the component works that out from the class itself rather than being told:
 *
 * <ul>
 *   <li>an element that is GIVEN is shown and not editable — a source class's members
 *       are its subject, and every element of an owned class's triple is authored on the
 *       field that produces it;</li>
 *   <li>where a kind may bound an end in fewer ways, the ways it may not use are not
 *       offered, so they are not editable either.</li>
 * </ul>
 *
 * <p>It was three implementations behind one name: a {@code CardLayout} over an authored
 * card, a membership card and a produced card, with the property field written twice and
 * the object end three times, two different border titles, and a separate public entry
 * point per kind so that every panel had to know which one it was. That is the same "one
 * thing, several spellings" the triple exists to remove, committed inside the component
 * meant to remove it.
 */
final class TripleEditor extends JPanel {

    private static final String TITLE = "Triple — subject · property · object";

    private final EntityEndEditor subject = new EntityEndEditor("Subject",
            "Bounding the subject restricts WHOSE statements are collected.");
    private final EntityEndEditor object = new EntityEndEditor("Object",
            "Bounding the object restricts WHICH statements are collected.");
    private final JTextField property = new JTextField(6);
    private final JLabel propertyLabel = new JLabel(" ");
    private final JComboBox<String> population = new JComboBox<>();
    private final JPanel propertyActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel objectActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    /** Says which elements are given, and by what. Blank when the kind authors them. */
    private final JLabel given = new JLabel(" ");

    TripleEditor() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder(TITLE));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        GridBagConstraints wide = (GridBagConstraints) c.clone();
        wide.gridx = 0;
        wide.gridwidth = 2;
        wide.fill = GridBagConstraints.HORIZONTAL;
        wide.weightx = 1;

        wide.gridy = 0;
        add(subject, wide);
        population.setToolTipText(
                "Optional: the already-extracted class whose statements are read, "
                        + "outgoing from its members. Leave blank to discover subjects "
                        + "incoming from the property instead — which then requires the "
                        + "objects to be bounded, since they become the starting set.");
        GridBagUtils.labeledRow(this, c, 1, "Subject population:", population);

        JPanel propertyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        property.setToolTipText("The property this triple is about.");
        propertyRow.add(property);
        propertyRow.add(propertyActions);
        propertyRow.add(propertyLabel);
        GridBagUtils.labeledRow(this, c, 2, "Property:", propertyRow);

        JPanel objectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        objectRow.add(object);
        objectRow.add(objectActions);
        GridBagConstraints objectCell = (GridBagConstraints) wide.clone();
        objectCell.gridy = 3;
        add(objectRow, objectCell);

        GridBagConstraints givenCell = (GridBagConstraints) wide.clone();
        givenCell.gridy = 4;
        add(given, givenCell);

        propertyActions.setOpaque(false);
        objectActions.setOpaque(false);
        WikidataLinks.linkify(propertyLabel, () -> RuleNode.cleanPid(property.getText()));
        // A hand-edited PID is no longer the property whose label is shown.
        property.getDocument().addDocumentListener(
                SimpleDocumentListener.of(() -> propertyLabel.setText(" ")));
    }

    /** The buttons that fill these rows; the dialogs behind them are the panel's. */
    void actions(List<JComponent> forProperty, List<JComponent> forObject) {
        propertyActions.removeAll();
        objectActions.removeAll();
        for (JComponent action : forProperty) propertyActions.add(action);
        for (JComponent action : forObject) objectActions.add(action);
    }

    /** This class's triple, with whatever it does not author shown and disabled. */
    void show(GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null) {
            clear();
            return;
        }
        List<String> vocabularies = vocabularies(project);
        subject.vocabularies(() -> vocabularies);
        object.vocabularies(() -> vocabularies);
        if (clazz.ownedClass()) {
            showProduced(clazz, project);
        } else if (clazz.reifiesStatements()) {
            showStatement(clazz, project);
        } else {
            showMembership(clazz);
        }
    }

    /** Writes back whatever this class's kind authors here, and nothing else. */
    void applyEdits(GeneratedClassModel clazz) {
        if (clazz == null || clazz.ownedClass()) return;
        if (clazz.reifiesStatements() || !subjectPopulation().isBlank()) {
            applyStatement(clazz);
        } else {
            applyMembership(clazz);
        }
    }

    // ---- Statement: every element authored ----

    private void showStatement(GeneratedClassModel clazz, GeneratedProjectModel project) {
        StatementClassSource source = clazz.statementSource();
        boolean projectionRequired = project != null && project.acquiresInstances();

        StatementFieldSemantics.SubjectDestination destination =
                StatementFieldSemantics.subjectDestination(clazz);
        subject.destination(destination.fieldName(),
                targetClassOf(clazz, destination.fieldName()),
                valueKindOf(clazz, destination.fieldName()),
                destination.route().phrase(), projectionRequired);
        subject.editable(true);
        subject.allowedModes(EntityEndEditor.allModes());
        subject.show(source == null ? null : source.subjectBound());

        population.setEnabled(true);
        subjectPopulation(candidates(clazz, project),
                source == null ? "" : source.sourceClassName());

        property.setEnabled(true);
        property.setText(source == null ? "" : source.propertyPid());
        propertyLabel.setText(source == null || source.propertyLabel().isBlank()
                ? " " : source.propertyLabel());

        String objectField = StatementFieldSemantics.statementValueFieldName(clazz);
        object.destination(objectField, targetClassOf(clazz, objectField),
                valueKindOf(clazz, objectField), "the value the statement points at",
                projectionRequired);
        object.editable(true);
        object.allowedModes(EntityEndEditor.allModes());
        object.show(source == null ? null : source.objectBound());

        given.setText(" ");
    }

    private void applyStatement(GeneratedClassModel clazz) {
        String sourceClass = subjectPopulation();
        String pid = RuleNode.cleanPid(property.getText());
        // A blank property AND no source class is not a statement class. A property
        // alone IS one: every shipped statement class discovers its subjects from the
        // property and names no source class.
        if (pid.isBlank() && sourceClass.isBlank()) {
            clazz.statementSource(null);
            return;
        }
        StatementClassSource prior = clazz.statementSource();
        // Copying carries the declarations this component does not edit, by
        // construction rather than by a list maintained here.
        StatementClassSource next = prior == null
                ? new StatementClassSource(sourceClass, pid) : prior.copy();
        next.sourceClassName(sourceClass);
        next.propertyPid(pid);
        next.subjectBound(subject.bound());
        next.objectBound(object.bound());
        clazz.statementSource(next);
    }

    // ---- Source: the members are given; the property and the objects are authored ----

    private void showMembership(GeneratedClassModel clazz) {
        EntityBound membership = clazz.membership();

        subject.given(clazz.className(), "an instance of this class IS this end");
        subject.show(EntityBound.unbounded());
        subject.allowedModes(EntityEndEditor.allModes());
        subject.editable(false);

        population.removeAllItems();
        population.addItem("");
        population.setEnabled(false);

        property.setEnabled(true);
        property.setText(membership.relationPid().isBlank()
                ? MembershipPattern.DEFAULT_PROPERTY : membership.relationPid());
        propertyLabel.setText(clazz.instanceMapping().propertyLabel().isBlank()
                ? " " : clazz.instanceMapping().propertyLabel());

        object.given(clazz.instanceMapping().displaySource(),
                "the entities this property must point into");
        object.editable(true);
        object.allowedModes(EntityEndEditor.explicitOnly());
        object.show(EntityBound.explicit(membership.qids()));

        given.setText("<html><i>The subject is given: an instance of this class IS the "
                + "entity at that end.</i></html>");
    }

    private void applyMembership(GeneratedClassModel clazz) {
        String pid = RuleNode.cleanPid(property.getText());
        if (!WikidataIds.isPid(pid)) pid = MembershipPattern.DEFAULT_PROPERTY;
        List<String> targets = objectQids();
        clazz.membership(targets.isEmpty()
                ? EntityBound.unbounded()
                : EntityBound.relation(pid, targets,
                        clazz.membership().includeDescendants()));
        // The property's label travels with the property. A plain membership has the
        // one named default; anything else keeps what "Find…" or a load resolved.
        clazz.instanceMapping().propertyLabel(
                MembershipPattern.relational(pid)
                        ? propertyLabelText()
                        : MembershipPattern.DEFAULT_PROPERTY_LABEL);
    }

    // ---- Owned: every element given, by the field that produces it ----

    private void showProduced(GeneratedClassModel clazz, GeneratedProjectModel project) {
        List<MembershipPattern.OwnedBy> sites =
                MembershipPattern.ownedBy(clazz, project);
        population.removeAllItems();
        population.addItem("");
        population.setEnabled(false);
        subject.editable(false);
        object.editable(false);
        property.setEnabled(false);

        subject.given(clazz.className(), "an instance of this class IS this end");
        subject.show(EntityBound.unbounded());
        object.show(EntityBound.unbounded());

        if (sites.isEmpty()) {
            property.setText("");
            propertyLabel.setText(" ");
            object.given("", "no owner yet");
            given.setText("<html><i>Produced nowhere yet. Add an ENTITY field to the "
                    + "owning class and select this class as its target.</i></html>");
            return;
        }
        MembershipPattern.OwnedBy first = sites.get(0);
        property.setText(first.ownerClass() + "." + first.fieldName());
        propertyLabel.setText(" ");
        object.given(first.ownerClass(), "the owner each part is a view of");

        StringBuilder note = new StringBuilder("<html><i>Given: authored on ")
                .append(first.ownerClass()).append(", where the field is.");
        if (sites.size() > 1) {
            note.append(" Also produced at ");
            for (int i = 1; i < sites.size(); i++) {
                if (i > 1) note.append(", ");
                note.append(sites.get(i).ownerClass()).append('.')
                        .append(sites.get(i).fieldName());
            }
            note.append('.');
        }
        given.setText(note.append("</i></html>").toString());
    }

    // ---- What the rows say ----

    void clear() {
        property.setText("");
        propertyLabel.setText(" ");
        population.removeAllItems();
        subject.show(null);
        object.show(null);
        given.setText(" ");
    }

    /** The property as typed — blank is blank, because blank means something here. */
    String propertyPid() {
        return RuleNode.cleanPid(property.getText());
    }

    void propertyPid(String pid, String label) {
        property.setText(pid == null ? "" : pid);
        propertyLabel.setText(label == null || label.isBlank() ? " " : label);
    }

    String propertyLabelText() {
        return propertyLabel.getText() == null ? "" : propertyLabel.getText().trim();
    }

    String subjectPopulation() {
        Object selected = population.getSelectedItem();
        return selected == null ? "" : selected.toString().trim();
    }

    /** What each end currently says, as one value. */
    EntityBound subjectBound() {
        return subject.bound();
    }

    EntityBound objectBound() {
        return object.bound();
    }

    /** The objects, as the QIDs they are. */
    List<String> objectQids() {
        return new ArrayList<>(object.bound().qids());
    }

    void objectQids(List<String> qids, String targetLabel) {
        object.show(EntityBound.explicit(qids == null ? List.of() : qids));
        if (targetLabel != null) {
            object.given(targetLabel, "the entities this property must point into");
        }
    }

    String firstObjectQid() {
        List<String> qids = objectQids();
        return qids.isEmpty() ? "" : qids.get(0);
    }

    // ---- What it needs to know, asked of the model ----

    private void subjectPopulation(List<String> classes, String selected) {
        population.removeAllItems();
        population.addItem("");
        for (String name : classes) {
            if (name != null && !name.isBlank()) population.addItem(name);
        }
        String chosen = selected == null ? "" : selected.trim();
        if (chosen.isBlank()) return;
        boolean offered = false;
        for (int i = 0; i < population.getItemCount(); i++) {
            if (chosen.equals(population.getItemAt(i))) offered = true;
        }
        // A name the project cannot currently list is added rather than dropped: what
        // the control shows is what gets written.
        if (!offered) population.addItem(chosen);
        population.setSelectedItem(chosen);
    }

    private static List<String> candidates(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        List<String> names = new ArrayList<>();
        if (project == null) return names;
        for (GeneratedClassModel candidate : project.classes()) {
            if (candidate == null || candidate.className().isBlank()) continue;
            if (candidate.className().equals(clazz.className())) continue;
            names.add(candidate.className());
        }
        return names;
    }

    private static List<String> vocabularies(GeneratedProjectModel project) {
        List<String> names = new ArrayList<>();
        if (project == null) return names;
        for (Selection selection : project.selections()) {
            if (selection instanceof VocabularySelection) names.add(selection.name());
        }
        return names;
    }

    private static String targetClassOf(GeneratedClassModel clazz, String fieldName) {
        return clazz.fields().stream()
                .filter(field -> field != null && fieldName != null
                        && fieldName.equals(field.name()))
                .findFirst().map(field -> field.entityClassName()).orElse("");
    }

    private static String valueKindOf(GeneratedClassModel clazz, String fieldName) {
        return clazz.fields().stream()
                .filter(field -> field != null && fieldName != null
                        && fieldName.equals(field.name()))
                .findFirst()
                .map(field -> field.type() == null ? "" : field.type().name())
                .orElse("");
    }
}
