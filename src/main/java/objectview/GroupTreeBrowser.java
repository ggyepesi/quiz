package objectview;

import objectview.viewconfig.FieldTypeSource;
import objectview.viewconfig.ViewablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

/**
 * Unified grouped browser:
 *
 * - VirtualizedGroupTreeView flattens the whole hierarchy (group headers + member
 *   cards) into ONE virtualized outline — expand/collapse and member field-chips
 *   behave like the flat card list, with a single scroll.
 * - ViewableSearchPanel owns search / sort / view configuration / highlighting.
 */
public final class GroupTreeBrowser extends JPanel {

    public GroupTreeBrowser(
            ViewableGroup<?> root,
            Class<? extends Viewable> memberClass,
            Viewable sample,
            Set<String> hiddenFields,
            FieldTypeSource fieldTypes) {

        setLayout(new BorderLayout(6, 6));

        VirtualizedGroupTreeView groupedView = new VirtualizedGroupTreeView(
                root,
                ViewablePanelConfig.all(memberClass));

        groupedView.scrollPane()
                   .getVerticalScrollBar()
                   .setUnitIncrement(16);

        ViewableSearchPanel searchPanel = new ViewableSearchPanel(
                memberClass,
                sample);

        searchPanel.setHiddenFields(
                hiddenFields == null ? Set.of() : hiddenFields);
        searchPanel.setFieldTypes(fieldTypes);

        groupedView.setTargetListener(searchPanel);
        searchPanel.setRenderContext(groupedView.renderContext());
        searchPanel.setTarget(groupedView, groupedView.scrollPane());

        add(searchPanel, BorderLayout.NORTH);
        add(groupedView, BorderLayout.CENTER);
    }
}
