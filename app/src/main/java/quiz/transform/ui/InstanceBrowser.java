package quiz.transform.ui;

import objectview.Viewable;
import objectview.render.CardListView;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import objectview.viewconfig.FieldTypeSource;
import objectview.Viewable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Shared compact instance-card browser used by Transform and Validate. */
final class InstanceBrowser {
    private InstanceBrowser() {}

    static JComponent create(
            List<? extends Viewable> members,
            Viewable sample,
            Set<String> hiddenFields,
            FieldTypeSource fieldTypes,
            Consumer<Object> selectionListener) {
        CardListView view = new CardListView();
        RenderContext context = new RenderContext();
        context.setCollapsibleCards(true);
        if (selectionListener != null) {
            context.setSelectionEnabled(true);
            context.addSelectionListener(selectionListener);
        }
        view.setRenderContext(context);
        for (Viewable member : members) {
            view.addViewable(member);
        }
        view.createCardsPanel(1);

        JPanel panel = new JPanel(new BorderLayout());
        if (sample != null) {
            @SuppressWarnings("unchecked")
            Class<? extends Viewable> cls = (Class<? extends Viewable>) sample.getClass();
            SearchPanel search = new SearchPanel(cls, sample);
            search.setHiddenFields(hiddenFields == null ? Set.of() : hiddenFields);
            search.setFieldTypes(fieldTypes);
            search.setRenderContext(context);
            search.setTarget(view.getCardsPanel(), view.getCardsScrollPane());
            view.addTargetListener(search);
            panel.add(search, BorderLayout.NORTH);
        }
        panel.add(view.getCardsScrollPane(), BorderLayout.CENTER);

        // The first layout of a newly opened modeless dialog can precede viewport sizing.
        SwingUtilities.invokeLater(() -> {
            if (view.getVirtualList() != null) {
                view.getVirtualList().rebuild();
            }
        });
        return panel;
    }
}
