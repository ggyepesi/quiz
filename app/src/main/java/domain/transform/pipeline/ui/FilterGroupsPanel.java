package domain.transform.pipeline.ui;

import domain.config.GeneratedClassModel;
import domain.config.TransformationClassSource;
import domain.transform.EditableGroup;
import domain.transform.FilterCondition;
import domain.transform.OperationGroup;
import domain.transform.ui.ProjectBacking;
import domain.transform.ui.TransformController;
import objectview.Viewable;
import objectview.group.ViewableGroup;
import objectview.render.GroupMembersView;
import objectview.render.RenderContext;
import objectview.utils.swing.WrapLayout;
import objectview.view.SearchableView;
import wikidata.ui.WikidataLinks;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Compose and inspect temporary filters, then declare their reproducible output. */
public final class FilterGroupsPanel extends JPanel implements AutoCloseable {
    private final TransformController controller;
    private final BooleanSupplier mayCreate;
    private final Consumer<GeneratedClassModel> created;
    private final TransformFieldPicker fields;
    private final FilterGroupEditor filter;
    private final JPanel groupsHolder = new JPanel(new BorderLayout());
    private final JTextField className = new JTextField(18);
    private final JButton promote = new JButton("Create transformed class");
    private final JTextArea status = new JTextArea();
    private GroupMembersView groups;
    private EditableGroup root;
    private ViewableGroup<?> selection;
    private SearchableView activeMembers;

    public FilterGroupsPanel(TransformController controller, BooleanSupplier mayCreate,
            Consumer<GeneratedClassModel> created) {
        super(new BorderLayout(4, 4));
        this.controller = controller;
        this.mayCreate = mayCreate;
        this.created = created;
        fields = new TransformFieldPicker(controller,
                () -> selection == null ? List.of() : selection.getMembers());
        filter = new FilterGroupEditor(controller, this::addFilter);
        fields.onChange(() -> filter.edit(fields.currentField()), this::showClass);
        JPanel editor = new JPanel(new BorderLayout(6, 6));
        editor.add(fields.classRow(), BorderLayout.NORTH);
        editor.add(fields.fieldBlock(), BorderLayout.CENTER);
        editor.add(filter, BorderLayout.SOUTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editor, groupsHolder);
        split.setResizeWeight(0.43);
        add(split, BorderLayout.CENTER);

        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 3));
        JLabel label = new JLabel("Output class name:");
        label.setLabelFor(className);
        className.setName("transform.outputClass");
        controls.add(label);
        controls.add(className);
        controls.add(promote);
        promote.addActionListener(event -> promote());
        status.setEditable(false);
        status.setOpaque(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(controls, BorderLayout.NORTH);
        footer.add(status, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        showClass();
    }

    private void showClass() {
        String type = controller.selectedType();
        if (type == null) return;
        root = (EditableGroup) controller.groupRoot(type);
        showGroups(root);
        status.setText("Filter the loaded " + type + " instances. A filter group is temporary; "
                + "creating a transformed class keeps its rule in the open configuration.");
    }

    private void showGroups(ViewableGroup<?> selected) {
        close();
        String type = controller.selectedType();
        RenderContext context = new RenderContext();
        groups = new GroupMembersView(root, group -> {
            disposeMemberView();
            activeMembers = instanceCards(controller, type, group.getMembers(), context);
            return activeMembers;
        }, JSplitPane.HORIZONTAL_SPLIT, false, 0.35, true, context);
        groups.setSelectionHandler(group -> {
            selection = group;
            fields.refreshWorkingSet();
            updateActions();
        });
        groupsHolder.add(groups, BorderLayout.CENTER);
        // Keep the filter's scope and the visible cards in step.
        selection = selected;
        if (groups.groups().selectedGroup() == selected) groups.showGroup(selected);
        else groups.selectGroup(selected, false);
        fields.refreshWorkingSet();
        updateActions();
        groupsHolder.revalidate();
        groupsHolder.repaint();
    }

    private void addFilter(String name, FilterCondition condition) {
        if (!isEnabled() || root == null) return;
        if (selection != root && !(selection instanceof OperationGroup)) {
            status.setText("Select the class root or a filter group under it first.");
            return;
        }
        var parent = (EditableGroup) selection;
        if (parent.getChild(name) != null) {
            status.setText("A group named “" + name + "” already exists here.");
            return;
        }
        var group = controller.addFilterGroup(controller.selectedType(), parent, name, condition);
        showGroups(group);
        status.setText("“" + name + "” matches " + group.getMembers().size() + " of "
                + parent.getMembers().size() + " instances. No files were written.");
    }

    private void promote() {
        if (!isEnabled() || !(selection instanceof OperationGroup group) || !mayCreate.getAsBoolean()) return;
        try {
            GeneratedClassModel output = controller.declareTransformedClass(className.getText(), group);
            String publication = TransformationClassSource.filterPublicationName(output.className());
            ProjectBacking project = controller.domain().capability(ProjectBacking.class);
            status.setText("Created " + output.className() + " and " + publication
                    + " in the open configuration. Preview population uses the loaded inputs. "
                    + "In the build, put Run transformation " + output.className()
                    + " and Publish population " + publication
                    + " after their inputs and before Save project. Then save "
                    + project.modelFile().getPath() + ".");
            created.accept(output);
        } catch (IllegalArgumentException refused) {
            status.setText(refused.getMessage());
        }
    }

    private void updateActions() {
        promote.setEnabled(isEnabled() && selection instanceof OperationGroup
                && !selection.getMembers().isEmpty());
    }

    public void editingEnabled(boolean enabled) {
        setEnabled(enabled);
        fields.classSelectionEnabled(enabled);
        filter.creationEnabled(enabled);
        className.setEditable(enabled);
        updateActions();
    }

    public static SearchableView instanceCards(TransformController controller, String type,
            Collection<? extends Viewable> members, RenderContext context) {
        var domain = controller.domain();
        return SearchableView.builder(List.copyOf(members))
                .sample(domain.configSample(type))
                .hiddenFields(domain.structuralFields(type))
                .fieldTypes(domain.fieldTypes(type))
                .fieldSchemas(value -> controller.renderedFieldSchema(value, type))
                .typeSchemas(domain::fieldSchema)
                .subtypeConfigs(controller.subtypeConfigs(type))
                .valueLinker(WikidataLinks.valueLinker())
                .renderContext(context)
                .emptyMessage("No matching instances.")
                .collapsible(true).build();
    }

    public void filterGroupNamePrompt(Supplier<String> prompt) {
        filter.filterGroupNamePrompt(prompt);
    }

    @Override public void close() {
        disposeMemberView();
        if (groups != null) {
            groups.dispose();
            groupsHolder.remove(groups);
            groups = null;
        }
    }

    private void disposeMemberView() {
        if (activeMembers != null) {
            activeMembers.dispose();
            activeMembers = null;
        }
    }
}
