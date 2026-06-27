package quiz;

import quiz.ui.viewconfig.QuizablePanelConfigEditor;

import javax.swing.*;
import java.awt.*;

/**
 * Combined editor for view/search/sort configurations.
 */
public class QuizConfigEditor extends JPanel {

    private final QuizConfig quizConfig;
    private final QuizablePanelConfigEditor viewEditor;
    private final QuizablePanelConfigEditor searchEditor;
    private final QuizablePanelConfigEditor sortEditor;

    public QuizConfigEditor(QuizConfig config) {
        this.quizConfig = config;
        this.viewEditor = new QuizablePanelConfigEditor(config.getViewConfig());
        this.searchEditor = new QuizablePanelConfigEditor(config.getSearchConfig());
        this.sortEditor = new QuizablePanelConfigEditor(config.getSortConfig());
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
