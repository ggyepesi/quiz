package quiz;

import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfigEditor;

import javax.swing.*;
import java.awt.*;

/**
 * Combined editor for view/search/sort configurations.
 */
public class QuizConfigEditor extends JPanel {

    private final QuizConfig quizConfig;
    private final ViewConfigEditor viewEditor;
    private final ViewConfigEditor searchEditor;
    private final ViewConfigEditor sortEditor;

    public QuizConfigEditor(QuizConfig config) {
        this.quizConfig = config;
        this.viewEditor = new ViewConfigEditor(config.getViewConfig(),
                FieldTableContributor.REORDERABLE);
        this.searchEditor = new ViewConfigEditor(config.getSearchConfig(),
                FieldTableContributor.REORDERABLE);
        this.sortEditor = new ViewConfigEditor(config.getSortConfig(),
                FieldTableContributor.REORDERABLE);
        buildLayout();
    }

    private void buildLayout() {
        setLayout(new BorderLayout());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("View", viewEditor);
        tabs.addTab("Search", searchEditor);
        tabs.addTab("Sort", sortEditor);
        add(tabs, BorderLayout.CENTER);
    }

    public QuizConfig getQuizConfig() {
        return quizConfig;
    }
}
