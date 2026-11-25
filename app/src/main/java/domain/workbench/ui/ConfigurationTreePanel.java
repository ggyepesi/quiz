package domain.workbench.ui;

import domain.config.ConfigurationNode;
import domain.config.ConfigurationReferenceInventory;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The shared Swing presentation of a {@link ConfigurationNode} projection.
 *
 * <p>The neutral {@link TreeAndDetailPanel} owns tree mechanics. This class owns the
 * meaning common to every configuration editor: the four node appearances and the
 * explicit Open command for references. Construct editors supply only their detail
 * components and react to the selected semantic node.</p>
 */
public final class ConfigurationTreePanel extends JPanel {
    private final TreeAndDetailPanel<ConfigurationNode.Id> tree;
    private final Map<ConfigurationNode.Id, ConfigurationNode> nodes =
            new LinkedHashMap<>();
    private ConfigurationNode root;
    private final LinkedHashSet<JComponent> editorDetails = new LinkedHashSet<>();
    private JComponent detailHeader;
    private final JPanel referenceDetail = new JPanel(new BorderLayout());
    private final JLabel referenceLabel =
            new JLabel("Select a reference to open it.");
    private final JButton openReference = new JButton("Open");
    private ConfigurationReferenceInventory.Site selectedReference;
    private Consumer<ConfigurationNode> selectionListener = ignored -> { };
    private Consumer<Runnable> selectionScope = Runnable::run;
    private Consumer<ConfigurationReferenceInventory.Site> navigationListener =
            ignored -> { };
    private Predicate<ConfigurationReferenceInventory.Site> canNavigate = site -> true;

