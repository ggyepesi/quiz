package wikidata.explore.workbench;

import wikidata.explore.advisor.DecisionCatalog;
import wikidata.explore.advisor.DecisionContext;
import wikidata.explore.advisor.StructuralDecision;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * The model-builder explaining itself: for the selected class it shows the
 * structural decisions from {@link DecisionCatalog} as the build script — resolved
 * steps with a check, and the open branches with the tool that answers each and
 * the hinted decision. Advisory (read-only): the user runs the named tool; pressing
 * Refresh re-evaluates as the model changes.
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
        if (ctx == null || ctx.clazz() == null) {
            header.setText("Select a class");
            view.setText("");
            return;
        }
        List<DecisionCatalog.Evaluated> ev = DecisionCatalog.evaluate(ctx);
        StructuralDecision next = DecisionCatalog.next(ctx);
        long open = DecisionCatalog.openCount(ctx);
        header.setText("Build steps · " + ctx.clazz().className()
                + "  (" + open + " open)");

        StringBuilder b = new StringBuilder("<html><body style='margin:4px'>");
        for (DecisionCatalog.Evaluated e : ev) {
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
        if (ev.isEmpty()) {
            b.append("No applicable steps.");
        }
        b.append("</body></html>");
        view.setText(b.toString());
        view.setCaretPosition(0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
