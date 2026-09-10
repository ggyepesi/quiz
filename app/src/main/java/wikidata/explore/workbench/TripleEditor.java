package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import workbench.SimpleDocumentListener;
import wikidata.WikidataIds;
import wikidata.ui.WikidataLinks;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.EntityRepresentations;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.model.Selection;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.VocabularySelection;
import wikidata.explore.rule.RuleNode;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;
import java.awt.BorderLayout;
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
    private final JPanel subjectPopulationControls = new JPanel(new GridBagLayout());
    private final JPanel statementSubjectPopulationRow = new JPanel(
            new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JLabel sourceSubject = new JLabel(" ");
    /** Says which elements are given, and by what. Blank when the kind authors them. */
    private final JLabel given = new JLabel(" ");
    /** One visible answer to whether this triple can produce instances. */
    private final JLabel configurationStatus = new JLabel(" ");
    /** Invalid text remains an editor draft, but Apply must say that it was not
     * written to the valid model instead of leaving the loss invisible. */
    private String unappliedDetail = "";

    TripleEditor() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder(TITLE));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);

        JPanel subjectSection = new JPanel(new BorderLayout(0, 4));
        JPanel subjectContents = new JPanel(new BorderLayout());
        subjectContents.add(sourceSubject, BorderLayout.NORTH);
        subjectContents.add(subject, BorderLayout.CENTER);
        subjectSection.add(subjectContents, BorderLayout.CENTER);
        subjectSection.add(subjectPopulationControls, BorderLayout.SOUTH);

        JPanel propertyColumn = new JPanel(new GridBagLayout());
        propertyColumn.setBorder(BorderFactory.createTitledBorder("Property"));
        population.setToolTipText(
                "Optional: the already-extracted class whose statements are read, "
                        + "outgoing from its members. Leave blank to discover subjects "
                        + "incoming from the property instead — which then requires the "
                        + "objects to be bounded, since they become the starting set.");
        statementSubjectPopulationRow.add(new JLabel("Subject population:"));
        statementSubjectPopulationRow.add(population);
        GridBagConstraints populationCell = (GridBagConstraints) c.clone();
        populationCell.gridx = 0;
        populationCell.gridy = 0;
        populationCell.gridwidth = 2;
        populationCell.anchor = GridBagConstraints.WEST;
        subjectPopulationControls.add(statementSubjectPopulationRow, populationCell);

        JPanel propertyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        property.setToolTipText("The property this triple is about.");
        propertyRow.add(property);
        propertyRow.add(propertyActions);
        propertyRow.add(propertyLabel);
        GridBagUtils.labeledRow(propertyColumn, c, 0,
                "Wikidata property:", propertyRow);

        JPanel objectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        objectRow.add(object);
        objectRow.add(objectActions);
        GridBagConstraints wide = (GridBagConstraints) c.clone();
        wide.gridx = 0;
        wide.gridwidth = 2;
        wide.fill = GridBagConstraints.HORIZONTAL;
        wide.weightx = 1;
        wide.gridy = 0;
        add(subjectSection, wide);

        GridBagConstraints propertyCell = (GridBagConstraints) wide.clone();
        propertyCell.gridy = 1;
        add(propertyColumn, propertyCell);

        GridBagConstraints objectCell = (GridBagConstraints) wide.clone();
        objectCell.gridy = 2;
        add(objectRow, objectCell);

        GridBagConstraints givenCell = (GridBagConstraints) wide.clone();
        givenCell.gridy = 3;
        add(given, givenCell);

        GridBagConstraints statusCell = (GridBagConstraints) wide.clone();
        statusCell.gridy = 4;
        add(configurationStatus, statusCell);

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

    /** Places a Source-specific population alternative with the subject it affects. */
    void sourceSubjectControl(String label, JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        GridBagUtils.labeledRow(subjectPopulationControls, c, 1, label, control);
    }

    /** This class's triple, with whatever it does not author shown and disabled. */
    void show(GeneratedClassModel clazz, GeneratedProjectModel project) {
        unappliedDetail = "";
        if (clazz == null) {
            clear();
            return;
        }
        List<String> vocabularies = vocabularies(project);
        subject.vocabularies(() -> vocabularies);
        object.vocabularies(() -> vocabularies);
        object.descendantOptionForExplicitQids(false);
        subject.consequence(null);
        object.consequence(null);
        if (clazz.ownedClass()) {
            showProduced(clazz, project);
        } else if (clazz.classKind() == ClassKind.STATEMENT) {
            showStatement(clazz, project);
        } else {
            showMembership(clazz);
        }
        refreshConfigurationStatus(clazz, project);
    }

    /** Writes back whatever this class's kind authors here, and nothing else. */
    void applyEdits(GeneratedClassModel clazz) {
        if (clazz == null || clazz.ownedClass()) return;
        if (clazz.classKind() == ClassKind.STATEMENT) {
            applyStatement(clazz);
        } else {
            applyMembership(clazz);
        }
    }

    // ---- Statement: every element authored ----

    private void showStatement(GeneratedClassModel clazz, GeneratedProjectModel project) {
        StatementClassSource source = clazz.statementSource();
        sourceSubject.setVisible(false);
        subject.setVisible(true);
        subject.editable(true);
        subject.allowedModes(EntityEndEditor.allModes());
        subject.show(source == null ? null : source.subjectBound());

        population.setEnabled(true);
        subjectPopulationControls.setVisible(true);
        statementSubjectPopulationRow.setVisible(true);
        subjectPopulation(candidates(clazz, project),
                source == null ? "" : source.sourceClassName());

        property.setEnabled(true);
        property.setText(source == null ? "" : source.propertyPid());
        propertyLabel.setText(source == null || source.propertyLabel().isBlank()
                ? " " : source.propertyLabel());

        object.setVisible(true);
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

        subject.setVisible(false);
        sourceSubject.setText("<html><b>Instances produced:</b> "
                + clazz.className() + "</html>");
        sourceSubject.setVisible(true);
        subject.show(EntityBound.unbounded());
        subject.allowedModes(EntityEndEditor.allModes());
        subject.editable(false);

        population.removeAllItems();
        population.addItem("");
        population.setEnabled(false);
        subjectPopulationControls.setVisible(true);
        statementSubjectPopulationRow.setVisible(false);

        property.setEnabled(true);
        property.setText(membership.relationPid().isBlank()
                ? MembershipPattern.DEFAULT_PROPERTY : membership.relationPid());
        propertyLabel.setText(clazz.instanceMapping().propertyLabel().isBlank()
                ? " " : clazz.instanceMapping().propertyLabel());

        object.setVisible(true);
        object.editable(true);
        object.allowedModes(EntityEndEditor.explicitOnly());
        object.descendantOptionForExplicitQids(true);
        object.consequence("Bounding the object restricts WHICH entities become "
                + "instances of this class.");
        object.show(EntityBound.explicit(membership.qids()));
        object.includeDescendants(membership.includeDescendants());

        given.setText("<html><i>Matching subjects become instances of this class.</i></html>");
    }

    private void applyMembership(GeneratedClassModel clazz) {
        String pid = RuleNode.cleanPid(property.getText());
        if (!WikidataIds.isPid(pid)) pid = MembershipPattern.DEFAULT_PROPERTY;
        List<String> targets = objectQids();
        unappliedDetail = targets.isEmpty()
                && !MembershipPattern.DEFAULT_PROPERTY.equals(pid)
                ? "property " + pid + " was not saved — add an object"
                : "";
        clazz.membership(targets.isEmpty()
                ? EntityBound.unbounded()
                : EntityBound.relation(pid, targets,
                        object.includesDescendants()));
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
        subjectPopulationControls.setVisible(false);
        statementSubjectPopulationRow.setVisible(false);
        sourceSubject.setVisible(false);
        subject.editable(false);
        object.editable(false);
        property.setEnabled(false);

        subject.setVisible(false);
        object.setVisible(false);
        subject.show(EntityBound.unbounded());
        object.show(EntityBound.unbounded());

        if (sites.isEmpty()) {
            property.setText("");
            propertyLabel.setText(" ");
            given.setText("<html><i>Produced nowhere yet. Add an ENTITY field to the "
                    + "owning class and select this class as its target.</i></html>");
            return;
        }
        MembershipPattern.OwnedBy first = sites.get(0);
        property.setText(first.ownerClass() + "." + first.fieldName());
        propertyLabel.setText(" ");

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
        configurationStatus.setText(" ");
    }

    /**
     * Shows whether the class has an executable instance source.  The triple remains
     * one shared component; only the rule answering readiness differs by class kind.
     */
    void refreshConfigurationStatus(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null) {
            configurationStatus.setText(" ");
            return;
        }

        String detail;
        boolean ready;
        if (clazz.ownedClass()) {
            List<MembershipPattern.OwnedBy> sites = MembershipPattern.ownedBy(clazz, project);
            ready = !sites.isEmpty();
            detail = ready
                    ? "produced by " + sites.getFirst().ownerClass() + "."
                            + sites.getFirst().fieldName()
                    : "add an ENTITY field that produces this class";
        } else if (clazz.classKind() == ClassKind.STATEMENT) {
            StatementClassSource source = clazz.statementSource();
            ready = source != null && source.isConfigured();
            detail = ready ? "statement property " + source.propertyPid()
                    : "choose a statement property";
        } else {
            boolean seeds = !clazz.seedQids().isEmpty();
            boolean relation = clazz.membership().bounded();
            List<String> representedRoles =
                    EntityRepresentations.rolesRepresentedAs(project, clazz);
            boolean represented = !representedRoles.isEmpty();
            ready = seeds || relation || represented;
            if (seeds && relation) {
                detail = "property + object, restricted to " + clazz.seedQids().size()
                        + " explicit QID" + (clazz.seedQids().size() == 1 ? "" : "s");
            } else if (relation) {
                detail = "property + object";
            } else if (seeds) {
                detail = clazz.seedQids().size() + " explicit QID"
                        + (clazz.seedQids().size() == 1 ? "" : "s");
            } else if (represented) {
                EntityKindRule admission = MembershipPattern.kindRule(clazz, project);
                detail = "represented from " + String.join(", ", representedRoles)
                        + (admission == null ? "" : " when "
                        + admission.propertyPid() + " contains "
                        + String.join(", ", admission.evidenceQids()))
                        + "; own population limit is unused";
            } else {
                detail = "add explicit QIDs, or choose both a property and object";
            }
            if (!unappliedDetail.isBlank()) {
                detail += "; " + unappliedDetail;
            }
        }

        configurationStatus.setText((ready ? "✓ Ready — " : "⚠ Incomplete — ") + detail);
        configurationStatus.setForeground(ready
                ? new java.awt.Color(0x18, 0x65, 0x2A)
                : new java.awt.Color(0xB0, 0x00, 0x20));
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

    /**
     * Re-shows the object QIDs without disturbing the rest of that end.
     *
     * <p>An explicit bound cannot carry subclass closure, so showing one silently
     * cleared the checkbox beside it — and because Apply re-shows the QIDs it had
     * just written, the NEXT Apply read the cleared box and stored a membership the
     * reader never asked for. The closure is a separate authored fact here; it is
     * carried across a redisplay rather than inferred from a bound that has no room
     * for it.
     */
    void objectQids(List<String> qids) {
        boolean descendants = object.includesDescendants();
        object.show(EntityBound.explicit(qids == null ? List.of() : qids));
        object.includeDescendants(descendants);
    }

    void includeMembershipDescendants(boolean value) {
        object.includeDescendants(value);
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

}
