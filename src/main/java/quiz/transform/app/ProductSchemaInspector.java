package quiz.transform.app;

import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shows the compile per class, one row per field: the declared ModelClass field on
 * the left, the resulting {@link ProductField} on the right, and a Note explaining
 * the change — an unmodeled reference collapsing to a String, a reify list appearing,
 * a structural field, cardinality resolved. Pick a class from the dropdown.
 */
public final class ProductSchemaInspector extends JPanel {

    private final GeneratedProjectModel model;
    private final ProductSchema schema;

    private final JComboBox<String> classCombo = new JComboBox<>();
    private final JLabel info = new JLabel(" ");
    private final DefaultTableModel rows = new DefaultTableModel(
            new Object[]{"Field", "ModelClass", "ProductClass", "Shape", "Note"}, 0);

    public ProductSchemaInspector(GeneratedProjectModel model, ProductSchema schema) {
        this.model = model;
        this.schema = schema;
        setLayout(new BorderLayout(8, 8));

        for (String name : schema.allClassNames()) {
            classCombo.addItem(name);
        }

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(new JLabel("Class:"));
        top.add(classCombo);
        top.add(info);

        JTable table = new JTable(rows) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(260);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        classCombo.addActionListener(e -> showClass((String) classCombo.getSelectedItem()));
        if (classCombo.getItemCount() > 0) {
            classCombo.setSelectedIndex(0);
            showClass((String) classCombo.getSelectedItem());
        }
    }

    private void showClass(String name) {
        rows.setRowCount(0);
        if (name == null) {
            return;
        }

        GeneratedClassModel mc = model == null ? null : model.findClass(name);
        Map<String, GeneratedFieldModel> modelFields = new LinkedHashMap<>();
        if (mc != null) {
            for (GeneratedFieldModel f : mc.effectiveFields(model)) {
                modelFields.put(f.name(), f);
            }
        }
        Map<String, ProductField> productFields = new LinkedHashMap<>();
        ProductClass pc = schema.get(name);
        if (pc != null) {
            for (ProductField pf : pc.fields()) {
                productFields.put(pf.name(), pf);
            }
        }

        Set<String> allNames = new LinkedHashSet<>(modelFields.keySet());
        allNames.addAll(productFields.keySet());
        for (String field : allNames) {
            GeneratedFieldModel mf = modelFields.get(field);
            ProductField pf = productFields.get(field);
            rows.addRow(new Object[]{
                    field,
                    mf == null ? "—" : modelDesc(mf),
                    pf == null ? "—" : pf.typeLabel(),
                    pf == null ? "" : shape(pf),
                    note(mf, pf)});
        }

        info.setText("  " + (schema.isMember(name) ? "member" : "reference-target")
                + (mc != null && !mc.alias().isBlank() ? " · alias: " + mc.alias() : "")
                + (mc != null && mc.reifiesStatements()
                        ? " · reifies: " + mc.statementSourceClass() : ""));
    }

    private static String modelDesc(GeneratedFieldModel f) {
        String base = f.type() == FieldType.ENTITY
                && f.entityClassName() != null && !f.entityClassName().isBlank()
                ? f.entityClassName()
                : f.type().toString();
        return base + " (" + f.cardinality() + ")";
    }

    private static String shape(ProductField pf) {
        if (pf.structural()) {
            return "structural";
        }
        if (pf.reference()) {
            return pf.collection() ? "ref[]" : "ref";
        }
        return pf.collection() ? "[]" : "value";
    }

    private String note(GeneratedFieldModel mf, ProductField pf) {
        if (mf == null && pf == null) {
            return "";
        }
        if (mf == null) {                       // product-only: the compile added it
            if (pf.structural()) {
                return "reify back-ref (structural, stripped)";
            }
            if ("Wikidata".equals(pf.name())) {
                return "identity link (kept, renamed)";
            }
            if (pf.reference() && pf.collection()) {
                return "reify list (from statementSourceClass)";
            }
            return "added";
        }
        if (pf == null) {                       // model-only: dropped in the product
            return "dropped";
        }
        if (mf.type() == FieldType.ENTITY && !pf.reference()) {
            return model != null && model.findClass(mf.entityClassName()) == null
                    ? "collapsed → String (unmodeled " + mf.entityClassName() + ")"
                    : "collapsed → label (Wikimedia-meta filtered)";
        }
        if (mf.cardinality() == wikidata.explore.model.FieldCardinality.AUTO && pf.collection()) {
            return "list (cardinality auto-detected)";
        }
        return "";
    }
}
