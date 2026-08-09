package wikidata.explore.workbench;

import wikidata.explore.advisor.DecisionCatalog;
import wikidata.explore.advisor.DecisionContext;
import wikidata.explore.advisor.ModelElementExplanation;
import wikidata.explore.advisor.ModelExplanationFactory;
import wikidata.explore.advisor.SourceRouteExplanation;
import wikidata.explore.advisor.StructuralDecision;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * The model-builder explaining itself for the selected model, class or field.
 * Construction details come from {@link ModelExplanationFactory}; class-level
 * structural decisions remain the build script from {@link DecisionCatalog}.
 * Advisory (read-only): pressing Refresh re-evaluates the live model.
 */
public class ModelingGuidePanel extends JPanel {

    private final Supplier<DecisionContext> ctxSupplier;
    private final JEditorPane view = new JEditorPane("text/html", "");
    private final JLabel header = new JLabel(" ");

    public ModelingGuidePanel(Supplier<DecisionContext> ctxSupplier) {
        super(new BorderLayout(4, 4));
        this.ctxSupplier = ctxSupplier == null ? () -> null : ctxSupplier;

        view.setEditable(false);
        view.setOpaque(false);
        Font f = UIManager.getFont("Label.font");
        if (f != null) {
            view.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            view.setFont(f);
        }

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        JPanel north = new JPanel(new BorderLayout());
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        north.add(header, BorderLayout.CENTER);
        north.add(refresh, BorderLayout.EAST);

        add(north, BorderLayout.NORTH);
        add(new JScrollPane(view), BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        DecisionContext ctx = ctxSupplier.get();
        if (ctx == null || ctx.project() == null) {
            header.setText("Select or load a model");
            view.setText("");
            return;
        }

        ModelElementExplanation explanation = ModelExplanationFactory.explain(ctx);
        header.setText(explanation.breadcrumb());

        StringBuilder b = new StringBuilder("<html><body style='margin:4px'>");
        section(b, "Intent", explanation.intent());
        section(b, "Result", explanation.resultShape());

        if (!explanation.sourceRoutes().isEmpty()) {
            b.append("<h3 style='margin-bottom:3px'>Construction</h3>");
            for (SourceRouteExplanation route : explanation.sourceRoutes()) {
                b.append("<div style='margin-bottom:5px'><b>")
                 .append(route.priority()).append(". ")
                 .append(esc(route.sourceType().toString()))
                 .append(route.fallback() ? " (fallback)" : "")
                 .append("</b><br><span style='color:#555'>")
                 .append(esc(route.displayProperty()))
                 .append(" · ").append(esc(route.direction().toString()))
                 .append("</span></div>");
            }
        }

        section(b, "Example", explanation.example());

        if (!explanation.advice().isEmpty()) {
            b.append("<h3 style='margin-bottom:3px'>Advice</h3><ul style='margin-top:0'>");
            for (String advice : explanation.advice()) {
                b.append("<li>").append(esc(advice)).append("</li>");
            }
            b.append("</ul>");
        }

        if (ctx.clazz() != null && ctx.field() == null) {
            appendBuildSteps(b, ctx);
        }
        b.append("</body></html>");
        view.setText(b.toString());
        view.setCaretPosition(0);
    }

    private static void appendBuildSteps(StringBuilder b, DecisionContext ctx) {
        List<DecisionCatalog.Evaluated> evaluated = DecisionCatalog.evaluate(ctx);
        StructuralDecision next = DecisionCatalog.next(ctx);
        long open = DecisionCatalog.openCount(ctx);
        b.append("<h3 style='margin-bottom:3px'>Build steps (")
         .append(open).append(" open)</h3>");
        for (DecisionCatalog.Evaluated e : evaluated) {
            StructuralDecision d = e.decision();
            boolean isNext = next != null && d.id().equals(next.id());
            if (e.resolved()) {
                b.append("<div style='color:#3a7d3a'>&#10003; ")
                 .append(esc(d.question())).append("</div>");
            } else {
                b.append("<div style='margin-top:6px'>")
                 .append(isNext ? "<b>&#10148; " : "&#10067; ")
                 .append(esc(d.question())).append(isNext ? "</b>" : "")
                 .append("<div style='color:#555;margin-left:14px'>Tool: ")
                 .append(esc(d.tool())).append("</div>")
                 .append("<div style='color:#777;margin-left:14px'>Hint: ")
                 .append(esc(d.hint())).append("</div></div>");
            }
        }
        if (evaluated.isEmpty()) {
            b.append("No applicable steps.");
        }
    }

    private static void section(StringBuilder b, String heading, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        b.append("<h3 style='margin-bottom:3px'>").append(esc(heading))
         .append("</h3><div>").append(esc(value)).append("</div>");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;")
                                 .replace("<", "&lt;").replace(">", "&gt;");
    }
}
