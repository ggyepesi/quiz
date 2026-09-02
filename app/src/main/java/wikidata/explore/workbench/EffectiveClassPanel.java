package wikidata.explore.workbench;

import wikidata.explore.advisor.EffectiveClassExplanation;
import wikidata.explore.advisor.EffectiveClassExplanations;
import wikidata.explore.advisor.EffectiveFieldExplanation;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Font;

/** Read-only explanation tab for the currently selected class or field. */
final class EffectiveClassPanel extends JPanel {
    private final JEditorPane body = new JEditorPane("text/html", "");

    EffectiveClassPanel() {
        super(new BorderLayout());
        body.setEditable(false);
        body.setOpaque(false);
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            body.setFont(font);
        }
        add(new JScrollPane(body), BorderLayout.CENTER);
        clear();
    }

    void showClass(GeneratedProjectModel project, GeneratedClassModel clazz) {
        if (clazz == null) { clear(); return; }
        body.setText(classHtml(EffectiveClassExplanations.explain(project, clazz), true));
        body.setCaretPosition(0);
    }

    void showField(GeneratedProjectModel project, GeneratedClassModel owner,
                   GeneratedFieldModel field) {
        if (owner == null || field == null) { clear(); return; }
        EffectiveFieldExplanation selected =
                EffectiveClassExplanations.explainField(project, owner, field);
        body.setText(fieldHtml(selected)
                + classHtml(EffectiveClassExplanations.explain(project, owner), false));
        body.setCaretPosition(0);
    }

    void clear() {
        body.setText("<html><body style='margin:8px'>Select a class or field to explain it.</body></html>");
        body.setCaretPosition(0);
    }

    private static String fieldHtml(EffectiveFieldExplanation value) {
        StringBuilder out = new StringBuilder("<html><body style='margin:8px'>");
        if (!value.available()) return out.append("<h2>Effective field</h2><b>Unavailable:</b> ")
                .append(esc(value.unavailableReason())).append("<hr>").toString();
        out.append("<h2>").append(esc(value.ownerClass())).append(".")
                .append(esc(value.fieldName())).append("</h2>");
        row(out, "Value", value.valueShape());
        row(out, "Source", value.source());
        row(out, "Target", value.target());
        row(out, "Role", value.role());
        return out.append("<hr>").toString();
    }

    private static String classHtml(EffectiveClassExplanation value, boolean documentStart) {
        StringBuilder out = new StringBuilder(documentStart
                ? "<html><body style='margin:8px'>" : "");
        if (!value.available()) {
            out.append("<h2>Effective class</h2><b>Unavailable:</b> ")
                    .append(esc(value.unavailableReason()));
            return out.append("</body></html>").toString();
        }
        out.append("<h2>Effective class: ").append(esc(value.className())).append("</h2>");
        row(out, "Declaration", value.declaration());
        row(out, "Instances", value.instances());
        out.append("<h3>Fields</h3><ul>");
        if (value.hasParts()) {
            // A reified statement is one fact with things said about it. Listing the
            // six as peers hides that two of them ARE the statement, and that only
            // some of the rest tell one statement from another that looks the same.
            section(out, "It is the statement",
                    value.fields(EffectiveClassExplanation.Part.SUBJECT),
                    value.fields(EffectiveClassExplanation.Part.VALUE));
            section(out, "Which one it is",
                    value.fields(EffectiveClassExplanation.Part.DISTINGUISHING));
            section(out, "Said about it",
                    value.fields(EffectiveClassExplanation.Part.DESCRIBING));
        } else {
            for (var field : value.fields()) {
                out.append("<li>").append(esc(field.name())).append(" — ")
                        .append(esc(field.type())).append(" · ")
                        .append(esc(field.origin())).append("</li>");
            }
            if (value.fields().isEmpty()) out.append("<li>none</li>");
        }
        if (!value.identity().isBlank()) {
            out.append("</ul><h3>Tells two apart</h3><ul><li>")
                    .append(esc(value.identity())).append("</li>");
        }
        out.append("</ul><h3>Used by</h3><ul>");
        // Three states, not two. Nobody has looked yet is not the same as looked and
        // found nothing, and rendering them alike would report a finding never made.
        if (value.uses().isEmpty()) {
            out.append("<li><i>not yet worked out — which fields and rules refer to "
                    + "this class is not computed here</i></li>");
        } else if (value.uses().get().isEmpty()) {
            out.append("<li>nothing in this project refers to it</li>");
        } else {
            for (String use : value.uses().get()) {
                out.append("<li>").append(esc(use)).append("</li>");
            }
        }
        return out.append("</ul></body></html>").toString();
    }

    @SafeVarargs
    private static void section(StringBuilder out, String title,
            java.util.List<EffectiveClassExplanation.Field>... groups) {
        java.util.List<EffectiveClassExplanation.Field> all = new java.util.ArrayList<>();
        for (var group : groups) all.addAll(group);
        if (all.isEmpty()) return;
        out.append("<li><b>").append(title).append("</b><ul>");
        for (var field : all) {
            // What fills it comes first: a reader asking about a field wants the
            // property, not where the declaration happens to live.
            out.append("<li>").append(esc(field.name()));
            if (!field.filledBy().isBlank()) {
                out.append(" ← ").append(esc(field.filledBy()));
            }
            out.append(" <i>(").append(esc(field.type()));
            if (!field.origin().isBlank()) out.append(", ").append(esc(field.origin()));
            out.append(")</i></li>");
        }
        out.append("</ul></li>");
    }

    private static void row(StringBuilder out, String label, String value) {
        out.append("<b>").append(label).append(":</b> ")
                .append(esc(value)).append("<br>");
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
