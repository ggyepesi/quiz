package domain.workbench.modelbuilder.ui;

import domain.config.ClassConfigurationProjection;
import domain.config.ClassKind;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.TransformationClassSource;
import domain.workbench.ui.ConfigurationTreePanel;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mounts each class editor's existing controls as sections of its local tree. */
final class ClassConfigurationEditor extends JPanel {
    static final String OUTLINE_TITLE = "Class settings";

    private final ClassKind kind;
    private final ConfigurationTreePanel tree;
    private final Map<String, JComponent> details = new LinkedHashMap<>();

    ClassConfigurationEditor(ClassKind kind) {
        super(new BorderLayout());
        this.kind = kind;
        tree = new ConfigurationTreePanel("modelbuilder." + kind.name().toLowerCase(
                java.util.Locale.ROOT) + ".configuration", OUTLINE_TITLE);
        add(tree, BorderLayout.CENTER);
    }

    JPanel form(String section) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        detail(section, form);
        return form;
    }

    void detail(String section, JComponent component) {
        details.put(section, topAlignedDetail(component));
    }

    void header(ClassHeaderEditor header) {
        tree.setDetailHeader(header);
    }

    private static JComponent topAlignedDetail(JComponent component) {
        JPanel top = new JPanel(new BorderLayout());
        top.add(component, BorderLayout.NORTH);
        return ConfigurationTreePanel.scrollDetail(top);
    }

    void showClass(GeneratedProjectModel project, GeneratedClassModel clazz) {
        tree.show(ClassConfigurationProjection.project(project, clazz, kind),
                node -> details.get(node.detailKey()));
    }

    void showClass(GeneratedProjectModel project, GeneratedClassModel clazz,
            TransformationClassSource transformation) {
        tree.show(ClassConfigurationProjection.project(project, clazz, kind, transformation),
                node -> details.get(node.detailKey()));
    }

    ConfigurationTreePanel tree() { return tree; }
}
