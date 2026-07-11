package quiz.ui;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.ui.viewconfig.FieldTypeSource;
import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

/**
 * Unified grouped browser:
 *
 * - VirtualizedGroupTreeView flattens the whole hierarchy (group headers + member
 *   cards) into ONE virtualized outline — expand/collapse and member field-chips
 *   behave like the flat card list, with a single scroll.
 * - QuizableSearchPanel owns search / sort / view configuration / highlighting.
 */
public final class GroupTreeBrowser extends JPanel {

    public GroupTreeBrowser(
            QuizableGroup root,
            Class<? extends Quizable> memberClass,
            Quizable sample,
            Set<String> hiddenFields,
            FieldTypeSource fieldTypes) {

        setLayout(new BorderLayout(6, 6));

        QuizablePanelConfig initialConfig =
                QuizablePanelConfig.all(memberClass);

        VirtualizedGroupTreeView groupedView =
                new VirtualizedGroupTreeView(root, initialConfig);
        groupedView.scrollPane().getVerticalScrollBar().setUnitIncrement(16);

        QuizableSearchPanel searchPanel = new QuizableSearchPanel(
                memberClass,
                sample
        );

        searchPanel.setHiddenFields(
                hiddenFields == null ? Set.of() : hiddenFields
                                   );
        searchPanel.setFieldTypes(fieldTypes);

        // The view owns its own (single) scroll pane — no outer wrapper.
        searchPanel.setTarget(groupedView, groupedView.scrollPane());

        add(searchPanel, BorderLayout.NORTH);
        add(groupedView, BorderLayout.CENTER);
    }
}