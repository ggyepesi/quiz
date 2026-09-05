package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.codegen.GeneratedViewableSourceGenerator;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Structural editor for an Owned class. Ownership sites remain field-defined. */
final class OwnedClassPanel extends JPanel {

    private final GeneratedProjectModel project;
    // Name, alias and extends are class facts, so they are the shared header. What is
    // left here is what an OWNED class adds: where it is produced.
    private final ClassHeaderEditor header;
    private final JLabel sites = new JLabel(" ");
    private final JButton apply = new JButton("Apply owned class");
    private GeneratedClassModel clazz;
    private Consumer<Void> afterChange = ignored -> {};

    OwnedClassPanel(GeneratedProjectModel project) {
        super(new BorderLayout());
        this.project = project;
        // An Owned class may extend only another Owned class — the validator's rule,
        // said here as which candidates the header offers rather than as a switch
        // inside it.
        this.header = new ClassHeaderEditor(project, () -> project.classes().stream()
                .filter(candidate -> candidate != null && candidate.ownedClass())
                .map(GeneratedClassModel::className)
                .toList());
        buildUi();
    }

    void afterChange(Consumer<Void> consumer) {
        afterChange = consumer == null ? ignored -> {} : consumer;
    }

    void edit(GeneratedClassModel value) {
        clazz = value;
        header.show(value);
        refreshSites();
    }

    void applyEdits() {
        if (clazz == null) return;
        header.applyEdits();
        clazz.discriminatorPid("");
        clazz.discriminatorQid("");
        clazz.ownedClass(true);
        refreshSites();
        afterChange.accept(null);
    }


    private void refreshSites() {
        String shown = MembershipPattern.ownedBy(clazz, project).stream()
                .map(site -> site.ownerClass() + "." + site.fieldName())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none yet");
        sites.setText("Producing fields: " + shown);
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        GridBagUtils.wideRow(form, row++, new JLabel(
                "<html><b>Owned class</b> — instances are created by fields that target this class.</html>"));
        GridBagUtils.wideRow(form, row++, header);
        GridBagUtils.wideRow(form, row++, sites);
        GridBagUtils.wideRow(form, row++, new JLabel(
                "<html>The owner is not configured here. Add an ENTITY field to the "
                        + "owner class and select this class as its target.</html>"));
        GridBagUtils.wideRow(form, row, apply);
        apply.addActionListener(event -> applyEdits());
        add(form, BorderLayout.NORTH);
    }
}
