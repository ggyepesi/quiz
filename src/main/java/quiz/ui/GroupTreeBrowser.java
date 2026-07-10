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
 * - GroupTreeVirtualView owns group expansion and virtual leaf cards.
 * - QuizableSearchPanel owns search/sort/view configuration/highlighting.
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

        GroupTreeVirtualView groupedView = new GroupTreeVirtualView(
                root,
                initialConfig
        );

        JScrollPane outerScroll = new JScrollPane(groupedView);
        outerScroll.getVerticalScrollBar().setUnitIncrement(16);

        QuizableSearchPanel searchPanel = new QuizableSearchPanel(
                memberClass,
                sample
        );

        searchPanel.setHiddenFields(
                hiddenFields == null ? Set.of() : hiddenFields
                                   );
        searchPanel.setFieldTypes(fieldTypes);

        searchPanel.setTarget(
                groupedView,
                outerScroll
                             );

        add(searchPanel, BorderLayout.NORTH);
        add(outerScroll, BorderLayout.CENTER);
    }
}