    public ConfigurationTreePanel(String componentNamePrefix, String outlineTitle) {
        super(new BorderLayout());
        tree = new TreeAndDetailPanel<>(componentNamePrefix, outlineTitle);
        buildReferenceDetail();
        add(tree, BorderLayout.CENTER);
        tree.setCellRenderer(new NodeRenderer());
        tree.setSelectionListener(selection -> selected(selection.id()));
        tree.tree().addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) openSelectedReference();
            }
        });
        tree.tree().getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke("ENTER"), "open-configuration-reference");
        tree.tree().getActionMap().put("open-configuration-reference",
                new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent event) {
                        openSelectedReference();
                    }
                });
    }

    public void onSelection(Consumer<ConfigurationNode> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
    }

    /** Lets an inspection host scope loading of a detail and enforce its editability. */
    public void selectionScope(Consumer<Runnable> scope) {
        selectionScope = scope;
    }

    public void onNavigate(Consumer<ConfigurationReferenceInventory.Site> listener) {
        navigationListener = listener == null ? ignored -> { } : listener;
    }

    public void navigationAvailableWhen(Predicate<ConfigurationReferenceInventory.Site> value) {
        canNavigate = value;
        updateReferenceDetail(selectedNode());
    }

    public void setDetailHeader(JComponent header) {
        detailHeader = header;
        tree.setDetailHeader(header);
    }

    /** Replaces one complete projection while the neutral shell preserves layout state. */
    public void show(ConfigurationNode root,
            Function<ConfigurationNode, JComponent> detailFor) {
        this.root = root;
        nodes.clear();
        editorDetails.clear();
        if (detailHeader != null) editorDetails.add(detailHeader);
        selectedReference = null;
        updateReferenceDetail(null);
        if (root == null) {
            tree.replaceRoots(List.of());
            return;
        }
        tree.replaceRoots(root.children().stream().map(node -> branch(node, detailFor)).toList());
        if (tree.selectedId() == null) {
            if (!root.children().isEmpty()) tree.select(root.children().getFirst().id());
        } else {
            // Projection replacement intentionally suppresses the neutral shell's
            // selection callback. Restore the semantic state (notably the explicit
            // reference Open command) for the selection it preserved by stable id.
            selected(tree.selectedId());
        }
    }

    /**
     * Updates draft captions while keeping the editor attached and its selection intact.
     * Returns false when a change requires reinstalling the projection.
     */
    public boolean refreshCaptions(ConfigurationNode updated) {
        if (root == null || updated == null || !sameStructure(root, updated)) return false;
        root = updated;
        updated.children().forEach(this::updateCaptions);
        updateReferenceDetail(selectedNode());
        return true;
    }

    private static boolean sameStructure(ConfigurationNode shown, ConfigurationNode updated) {
        if (!shown.id().equals(updated.id()) || shown.kind() != updated.kind()
                || !shown.detailKey().equals(updated.detailKey())
                || !Objects.equals(shown.referenceTarget(), updated.referenceTarget())
                || !Objects.equals(shown.bindingSite(), updated.bindingSite())
                || !Objects.equals(shown.sourceContext(), updated.sourceContext())
                || shown.children().size() != updated.children().size()) return false;
        for (int i = 0; i < shown.children().size(); i++) {
            if (!sameStructure(shown.children().get(i), updated.children().get(i))) return false;
        }
        return true;
    }

    private void updateCaptions(ConfigurationNode node) {
        nodes.put(node.id(), node);
        tree.updateCaption(node.id(), node.caption());
        node.children().forEach(this::updateCaptions);
    }

    public JTree tree() { return tree.tree(); }

    public ConfigurationNode selectedNode() { return nodes.get(tree.selectedId()); }

    public void select(ConfigurationNode.Id id) { tree.select(id); }

    public void expand(ConfigurationNode.Id id) { tree.expand(id); }

    public boolean contains(ConfigurationNode.Id id) { return tree.contains(id); }

    public boolean selectDetail(String detailKey) {
        var node = nodes.values().stream()
                .filter(value -> value.kind() != ConfigurationNode.Kind.REFERENCE
                        && value.detailKey().equals(detailKey)).findFirst();
        node.ifPresent(value -> select(value.id()));
        return node.isPresent();
    }

    public JButton openReferenceButton() { return openReference; }

    public List<ConfigurationNode> nodes() { return List.copyOf(nodes.values()); }

    /** The controls owned by the editor. Reference navigation remains available for inspection. */
    public List<JComponent> editorDetails() { return List.copyOf(editorDetails); }

    public static JScrollPane scrollDetail(JComponent component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private TreeAndDetailPanel.Branch<ConfigurationNode.Id> branch(
            ConfigurationNode node, Function<ConfigurationNode, JComponent> detailFor) {
        nodes.put(node.id(), node);
        boolean reference = node.kind() == ConfigurationNode.Kind.REFERENCE;
        JComponent detail = reference ? referenceDetail : detailFor.apply(node);
        if (detail == null) detail = new JPanel();
        if (!reference) editorDetails.add(detail);
        return new TreeAndDetailPanel.Branch<>(node.id(), node.caption(), detail,
                node.children().stream().map(child -> branch(child, detailFor)).toList());
    }

    private void selected(ConfigurationNode.Id id) {
        ConfigurationNode node = nodes.get(id);
        selectedReference = node == null ? null : node.referenceTarget();
        updateReferenceDetail(node);
        if (node != null) selectionScope.accept(() -> selectionListener.accept(node));
    }

    private void buildReferenceDetail() {
        referenceDetail.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
        referenceDetail.add(referenceLabel, BorderLayout.NORTH);
        JPanel open = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 6));
        open.add(openReference);
        referenceDetail.add(open, BorderLayout.CENTER);
        openReference.setEnabled(false);
        openReference.addActionListener(event -> openSelectedReference());
    }

    private void updateReferenceDetail(ConfigurationNode node) {
        boolean available = selectedReference != null && canNavigate.test(selectedReference);
        openReference.setEnabled(available);
        if (selectedReference == null) {
            referenceLabel.setText("Select a reference to open it.");
            openReference.setText("Open");
        } else {
            String caption = node == null ? selectedReference.displayName() : node.caption();
            referenceLabel.setText(available ? caption : caption + " — no detail editor here");
            openReference.setText("Open " + selectedReference.displayName());
        }
    }

    private void openSelectedReference() {
        if (openReference.isEnabled()) navigationListener.accept(selectedReference);
    }

    private final class NodeRenderer extends DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(
                JTree valueTree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean focus) {
            JLabel label = (JLabel) super.getTreeCellRendererComponent(
                    valueTree, value, selected, expanded, leaf, row, focus);
            if (!(value instanceof javax.swing.tree.DefaultMutableTreeNode treeNode)
                    || !(treeNode.getUserObject() instanceof TreeAndDetailPanel.Item<?> item)
                    || !(item.id() instanceof ConfigurationNode.Id id)) return label;
            ConfigurationNode node = nodes.get(id);
            if (node == null) return label;
            if (node.kind() == ConfigurationNode.Kind.AVAILABLE_SLOT) {
                label.setFont(label.getFont().deriveFont(Font.ITALIC));
                if (!selected) label.setForeground(new Color(0x0969da));
            } else if (node.kind() == ConfigurationNode.Kind.REFERENCE) {
                if (!selected) label.setForeground(new Color(0x0969da));
            } else if (node.kind() == ConfigurationNode.Kind.INVENTORY_SUMMARY) {
                if (!selected) label.setForeground(new Color(0x6e7781));
            } else if (!selected) {
                label.setForeground(UIManager.getColor("Tree.textForeground"));
            }
            return label;
        }
    }
}
