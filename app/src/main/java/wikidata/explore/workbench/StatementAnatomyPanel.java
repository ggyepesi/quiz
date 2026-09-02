package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.StatementFieldSemantics;
import wikidata.explore.transform.InvertConstruct;
import wikidata.explore.transform.ModelInverts;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;

/** Plain-language view of the statement structure already compiled for generation. */
final class StatementAnatomyPanel extends JPanel {
    private final JLabel meaning = new JLabel(" ");
    private final JTextArea mappings = new JTextArea(6, 34);

    StatementAnatomyPanel() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder("What becomes a record"));
        GridBagUtils.wideRow(this, 0, meaning);
        mappings.setEditable(false);
        mappings.setOpaque(false);
        mappings.setBorder(BorderFactory.createTitledBorder("Statement field mapping"));
        GridBagUtils.wideRow(this, 1, mappings);
    }

    void show(GeneratedProjectModel project, GeneratedClassModel clazz) {
        StatementClassSource source = clazz == null ? null : clazz.statementSource();
        if (source == null || !source.hasProperty()) {
            meaning.setText("Not configured yet.");
            mappings.setText("");
            return;
        }
        String property = propertyDisplay(clazz, source.propertyPid());
        String subjectType = source.hasSourceClass()
                ? source.sourceClassName() : subjectEntityClass(clazz);
        String restriction = source.hasValueSelection()
                ? " Values are restricted to '" + source.valueSelectionName() + "'." : "";
        meaning.setText("<html>Each <b>" + clazz.className() + "</b> is one <b>"
                + property + "</b> statement stored on "
                + (subjectType.isBlank() ? "its subject entity" : "a " + subjectType + " entity")
                + ". " + (source.hasSourceClass()
                ? "Statements are read from members of " + source.sourceClassName() + "."
                : "Matching subject entities are discovered from the property.")
                + restriction + "</html>");

        List<String> lines = new ArrayList<>();
        String valueField = StatementFieldSemantics.statementValueFieldName(clazz);
        for (GeneratedFieldModel field : clazz.fields()) {
            if (StatementFieldSemantics.isStatementSubjectField(clazz, field)) {
                lines.add("subject entity  → " + field.name() + type(field));
            } else if (field.name().equals(valueField)) {
                lines.add("statement value → " + field.name() + type(field));
            } else if (StatementFieldSemantics.isQualifierField(clazz, field)) {
                lines.add("qualifier " + named(field.mapping().propertyLabel(),
                        field.mapping().qualifierPid()) + " → " + field.name());
            }
        }
        for (InvertConstruct inverse : inverts(project)) {
            if (clazz.className().equals(inverse.sourceType())) {
                lines.add("records whose " + inverse.refField() + " is a "
                        + inverse.targetType() + " → " + inverse.targetType() + "."
                        + inverse.backRefField() + " (list)");
            }
        }
        mappings.setText(lines.isEmpty()
                ? "Add fields for the statement subject, value, and qualifiers."
                : String.join("\n", lines));
        mappings.setCaretPosition(0);
    }

    String meaningText() { return meaning.getText(); }
    String mappingsText() { return mappings.getText(); }

    private static List<InvertConstruct> inverts(GeneratedProjectModel project) {
        if (project == null) return List.of();
        try {
            return ModelInverts.derive(ProjectModelCompiler.compile(project));
        } catch (IllegalArgumentException | IllegalStateException incompleteDraft) {
            return List.of();
        }
    }

    private static String propertyDisplay(GeneratedClassModel clazz, String pid) {
        for (GeneratedFieldModel field : clazz.fields()) {
            if (pid.equals(field.mapping().propertyPid())
                    && !field.mapping().propertyLabel().isBlank()) {
                return named(field.mapping().propertyLabel(), pid);
            }
        }
        return pid;
    }

    private static String subjectEntityClass(GeneratedClassModel clazz) {
        for (GeneratedFieldModel field : clazz.fields()) {
            if (StatementFieldSemantics.isStatementSubjectField(clazz, field)) {
                return field.entityClassName() == null ? "" : field.entityClassName();
            }
        }
        return "";
    }

    private static String named(String label, String id) {
        return label == null || label.isBlank() ? id : label + " (" + id + ")";
    }

    private static String type(GeneratedFieldModel field) {
        return field.entityClassName() == null || field.entityClassName().isBlank()
                ? "" : " (" + field.entityClassName() + ")";
    }
}
