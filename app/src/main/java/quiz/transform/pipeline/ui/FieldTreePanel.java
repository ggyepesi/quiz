package quiz.transform.pipeline.ui;

import quiz.transform.ui.DomainField;
import objectview.field.FieldKind;
import objectview.viewconfig.FieldTypeSource;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A single-selection field PICKER as an in-place TREE: top-level fields are nodes,
 * and a reference field expands to its nested fields (dotted paths) right where it
 * sits. Selecting any node — a scalar leaf or a reference itself — yields that
 * field's {@link DomainField}. Purpose-built for pipeline config, where you choose
 * ONE (possibly nested) field, so it's clearer than the multi-check card-config
 * table (no "expand selects everything", no nested tables).
 */
public final class FieldTreePanel extends JPanel {

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("fields");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final Map<String, DefaultMutableTreeNode> byPath = new LinkedHashMap<>();
    private Runnable onChange;

    public FieldTreePanel() {
        setLayout(new BorderLayout());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> {
            if (onChange != null) {
                onChange.run();
            }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Rebuild the tree from {@code fields} (dotted paths), skipping any path whose
     *  top segment is in {@code hiddenTop} (structural). {@code types} (nullable)
     *  supplies the model type label shown per node. Clears the selection. */
    public void setFields(List<DomainField> fields, Set<String> hiddenTop,
                          FieldTypeSource types) {
        root.removeAllChildren();
        byPath.clear();

        // Shortest paths first so an ancestor node exists before its children.
        List<DomainField> sorted = new ArrayList<>(fields);
        sorted.sort(Comparator
                .comparingInt((DomainField f) -> f.field().split("\\.").length)
                .thenComparing(DomainField::field));

        for (DomainField f : sorted) {
            String[] seg = f.field().split("\\.");
            if (hiddenTop != null && hiddenTop.contains(seg[0])) {
                continue;
            }
            DefaultMutableTreeNode parent = root;
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < seg.length; i++) {
                if (i > 0) {
                    prefix.append('.');
                }
                prefix.append(seg[i]);
                DefaultMutableTreeNode node = byPath.get(prefix.toString());
                if (node == null) {
                    node = new DefaultMutableTreeNode(new FieldNode(seg[i], null, null));
                    parent.add(node);
                    byPath.put(prefix.toString(), node);
                }
                parent = node;
            }
            // Attach the exact field at its leaf path (keeps ancestor fields intact).
            byPath.get(f.field()).setUserObject(
                    new FieldNode(seg[seg.length - 1], f, typeLabel(types, seg)));
        }

        model.reload();
    }

    /** Walk the nested type sources down {@code seg} to the field's type label, or
     *  null when no type info is available (non-compiled domains). */
    private static String typeLabel(FieldTypeSource types, String[] seg) {
        FieldTypeSource level = types;
        FieldTypeSource.FieldTypeInfo info = null;
        for (String s : seg) {
            if (level == null) {
                return null;
            }
            info = level.field(s);
            if (info == null) {
                return null;
            }
            level = info.nested();
        }
        return info == null ? null : info.typeLabel();
    }

    /** The selected field, or null when nothing (or a container-only node) is chosen. */
    public DomainField selectedField() {
        if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode n
                && n.getUserObject() instanceof FieldNode fn) {
            return fn.field();
        }
        return null;
    }

    /** Select the node at {@code dottedPath} (for reflecting an external choice). */
    public void selectPath(String dottedPath) {
        DefaultMutableTreeNode node = dottedPath == null ? null : byPath.get(dottedPath);
        if (node == null) {
            tree.clearSelection();
            return;
        }
        TreePath path = new TreePath(node.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    /** A tree node: the segment label, the field it stands for (null for a bare
     *  container), and the model type label. {@code toString} shows the model type
     *  when known, else the field's kind (so a field the model doesn't describe —
     *  e.g. the identity {@code name} — still reads {@code : String}), else a shape
     *  hint for references/collections. */
    private record FieldNode(String label, DomainField field, String typeLabel) {
        @Override
        public String toString() {
            if (field == null) {
                return label;
            }
            if (typeLabel != null && !typeLabel.isBlank()) {
                return label + "  :  " + typeLabel;
            }
            String kind = kindLabel(field.kind());
            if (kind != null) {
                return label + "  :  " + kind;
            }
            String shape = field.reference() ? (field.collection() ? "  · ref[]" : "  · ref")
                    : field.collection() ? "  · []" : "";
            return label + shape;
        }
    }

    /** A coarse display label for a value kind, when no specific model type is known;
     *  null for reference/collection/unknown (shown as a shape hint instead). */
    private static String kindLabel(FieldKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case BOOLEAN -> "Boolean";
            case ORDERED -> "Number";
            case TEXT -> "String";
            default -> null;
        };
    }
}
