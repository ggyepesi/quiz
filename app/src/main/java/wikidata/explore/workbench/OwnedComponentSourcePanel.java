package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldRenderMode;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Class-level editor for field-owned components.
 *
 * <p>The class kind is deliberately a projection of an owning ENTITY field.  This
 * panel makes that relationship configurable from the target class without adding
 * a second owner setting to {@link GeneratedClassModel}.</p>
 */
final class OwnedComponentSourcePanel extends JPanel {

    private final GeneratedProjectModel project;
    private final JComboBox<String> ownerBox = new JComboBox<>();
    private final JTextField fieldName = new JTextField(18);
    private final JLabel existingSites = new JLabel(" ");
    private final JButton apply = new JButton("Apply owned component");

    private GeneratedClassModel target;
    private Consumer<Void> afterChange = ignored -> {};

    OwnedComponentSourcePanel(GeneratedProjectModel project) {
        super(new BorderLayout(4, 4));
        this.project = project;
        buildUi();
    }

    /** Picks the owner the site will be declared on (the editor's combo). */
    void selectOwner(String ownerClass) {
        ownerBox.setSelectedItem(ownerClass);
    }

    void afterChange(Consumer<Void> consumer) {
        afterChange = consumer == null ? ignored -> {} : consumer;
    }

    void edit(GeneratedClassModel clazz) {
        target = clazz;
        ownerBox.removeAllItems();
        for (GeneratedClassModel candidate : project.classes()) {
            if (candidate != null && clazz != null
                    && !candidate.className().equals(clazz.className())) {
                ownerBox.addItem(candidate.className());
            }
        }

        List<MembershipPattern.OwnedBy> sites = MembershipPattern.ownedBy(clazz, project);
        existingSites.setText(sites.isEmpty()
                ? "No owning field configured yet."
                : "Produced by " + sites.stream()
                        .map(site -> site.ownerClass() + "." + site.fieldName())
                        .reduce((a, b) -> a + ", " + b).orElse(""));
        if (!sites.isEmpty()) {
            ownerBox.setSelectedItem(sites.getFirst().ownerClass());
            fieldName.setText(sites.getFirst().fieldName());
        } else {
            fieldName.setText(defaultFieldName(clazz));
        }
        apply.setEnabled(clazz != null && ownerBox.getItemCount() > 0);
    }

    void applyEdits() {
        declareSite(true);
    }

    /**
     * Writes the owning field the class kind is READ from. The kind of an owned
     * component is not stored on the class — it IS the existence of this field
     * elsewhere — so until this runs the model still says UNCONFIGURED, and any refresh
     * drops the class back to "Source class". Switching the kind therefore declares the
     * site straight away with the shown defaults, exactly as switching to a statement
     * class writes its statement source.
     *
     * @return false when there is no other class to own the component.
     */
    boolean declareSite(boolean explain) {
        if (target == null) return false;
        Object selectedOwner = ownerBox.getSelectedItem();
        String requestedName = clean(fieldName.getText());
        if (requestedName.isEmpty()) requestedName = defaultFieldName(target);
        if (selectedOwner == null) {
            if (explain) {
                JOptionPane.showMessageDialog(this,
                        "Choose an owner class and enter the owning field name.",
                        "Owned component", JOptionPane.WARNING_MESSAGE);
            }
            return false;
        }
        GeneratedClassModel owner = project.findClass(String.valueOf(selectedOwner));
        if (owner == null || owner == target) return false;

        clearIndependentPopulation(target);
        // One site at a time: re-pointing the owner MOVES the component's production
        // site rather than leaving the old field behind to produce a second one.
        detachSites(target, project);
        final String name = requestedName;
        GeneratedFieldModel field = owner.fields().stream()
                .filter(value -> value != null && name.equals(value.name()))
                .findFirst().orElseGet(() -> owner.addField(
                        name, FieldType.ENTITY, FieldCardinality.SINGLE));
        field.type(FieldType.ENTITY);
        field.cardinality(FieldCardinality.SINGLE);
        field.renderMode(FieldRenderMode.INLINE);
        field.entityClassName(target.className());
        field.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        field.mapping().propertyPid("");
        field.mapping().propertyLabel("");
        field.mapping().qualifierPid("");
        field.required(false);

        edit(target);
        afterChange.accept(null);
        return true;
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        // Spelled out rather than positional: the shorter gbc() overloads read the
        // trailing ints as ⟨anchor, fill⟩, so a gridwidth passed there becomes an
        // anchor — and anchor 0 is not a legal one, which GridBagLayout only reports
        // when it lays the panel out.
        Insets pad = new Insets(2, 4, 2, 4);
        int row = 0;
        form.add(new JLabel("Owner class:"), GridBagUtils.spanning(0, row, 1, 0, 0,
                GridBagConstraints.WEST, GridBagConstraints.NONE, pad));
        form.add(ownerBox, GridBagUtils.spanning(1, row++, 1, 1, 0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, pad));
        form.add(new JLabel("Owning field:"), GridBagUtils.spanning(0, row, 1, 0, 0,
                GridBagConstraints.WEST, GridBagConstraints.NONE, pad));
        form.add(fieldName, GridBagUtils.spanning(1, row++, 1, 1, 0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, pad));
        form.add(existingSites, GridBagUtils.spanning(0, row++, 2, 1, 0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, pad));
        JLabel help = new JLabel("<html>The owner field creates one component with "
                + "the owner's QID. Fields declared on this class load from that QID.</html>");
        form.add(help, GridBagUtils.spanning(0, row++, 2, 1, 0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, pad));
        form.add(apply, GridBagUtils.spanning(0, row, 2, 0, 0,
                GridBagConstraints.WEST, GridBagConstraints.NONE, pad));
        apply.addActionListener(event -> applyEdits());
        add(form, BorderLayout.NORTH);
    }

    static void detachSites(GeneratedClassModel target, GeneratedProjectModel project) {
        if (target == null || project == null) return;
        for (GeneratedClassModel owner : project.classes()) {
            if (owner == null) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field != null
                        && field.mapping().productionKind() == FieldProductionKind.OWNED_COMPONENT
                        && target.className().equals(field.entityClassName())) {
                    field.mapping().productionKind(FieldProductionKind.AUTO);
                }
            }
        }
    }

    private static void clearIndependentPopulation(GeneratedClassModel clazz) {
        clazz.statementSource(null);
        clazz.seedQids().clear();
        var mapping = clazz.instanceMapping();
        mapping.sourceQid("");
        mapping.sourceLabel("");
        mapping.propertyPid("");
        mapping.propertyLabel("");
        mapping.additionalTypeQids().clear();
        mapping.excludedTypeQids().clear();
    }

    private static String defaultFieldName(GeneratedClassModel clazz) {
        if (clazz == null || clazz.className().isBlank()) return "component";
        String name = clazz.className();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
